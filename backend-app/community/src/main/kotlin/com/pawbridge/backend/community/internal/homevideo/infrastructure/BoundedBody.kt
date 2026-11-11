package com.pawbridge.backend.community.internal.homevideo.infrastructure

import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow

/** Bound both buffered bytes and the full-response deadline, including a stalled body. */
internal class BoundedBody(private val maxBytes: Long) : HttpResponse.BodySubscriber<ByteArray> {
    private val delegate: HttpResponse.BodySubscriber<ByteArray> = HttpResponse.BodySubscribers.ofByteArray()
    private lateinit var subscription: Flow.Subscription
    private var received = 0L

    override fun getBody(): CompletionStage<ByteArray> = delegate.body

    override fun onSubscribe(value: Flow.Subscription) {
        subscription = value
        delegate.onSubscribe(value)
    }

    override fun onNext(buffers: MutableList<ByteBuffer>) {
        for (buffer in buffers) {
            received += buffer.remaining()
        }
        if (received > maxBytes) {
            subscription.cancel()
            delegate.onError(IllegalStateException("Provider response limit exceeded"))
        } else {
            delegate.onNext(buffers)
        }
    }

    override fun onError(failure: Throwable) {
        delegate.onError(failure)
    }

    override fun onComplete() {
        delegate.onComplete()
    }
}
