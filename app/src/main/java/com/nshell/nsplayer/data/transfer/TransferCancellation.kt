package com.nshell.nsplayer.data.transfer

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** Owns cancellation and the worker's current user-response wait, never a screen. */
internal class TransferCancellation {
    private val cancelled = AtomicBoolean(false)
    private val lock = Any()
    private var pendingReply: TransferReply<*>? = null

    val isCancelled: Boolean
        get() = cancelled.get()

    fun cancel() {
        synchronized(lock) {
            cancelled.set(true)
            pendingReply?.cancel()
        }
    }

    fun <T> awaitReply(publish: (TransferReply<T>) -> Unit): T? {
        val reply = TransferReply<T>()
        synchronized(lock) {
            if (isCancelled) return null
            check(pendingReply == null) { "Only one transfer response can be pending" }
            pendingReply = reply
        }
        return try {
            if (!isCancelled) publish(reply)
            val value = reply.await()
            if (isCancelled) null else value
        } catch (_: InterruptedException) {
            cancel()
            Thread.currentThread().interrupt()
            null
        } finally {
            synchronized(lock) {
                if (pendingReply === reply) pendingReply = null
            }
        }
    }
}

internal class TransferReply<T> {
    private val response = CompletableFuture<T?>()

    fun complete(value: T): Boolean = response.complete(value)

    internal fun cancel() {
        response.complete(null)
    }

    internal fun await(): T? = response.get()
}
