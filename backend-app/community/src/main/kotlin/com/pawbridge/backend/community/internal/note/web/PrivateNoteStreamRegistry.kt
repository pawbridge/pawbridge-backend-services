package com.pawbridge.backend.community.internal.note.web

import com.pawbridge.backend.community.internal.note.application.NoteChangeHints
import com.pawbridge.backend.community.internal.note.application.NoteDeliveryHints
import com.pawbridge.backend.community.internal.note.application.NoteNotifications
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier
import java.util.function.LongFunction
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.context.SmartLifecycle
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

class PrivateNoteStreamRegistry internal constructor(
    private val clock: Clock,
    private val emitterFactory: LongFunction<StreamEmitter>,
    private val writes: ExecutorService,
    private val completions: ExecutorService,
    private val heartbeat: ScheduledExecutorService,
) : NoteChangeHints, NoteDeliveryHints, SmartLifecycle, DisposableBean {

    // Package-private in Java; internal here (public in bytecode) so the same-package Java test can extend it.
    internal open class StreamEmitter(lifetime: Long) : SseEmitter(lifetime) {

        @Throws(IOException::class)
        fun sendIfActive(
            event: SseEventBuilder,
            active: BooleanSupplier,
        ): Boolean {
            // Use Spring's actual write lock: a queued writer may expire while waiting for it.
            writeLock.lock()
            try {
                if (!active.asBoolean) {
                    return false
                }
                send(event)
                return true
            } finally {
                writeLock.unlock()
            }
        }
    }

    private class ConnectionLimitException : ResponseStatusException(
        HttpStatus.TOO_MANY_REQUESTS,
        "알림 연결이 너무 많습니다.",
    ) {
        private val headers = HttpHeaders()

        init {
            headers.set(HttpHeaders.RETRY_AFTER, Integer.toString(HEARTBEAT_SECONDS))
        }

        override fun getHeaders(): HttpHeaders = headers
    }

    private class Connection(
        val emitter: StreamEmitter,
        val expiresAt: Instant,
        val clientId: UUID?,
    ) {
        @Volatile
        var closing: Boolean = false

        // Protected by registryLock. A rejected completion is retried by heartbeat.
        var completionQueued: Boolean = false
    }

    private data class Registration(
        val memberId: Long,
        val connectionId: UUID,
        val connection: Connection,
    )

    private data class Opening(
        val current: Registration,
        val previous: List<Registration>,
    )

    private val registryLock = Any()
    private val connections: MutableMap<Long, MutableMap<UUID, Connection>> = HashMap()

    @Volatile
    private var stopping = false

    constructor(clock: Clock) : this(
        clock,
        LongFunction { lifetime -> StreamEmitter(lifetime) },
        boundedExecutor("private-note-stream", 256),
        boundedExecutor("private-note-close", MAX_CONNECTIONS),
        Executors.newSingleThreadScheduledExecutor { work -> daemonThread(work, "private-note-heartbeat") },
    )

    init {
        heartbeat.scheduleAtFixedRate({ heartbeat() }, HEARTBEAT_SECONDS.toLong(), HEARTBEAT_SECONDS.toLong(), TimeUnit.SECONDS)
    }

    fun open(
        memberId: Long,
        tokenExpiresAt: Instant,
    ): SseEmitter = open(memberId, tokenExpiresAt, null)

    fun open(
        memberId: Long,
        tokenExpiresAt: Instant,
        clientId: UUID?,
    ): SseEmitter {
        val opening = register(memberId, tokenExpiresAt, clientId)
        val registration = opening.current
        val emitter: SseEmitter = registration.connection.emitter
        emitter.onCompletion { remove(registration) }
        // Spring reports a timeout as an error unless the emitter completes inside this callback,
        // so it must not be handed to the completion executor.
        emitter.onTimeout { complete(registration) }
        emitter.onError { remove(registration) }
        opening.previous.forEach { requestCompletion(it) }
        // The initial event is buffered by Spring until the controller initializes the response.
        send(registration, "ready", null)
        return emitter
    }

    private fun register(
        memberId: Long,
        tokenExpiresAt: Instant,
        clientId: UUID?,
    ): Opening {
        synchronized(registryLock) {
            val now = clock.instant()
            val lifetime = Math.min(MAX_CONNECTION_LIFETIME_MILLIS, Duration.between(now, tokenExpiresAt).toMillis())
            if (lifetime <= 0) {
                throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
            }
            if (stopping) {
                throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)
            }
            val memberConnections = connections[memberId]
            val connectionCount = connections.values.sumOf { it.size }
            val previous: List<Registration> = if (clientId == null || memberConnections == null) {
                listOf()
            } else {
                memberConnections.entries
                    .filter { entry -> clientId == entry.value.clientId }
                    .map { entry -> Registration(memberId, entry.key, entry.value) }
            }
            val memberSlots: Long = if (memberConnections == null) {
                0
            } else {
                (
                    memberConnections.values.count { connection -> connection.clientId == null } +
                        memberConnections.values.mapNotNull { connection -> connection.clientId }.distinct().size
                    ).toLong()
            }
            val pendingReplacements: Long = if (memberConnections == null) {
                0
            } else {
                memberConnections.values.count { connection -> connection.clientId != null && connection.closing }.toLong()
            }
            // Same-tab replacements share one member slot. Pending physical connections still
            // count toward the global cap until completion, including rejected close tasks.
            if ((previous.isEmpty() && memberSlots >= MAX_CONNECTIONS_PER_MEMBER) ||
                (!previous.isEmpty() && pendingReplacements >= MAX_PENDING_REPLACEMENTS_PER_MEMBER) ||
                connectionCount >= MAX_CONNECTIONS
            ) {
                throw ConnectionLimitException()
            }
            val connectionId = UUID.randomUUID()
            val connection = Connection(emitterFactory.apply(lifetime), now.plusMillis(lifetime), clientId)
            previous.forEach { registration -> registration.connection.closing = true }
            connections.computeIfAbsent(memberId) { HashMap() }[connectionId] = connection
            return Opening(Registration(memberId, connectionId, connection), previous)
        }
    }

    override fun resync(memberId: Long) {
        memberConnections(memberId).forEach { registration -> enqueue { send(registration, "resync", null) } }
    }

    fun closeMember(memberId: Long) {
        memberConnections(memberId).forEach { requestCompletion(it) }
    }

    fun resyncAll() {
        allConnections().forEach { registration -> enqueue { send(registration, "resync", null) } }
    }

    override fun publish(
        memberId: Long,
        notification: NoteNotifications.Notification,
    ) {
        memberConnections(memberId).forEach { registration ->
            enqueue { send(registration, "note", notification) }
        }
    }

    // 채팅 신호는 본문이 없다. 쪽지 알림과 같은 연결, 같은 한도, 같은 최선 전달을 쓴다.
    fun publishChat(
        memberId: Long,
        signal: Any,
    ) {
        memberConnections(memberId).forEach { registration ->
            enqueue { send(registration, "chat", signal) }
        }
    }

    // Public (package-private in Java) so the same-package Java test can drive it.
    fun heartbeat() {
        for (registration in allConnections()) {
            val connection = registration.connection
            if (connection.closing || !connection.expiresAt.isAfter(clock.instant())) {
                requestCompletion(registration)
            } else {
                enqueue { send(registration, "heartbeat", null) }
            }
        }
    }

    private fun memberConnections(memberId: Long): List<Registration> {
        synchronized(registryLock) {
            val memberConnections = connections[memberId] ?: return listOf()
            return memberConnections.entries.map { entry -> Registration(memberId, entry.key, entry.value) }
        }
    }

    private fun allConnections(): List<Registration> {
        synchronized(registryLock) {
            return connections.entries.flatMap { member ->
                member.value.entries.map { entry -> Registration(member.key, entry.key, entry.value) }
            }
        }
    }

    // Caller holds registryLock.
    private fun registered(registration: Registration): Boolean {
        val memberConnections = connections[registration.memberId]
        return memberConnections != null &&
            memberConnections[registration.connectionId] === registration.connection
    }

    private fun remove(registration: Registration) {
        synchronized(registryLock) {
            if (!registered(registration)) {
                return
            }
            val memberConnections = connections[registration.memberId]!!
            registration.connection.closing = true
            memberConnections.remove(registration.connectionId)
            if (memberConnections.isEmpty()) {
                connections.remove(registration.memberId)
            }
        }
    }

    private fun requestCompletion(registration: Registration) {
        val connection = registration.connection
        synchronized(registryLock) {
            if (!registered(registration) || connection.completionQueued) {
                return
            }
            connection.closing = true
            connection.completionQueued = true
        }
        try {
            completions.execute { complete(registration) }
        } catch (full: RejectedExecutionException) {
            synchronized(registryLock) {
                connection.completionQueued = false
            }
            // Keep this closing connection tracked and counted until completion can be retried.
        }
    }

    private fun complete(registration: Registration) {
        val connection = registration.connection
        connection.closing = true
        try {
            connection.emitter.complete()
        } catch (recycled: IllegalStateException) {
            // The container already finished this request (timeout or disconnect); nothing is left to close.
        } finally {
            remove(registration)
        }
    }

    private fun enqueue(work: Runnable) {
        try {
            writes.execute(work)
        } catch (full: RejectedExecutionException) {
            // REST repairs a dropped notification hint; a later heartbeat retries disconnect detection.
        }
    }

    private fun send(
        registration: Registration,
        eventName: String,
        payload: Any?,
    ) {
        val connection = registration.connection
        synchronized(registryLock) {
            if (!registered(registration) || connection.closing) {
                return
            }
        }
        if (!connection.expiresAt.isAfter(clock.instant())) {
            requestCompletion(registration)
            return
        }
        try {
            val sent = connection.emitter.sendIfActive(
                SseEmitter.event().name(eventName).data(payload ?: "{}"),
                BooleanSupplier { !connection.closing && connection.expiresAt.isAfter(clock.instant()) },
            )
            if (!sent) {
                requestCompletion(registration)
            }
        } catch (disconnected: IOException) {
            // Spring/container error callbacks finish a failed write; do not complete it again here.
            remove(registration)
        } catch (disconnected: IllegalStateException) {
            remove(registration)
        }
    }

    // Stop before Boot 3.4.11 web-server graceful shutdown (phase 2147482623).
    // This closes owned SSEs before the server waits for active HTTP requests.
    override fun getPhase(): Int = Int.MAX_VALUE

    override fun isRunning(): Boolean = !stopping

    override fun start() {
        if (stopping) {
            throw IllegalStateException("A stopped note stream registry cannot be restarted")
        }
    }

    override fun stop(callback: Runnable) {
        try {
            stop()
        } finally {
            callback.run()
        }
    }

    override fun stop() {
        synchronized(registryLock) {
            if (stopping) {
                return
            }
            stopping = true
        }
        val deadline = System.nanoTime() + SHUTDOWN_BUDGET_NANOS
        heartbeat.shutdownNow()
        writes.shutdownNow()
        allConnections().forEach { requestCompletion(it) }
        completions.shutdown()
        var terminated = true
        for (executor in listOf<ExecutorService>(heartbeat, writes, completions)) {
            try {
                val remaining = Math.max(0, deadline - System.nanoTime())
                if (!executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                    terminated = false
                    executor.shutdownNow()
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                terminated = false
                executor.shutdownNow()
            }
        }
        if (terminated && allConnections().isEmpty()) {
            log.info("Private note stream resources stopped")
        } else {
            // Bounded stop is not proof that a blocked network writer terminated.
            // The real opened-SSE shutdown gate requires the success signal above.
            log.warn("Private note stream resources did not stop within budget")
        }
    }

    override fun destroy() {
        stop()
    }

    private companion object {
        private val log = LoggerFactory.getLogger(PrivateNoteStreamRegistry::class.java)
        private val SHUTDOWN_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(5)
        private const val MAX_CONNECTION_LIFETIME_MILLIS = 300_000L
        private const val MAX_CONNECTIONS_PER_MEMBER = 3
        private const val MAX_PENDING_REPLACEMENTS_PER_MEMBER = 3
        private const val MAX_CONNECTIONS = 500
        private const val HEARTBEAT_SECONDS = 15

        private fun boundedExecutor(
            name: String,
            capacity: Int,
        ): ExecutorService =
            ThreadPoolExecutor(
                2,
                2,
                0,
                TimeUnit.SECONDS,
                ArrayBlockingQueue(capacity),
                ThreadFactory { work -> daemonThread(work, name) },
            )

        private fun daemonThread(
            work: Runnable,
            name: String,
        ): Thread {
            val thread = Thread(work, name)
            thread.isDaemon = true
            return thread
        }
    }
}
