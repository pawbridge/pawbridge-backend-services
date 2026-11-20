package com.pawbridge.backend.community.internal.note.web

import com.pawbridge.backend.community.internal.note.application.NoteNotifications
import com.pawbridge.backend.community.internal.note.application.RequireActiveNoteStream
import com.pawbridge.backend.community.support.TestFaker
import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Consumer
import java.util.function.LongFunction

/** Manual executors/emitters verify registry policy; they do not model real sockets or servlet shutdown. */
class PrivateNoteStreamRegistryTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `탈퇴한 회원의 탭이 있거나 없고 다른 회원이 있을 때__그 회원만 닫고 전체를 다시 동기화하면__소유한 탭만 완료하고 레지스트리는 계속 동작한다`(
        hasOwner: Boolean,
    ) {
        // given
        val heartbeat = mockk<ScheduledExecutorService>()

        every { heartbeat.scheduleAtFixedRate(any(), any(), any(), any()) } returns null
        every { heartbeat.shutdownNow() } returns mutableListOf()
        every { heartbeat.awaitTermination(any(), any()) } returns true
        Fixture(heartbeat).use { f ->
            if (hasOwner) {
                f.streams.open(7, f.expiry())
                f.streams.open(7, f.expiry())
            }
            f.streams.open(8, f.expiry())
            val other = if (hasOwner) 2 else 0

            // when
            f.streams.closeMember(7)
            f.streams.resyncAll()
            f.writes.runAll()
            f.completions.runAll()

            // then
            for (i in 0 until other) {
                assertThat(f.emitters[i].completed).hasValue(1)
                assertThat(f.emitters[i].sends).hasValue(1)
            }
            assertThat(f.emitters[other].completed).hasValue(0)
            assertThat(f.emitters[other].frames).hasSize(2)
            assertThat(f.emitters[other].frames.last().filter { it is String }.map { it.toString() })
                .anySatisfy({ part -> assertThat(part).contains("event:resync\n") })
            assertThat(f.streams.isRunning()).isTrue()

            // when
            f.streams.open(9, f.expiry())
            f.streams.resync(8)
            f.writes.runAll()

            // then
            assertThat(f.emitters[other].sends).hasValue(3)
        }
    }

    @Test
    fun `회원 제거 중 완료 큐가 가득 찼을 때__기존 하트비트가 재시도하면__닫는 탭을 계속 센 채 결국 닫고 힌트는 보내지 않는다`() {
        // given
        val heartbeat = mockk<ScheduledExecutorService>()

        every { heartbeat.scheduleAtFixedRate(any(), any(), any(), any()) } returns null
        every { heartbeat.shutdownNow() } returns mutableListOf()
        every { heartbeat.awaitTermination(any(), any()) } returns true
        Fixture(heartbeat).use { f ->
            f.completions.capacity = 1
            f.streams.open(7, f.expiry())
            f.streams.open(7, f.expiry())
            f.streams.open(8, f.expiry())

            // when
            f.streams.closeMember(7)
            f.streams.resyncAll()
            f.writes.runAll()

            // then
            assertThat(f.emitters[0].sends).hasValue(1)
            assertThat(f.emitters[1].sends).hasValue(1)

            // when
            f.completions.runAll()
            f.streams.heartbeat()
            f.completions.runAll()

            // then
            assertThat(f.emitters[0].completed).hasValue(1)
            assertThat(f.emitters[1].completed).hasValue(1)
            assertThat(f.emitters[2].completed).hasValue(0)
            assertThat(f.streams.isRunning()).isTrue()
        }
    }

    @Test
    fun `수신자의 탭들과 다른 회원이 있을 때__확정된 쪽지를 발행하면__같은 제한된 수신자 레지스트리만 쓰고 정확한 페이로드를 보낸다`() {
        // given
        val heartbeat = mockk<ScheduledExecutorService>()

        every { heartbeat.scheduleAtFixedRate(any(), any(), any(), any()) } returns null
        every { heartbeat.shutdownNow() } returns mutableListOf()
        every { heartbeat.awaitTermination(any(), any()) } returns true
        Fixture(heartbeat).use { f ->
            f.streams.open(7, f.expiry())
            f.streams.open(7, f.expiry())
            f.streams.open(8, f.expiry())
            val payload = notification(f.clock.instant())

            // when
            f.streams.publish(7, payload)

            // then
            assertThat(f.writes.tasks).hasSize(2)
            assertThat(f.emitters).allSatisfy({ emitter -> assertThat(emitter.sends).hasValue(1) })

            // when
            f.writes.runAll()

            // then
            for (index in intArrayOf(0, 1)) {
                val frames = f.emitters[index].frames
                assertThat(frames).hasSize(2)
                assertThat(frames.last()).contains(payload)
                assertThat(frames.last().filter { it is String }.map { it.toString() })
                    .anySatisfy({ part -> assertThat(part).contains("event:note\n") })
            }
            assertThat(f.emitters[2].frames).hasSize(1)
        }
    }

    @Test
    fun `수신자의 탭들과 다른 회원이 있을 때__채팅 신호를 발행하면__수신자 탭에만 chat 이벤트로 같은 페이로드를 보낸다`() {
        // given
        val heartbeat = mockk<ScheduledExecutorService>()

        every { heartbeat.scheduleAtFixedRate(any(), any(), any(), any()) } returns null
        every { heartbeat.shutdownNow() } returns mutableListOf()
        every { heartbeat.awaitTermination(any(), any()) } returns true
        Fixture(heartbeat).use { f ->
            f.streams.open(7, f.expiry())
            f.streams.open(7, f.expiry())
            f.streams.open(8, f.expiry())
            val signal = mapOf("memberId" to 7L, "kind" to "MESSAGE", "roomId" to TestFaker.uuid(), "sequence" to 3L)

            // when
            f.streams.publishChat(7, signal)
            f.writes.runAll()

            // then
            for (index in intArrayOf(0, 1)) {
                val frames = f.emitters[index].frames
                assertThat(frames).hasSize(2)
                assertThat(frames.last()).contains(signal)
                assertThat(frames.last().filter { it is String }.map { it.toString() })
                    .anySatisfy({ part -> assertThat(part).contains("event:chat\n") })
            }
            assertThat(f.emitters[2].frames).hasSize(1)
        }
    }

    @Test
    fun `쓰기 큐가 가득 찼을 때__채팅 신호를 발행하면__신호만 버리고 예외를 던지지 않는다`() {
        // given
        val heartbeat = mockk<ScheduledExecutorService>()

        every { heartbeat.scheduleAtFixedRate(any(), any(), any(), any()) } returns null
        every { heartbeat.shutdownNow() } returns mutableListOf()
        every { heartbeat.awaitTermination(any(), any()) } returns true
        Fixture(heartbeat).use { f ->
            f.streams.open(7, f.expiry())
            f.writes.runAll()
            f.writes.capacity = 0

            // when
            assertThatCode { f.streams.publishChat(7, mapOf("kind" to "MESSAGE")) }.doesNotThrowAnyException()
            f.writes.runAll()

            // then
            assertThat(f.emitters[0].frames).hasSize(1)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["expired", "full"])
    fun `만료된 연결이거나 쓰기 큐가 가득 찼을 때__쪽지를 발행하면__기존의 제한된 최선 전달을 유지하고 늦은 전달은 없다`(
        condition: String,
    ) {
        // given
        val heartbeat = mockk<ScheduledExecutorService>()

        every { heartbeat.scheduleAtFixedRate(any(), any(), any(), any()) } returns null
        every { heartbeat.shutdownNow() } returns mutableListOf()
        every { heartbeat.awaitTermination(any(), any()) } returns true
        Fixture(heartbeat).use { f ->
            f.streams.open(7, f.clock.instant().plusSeconds(if (condition == "expired") 1 else 60))
            val payload = notification(f.clock.instant())
            f.writes.capacity = 1

            // when
            f.streams.publish(7, payload)
            if (condition == "expired") {
                f.clock.advance(2)
            } else {
                assertThatCode { f.streams.publish(7, payload) }.doesNotThrowAnyException()
            }

            // then
            assertThat(f.writes.tasks).hasSize(1)

            // when
            f.writes.runAll()
            f.completions.runAll()

            // then
            assertThat(f.emitters.first().sends).hasValue(if (condition == "expired") 1 else 2)
            assertThat(f.emitters.first().completed).hasValue(if (condition == "expired") 1 else 0)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["member", "global", "pending"])
    fun `연결 한도가 주어졌을 때__한도를 넘어 열면__429와 Retry-After를 돌려주고 기존 연결은 유지한다`(
        budget: String,
    ) {
        // given
        val heartbeat = mockk<ScheduledExecutorService>()

        every { heartbeat.scheduleAtFixedRate(any(), any(), any(), any()) } returns null
        every { heartbeat.shutdownNow() } returns mutableListOf()
        every { heartbeat.awaitTermination(any(), any()) } returns true
        Fixture(heartbeat).use { f ->
            val client = TestFaker.uuid()
            if (budget == "member") {
                for (n in 0 until 3) {
                    f.streams.open(7, f.expiry())
                }
            } else if (budget == "global") {
                for (id in 1L..500L) {
                    f.streams.open(id, f.expiry())
                }
            } else {
                for (n in 0 until 4) {
                    f.streams.open(7, f.expiry(), client)
                }
            }

            // when
            val failure = catchThrowable {
                if (budget == "pending") {
                    f.streams.open(7, f.expiry(), client)
                } else {
                    f.streams.open(if (budget == "global") 501 else 7, f.expiry())
                }
            }

            // then
            assertFull(failure)
            if (budget == "pending") {
                assertThat(f.emitters).hasSize(4)
            } else {
                assertThat(f.emitters).hasSize(if (budget == "global") 500 else 3)
                assertThat(f.emitters).allSatisfy({ emitter -> assertThat(emitter.completed.get()).isZero() })
            }
        }
    }

    @Test
    fun `같은 클라이언트가 재연결하고 이전 콜백이 늦게 올 때__다시 동기화하면__교체된 연결과 다른 탭과 다른 회원을 보존한다`() {
        // given
        val heartbeat = mockk<ScheduledExecutorService>()

        every { heartbeat.scheduleAtFixedRate(any(), any(), any(), any()) } returns null
        every { heartbeat.shutdownNow() } returns mutableListOf()
        every { heartbeat.awaitTermination(any(), any()) } returns true
        Fixture(heartbeat).use { f ->
            val tab = TestFaker.uuid()
            f.streams.open(7, f.expiry(), tab)
            val old = f.emitters.first()
            f.streams.open(8, f.expiry(), tab)
            f.streams.open(7, f.expiry(), TestFaker.uuid())
            f.streams.open(7, f.expiry(), tab)
            f.completions.runAll()
            old.completion.run()

            // when
            f.streams.resync(7)
            f.writes.runAll()

            // then
            assertThat(old.completed).hasValue(1)
            assertThat(old.sends).hasValue(1)
            assertThat(f.emitters[1].sends).hasValue(1)
            assertThat(f.emitters[2].sends).hasValue(2)
            assertThat(f.emitters[3].sends).hasValue(2)
            f.streams.open(7, f.expiry(), TestFaker.uuid())
            assertFull { f.streams.open(7, f.expiry()) }
        }
    }

    @ParameterizedTest
    @ValueSource(longs = [12, 600])
    fun `검증된 JWT 수명이 주어졌을 때__열면__토큰 수명 또는 5분 상한과 15초 하트비트 한 개를 쓴다`(seconds: Long) {
        // given
        val heartbeat = mockk<ScheduledExecutorService>()

        every { heartbeat.scheduleAtFixedRate(any(), any(), any(), any()) } returns null
        every { heartbeat.shutdownNow() } returns mutableListOf()
        every { heartbeat.awaitTermination(any(), any()) } returns true
        Fixture(heartbeat).use { f ->
            // when
            val emitter = f.streams.open(7, f.clock.instant().plusSeconds(seconds))

            // then
            assertThat(emitter.timeout).isEqualTo(Math.min(seconds, 300) * 1000)
            verify(exactly = 1) {
                heartbeat.scheduleAtFixedRate(any<Runnable>(), 15L, 15L, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun `회원 조회가 검증된 만료 시각을 넘길 때__열면__회원 조회는 끝내지만 에미터는 받아들이지 않는다`() {
        // given
        val members = mockk<ContactMemberQuery>()
        val heartbeat = mockk<ScheduledExecutorService>()

        every { heartbeat.scheduleAtFixedRate(any(), any(), any(), any()) } returns null
        every { heartbeat.shutdownNow() } returns mutableListOf()
        every { heartbeat.awaitTermination(any(), any()) } returns true
        Fixture(heartbeat).use { f ->
            val expiry = f.clock.instant().plusSeconds(1)
            val authentication = UsernamePasswordAuthenticationToken(7L, null, emptyList<GrantedAuthority>())
            authentication.details = expiry
            val controller = NoteStreamController(RequireActiveNoteStream(members), f.streams)

            every { members.get(any()) } answers {
                f.clock.advance(2)
                ContactMember(7L, "", true, false)
            }

            // when
            val thrown = assertThatThrownBy { controller.open(7L, authentication, null) }

            // then
            thrown.isInstanceOfSatisfying(ResponseStatusException::class.java) { failure ->
                assertThat(failure.statusCode.value()).isEqualTo(401)
            }
            verify(exactly = 1) { members.get(7) }
            confirmVerified(members)
            assertThat(f.emitters).isEmpty()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `대기 중인 힌트가 있거나 스프링 쓰기 잠금이 잡혀 있을 때__실제 전송 전에 토큰이 만료되면__재동기화를 억제하고 연결을 완료한다`(
        locked: Boolean,
    ) {
        // given
        val heartbeat = mockk<ScheduledExecutorService>()

        every { heartbeat.scheduleAtFixedRate(any(), any(), any(), any()) } returns null
        every { heartbeat.shutdownNow() } returns mutableListOf()
        every { heartbeat.awaitTermination(any(), any()) } returns true
        Fixture(heartbeat).use { f ->
            f.streams.open(7, f.clock.instant().plusSeconds(1))
            val emitter = f.emitters.first()
            f.streams.resync(7)

            // when
            if (locked) {
                val worker = Executors.newSingleThreadExecutor()
                emitter.lock()
                try {
                    val task = worker.submit(Runnable { f.writes.runAll() })
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                    while (!emitter.writerWaiting() && System.nanoTime() < deadline) {
                        Thread.onSpinWait()
                    }
                    assertThat(emitter.writerWaiting()).isTrue()
                    f.clock.advance(2)
                    emitter.unlock()
                    task.get(1, TimeUnit.SECONDS)
                } finally {
                    if (emitter.lockOwned()) {
                        emitter.unlock()
                    }
                    worker.shutdownNow()
                    assertThat(worker.awaitTermination(1, TimeUnit.SECONDS)).isTrue()
                }
            } else {
                f.clock.advance(2)
                f.writes.runAll()
            }
            f.completions.runAll()

            // then
            assertThat(emitter.sends).hasValue(1)
            assertThat(emitter.completed).hasValue(1)
        }
    }

    @Test
    fun `완료가 거부됐을 때__하트비트가 재시도하면__닫는 슬롯을 계속 세고 이미 대기 중인 힌트는 억제한다`() {
        // given
        val heartbeat = mockk<ScheduledExecutorService>()

        every { heartbeat.scheduleAtFixedRate(any(), any(), any(), any()) } returns null
        every { heartbeat.shutdownNow() } returns mutableListOf()
        every { heartbeat.awaitTermination(any(), any()) } returns true
        Fixture(heartbeat).use { f ->
            f.streams.open(7, f.clock.instant().plusSeconds(1))
            f.streams.resync(7)
            f.clock.advance(2)
            f.completions.rejectNext = true

            // when
            f.streams.heartbeat()
            f.streams.open(7, f.expiry())
            f.streams.open(7, f.expiry())

            // then
            assertFull { f.streams.open(7, f.expiry()) }

            // when
            f.writes.runAll()

            // then
            assertThat(f.emitters.first().sends).hasValue(1)

            // when
            f.streams.heartbeat()
            f.completions.runAll()
            f.streams.open(7, f.expiry())

            // then
            assertThat(f.emitters.first().completed).hasValue(1)
        }
    }

    @Test
    fun `쓰기 큐가 가득 찼을 때__다른 힌트가 도착하면__힌트만 버리고 큐를 무한히 늘리거나 예외를 던지지 않는다`() {
        // given
        val heartbeat = mockk<ScheduledExecutorService>()

        every { heartbeat.scheduleAtFixedRate(any(), any(), any(), any()) } returns null
        every { heartbeat.shutdownNow() } returns mutableListOf()
        every { heartbeat.awaitTermination(any(), any()) } returns true
        Fixture(heartbeat).use { f ->
            f.writes.capacity = 1
            f.streams.open(7, f.expiry())
            f.streams.resync(7)

            // when
            val action = assertThatCode { f.streams.resync(7) }

            // then
            action.doesNotThrowAnyException()
            assertThat(f.writes.tasks).hasSize(1)

            // when
            f.writes.runAll()

            // then
            assertThat(f.emitters.first().sends).hasValue(2)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["error", "timeout"])
    fun `연결 끊김 또는 타임아웃 콜백이 있을 때__연결을 정리하면__그 연결의 슬롯만 바로 풀고 대기 중인 쓰기는 억제한다`(
        callback: String,
    ) {
        // given
        val heartbeat = mockk<ScheduledExecutorService>()

        every { heartbeat.scheduleAtFixedRate(any(), any(), any(), any()) } returns null
        every { heartbeat.shutdownNow() } returns mutableListOf()
        every { heartbeat.awaitTermination(any(), any()) } returns true
        Fixture(heartbeat).use { f ->
            for (n in 0 until 3) {
                f.streams.open(7, f.expiry())
            }
            val closing = f.emitters.first()
            f.streams.resync(7)

            // when
            if (callback == "error") {
                closing.error.accept(IOException("synthetic-disconnect"))
            } else {
                closing.timeout.run()
                assertThat(f.completions.tasks).isEmpty()
            }
            f.streams.open(7, f.expiry())
            f.writes.runAll()

            // then
            assertThat(closing.sends).hasValue(1)
            assertThat(closing.completed).hasValue(if (callback == "error") 0 else 1)
            assertFull { f.streams.open(7, f.expiry()) }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["timeout", "heartbeat"])
    fun `컨테이너가 이미 응답을 회수했을 때__연결을 완료하면__예외를 밖으로 던지지 않고 슬롯을 푼다`(
        path: String,
    ) {
        // given
        val heartbeat = mockk<ScheduledExecutorService>()

        every { heartbeat.scheduleAtFixedRate(any(), any(), any(), any()) } returns null
        every { heartbeat.shutdownNow() } returns mutableListOf()
        every { heartbeat.awaitTermination(any(), any()) } returns true
        Fixture(heartbeat).use { f ->
            f.streams.open(7, f.clock.instant().plusSeconds(1))
            f.streams.open(7, f.expiry())
            f.streams.open(7, f.expiry())
            val closing = f.emitters.first()
            closing.recycled = true

            // when
            val thrown = catchThrowable {
                if (path == "timeout") {
                    closing.timeout.run()
                } else {
                    f.clock.advance(2)
                    f.streams.heartbeat()
                    f.completions.runAll()
                }
            }

            // then
            assertThat(thrown).isNull()
            assertThatCode { f.streams.open(7, f.expiry()) }.doesNotThrowAnyException()
            assertFull { f.streams.open(7, f.expiry()) }
        }
    }

    @Test
    fun `소유한 연결이 있을 때__두 번 멈추면__서버 단계 전에 완료하고 소유한 실행기를 한 번만 종료한다`() {
        // given
        val heartbeat = mockk<ScheduledExecutorService>()

        every { heartbeat.scheduleAtFixedRate(any(), any(), any(), any()) } returns null
        every { heartbeat.shutdownNow() } returns mutableListOf()
        every { heartbeat.awaitTermination(any(), any()) } returns true
        val f = Fixture(heartbeat)
        f.streams.open(7, f.expiry())
        val callbacks = AtomicInteger()

        // when
        f.streams.stop(Runnable { callbacks.incrementAndGet() })
        f.streams.destroy()

        // then
        assertThat(f.streams.getPhase()).isEqualTo(Integer.MAX_VALUE)
        assertThat(f.streams.isRunning()).isFalse()
        assertThat(callbacks).hasValue(1)
        assertThat(f.emitters.first().completed).hasValue(1)
        assertThat(f.writes.shutdownNowCalls).isEqualTo(1)
        assertThat(f.completions.shutdownCalls).isEqualTo(1)
        verify(exactly = 1) { heartbeat.shutdownNow() }
        assertThatThrownBy { f.streams.open(7, f.expiry()) }
            .isInstanceOfSatisfying(ResponseStatusException::class.java) { failure ->
                assertThat(failure.statusCode.value()).isEqualTo(503)
            }
        assertThat(f.writes.awaitBudgets)
            .allSatisfy({ n -> assertThat(n).isBetween(0L, TimeUnit.SECONDS.toNanos(5)) })
        assertThat(f.completions.awaitBudgets)
            .allSatisfy({ n -> assertThat(n).isBetween(0L, TimeUnit.SECONDS.toNanos(5)) })
    }

    @Test
    fun `실행기가 종료되지 않을 때__멈추면__공유 예산 안에서 강제 종료하고 항상 생명주기에 알린다`() {
        // given
        val heartbeat = mockk<ScheduledExecutorService>()

        every { heartbeat.scheduleAtFixedRate(any(), any(), any(), any()) } returns null
        every { heartbeat.shutdownNow() } returns mutableListOf()
        every { heartbeat.awaitTermination(any(), any()) } returns true
        val f = Fixture(heartbeat)
        f.writes.terminates = false
        val callbacks = AtomicInteger()

        // when
        f.streams.stop(Runnable { callbacks.incrementAndGet() })

        // then
        assertThat(callbacks).hasValue(1)
        assertThat(f.writes.shutdownNowCalls).isEqualTo(2)
        assertThat(f.writes.awaitBudgets).hasSize(1)
            .allSatisfy({ n -> assertThat(n).isBetween(0L, TimeUnit.SECONDS.toNanos(5)) })
        assertThat(f.streams.isRunning()).isFalse()
        f.close()
    }

    private fun assertFull(action: () -> Any?) {
        assertFull(catchThrowable { action() })
    }

    private fun assertFull(thrown: Throwable?) {
        assertThat(thrown).isInstanceOfSatisfying(ResponseStatusException::class.java) { failure ->
            assertThat(failure.statusCode.value()).isEqualTo(429)
            assertThat(failure.headers.getFirst("Retry-After")).isEqualTo("15")
        }
    }

    private fun notification(now: Instant): NoteNotifications.Notification =
        NoteNotifications.Notification(
            UUID.fromString("f2900000-0000-0000-0000-000000000001"),
            "PRIVATE_NOTE",
            8L,
            " raw nickname ",
            now,
            false,
            "/notes/f2900000-0000-0000-0000-000000000001",
        )

    private class Fixture(heartbeat: ScheduledExecutorService) : AutoCloseable {
        val clock = MutableClock()
        val writes = ManualExecutor()
        val completions = ManualExecutor()
        val emitters = ArrayList<TrackingEmitter>()
        val streams: PrivateNoteStreamRegistry

        init {
            streams = PrivateNoteStreamRegistry(
                clock,
                LongFunction { timeout ->
                    val emitter = TrackingEmitter(timeout)
                    emitters.add(emitter)
                    emitter
                },
                writes,
                completions,
                heartbeat,
            )
        }

        fun expiry(): Instant = clock.instant().plusSeconds(60)

        override fun close() {
            streams.stop()
        }
    }

    private class MutableClock : Clock() {
        @Volatile
        private var now: Instant = Instant.parse("2031-01-01T00:00:00Z")

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now

        fun advance(seconds: Long) {
            now = now.plusSeconds(seconds)
        }
    }

    private class TrackingEmitter(timeout: Long) : PrivateNoteStreamRegistry.StreamEmitter(timeout) {
        val sends = AtomicInteger()
        val completed = AtomicInteger()
        val frames = ArrayList<List<Any?>>()
        lateinit var completion: Runnable
        lateinit var timeout: Runnable
        lateinit var error: Consumer<Throwable>
        var recycled = false

        @Throws(IOException::class)
        override fun send(event: SseEmitter.SseEventBuilder) {
            frames.add(event.build().map { part -> part.data })
            sends.incrementAndGet()
        }

        override fun onCompletion(callback: Runnable) {
            completion = callback
        }

        override fun onTimeout(callback: Runnable) {
            timeout = callback
        }

        override fun onError(callback: Consumer<Throwable>) {
            error = callback
        }

        override fun complete() {
            if (recycled) {
                throw IllegalStateException("The response object has been recycled")
            }
            completed.incrementAndGet()
            completion.run()
        }

        fun lock() {
            writeLock.lock()
        }

        fun unlock() {
            writeLock.unlock()
        }

        fun lockOwned(): Boolean = (writeLock as ReentrantLock).isHeldByCurrentThread

        fun writerWaiting(): Boolean = (writeLock as ReentrantLock).hasQueuedThreads()
    }

    private class ManualExecutor : AbstractExecutorService() {
        val tasks = ArrayList<Runnable>()
        val awaitBudgets = ArrayList<Long>()
        var capacity = 1000
        var shutdownNowCalls = 0
        var shutdownCalls = 0
        var stopped = false
        var rejectNext = false
        var terminates = true

        override fun execute(task: Runnable) {
            if (stopped || rejectNext || tasks.size >= capacity) {
                rejectNext = false
                throw RejectedExecutionException()
            }
            tasks.add(task)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) {
                tasks.removeAt(0).run()
            }
        }

        override fun shutdown() {
            stopped = true
            shutdownCalls++
        }

        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            shutdownNowCalls++
            val old = ArrayList(tasks)
            tasks.clear()
            return old
        }

        override fun isShutdown(): Boolean = stopped

        override fun isTerminated(): Boolean = stopped && tasks.isEmpty() && terminates

        override fun awaitTermination(value: Long, unit: TimeUnit): Boolean {
            awaitBudgets.add(unit.toNanos(value))
            if (terminates) {
                runAll()
            }
            return isTerminated
        }
    }
}
