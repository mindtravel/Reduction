package com.mindtravel.reduction.core

import java.util.concurrent.CancellationException

/**
 * A one-way signal that a reply is no longer wanted.
 *
 * A reply runs on a blocking HTTP call that coroutine cancellation cannot interrupt, so
 * the transport registers the call here and [cancel] cancels it directly. Tools that run
 * before the next round see [ensureActive] and stop instead of starting new work.
 */
class CancelSignal {
    private val lock = Any()
    private var cancelled = false
    private val hooks = ArrayList<() -> Unit>()

    val isCancelled: Boolean
        get() = synchronized(lock) { cancelled }

    /** Tells everyone waiting, once. Safe to call from any thread. */
    fun cancel() {
        val waiting = synchronized(lock) {
            if (cancelled) return
            cancelled = true
            hooks.toList().also { hooks.clear() }
        }
        for (hook in waiting) runCatching { hook() }
    }

    /** Registers [hook] to run on [cancel]; runs it at once if the signal has already fired. */
    fun onCancel(hook: () -> Unit) {
        synchronized(lock) {
            if (!cancelled) {
                hooks += hook
                return
            }
        }
        runCatching { hook() }
    }

    /** Throws [CancellationException] when the reply has been abandoned. */
    fun ensureActive() {
        if (isCancelled) throw CancellationException("回复已取消")
    }
}
