package com.mindtravel.reduction.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CancelSignalTest {

    @Test
    fun `cancel runs each hook once`() {
        val signal = CancelSignal()
        var runs = 0
        signal.onCancel { runs++ }
        signal.onCancel { runs++ }

        signal.cancel()
        signal.cancel()

        assertEquals(2, runs)
        assertTrue(signal.isCancelled)
    }

    @Test
    fun `a hook added after the signal fired runs at once`() {
        val signal = CancelSignal().apply { cancel() }
        var ran = false

        signal.onCancel { ran = true }

        assertTrue(ran)
    }

    @Test
    fun `ensureActive stays quiet until cancelled and then throws`() {
        val signal = CancelSignal()
        signal.ensureActive()
        assertFalse(signal.isCancelled)

        signal.cancel()

        try {
            signal.ensureActive()
            throw AssertionError("expected CancellationException")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun `a waiting hook is woken by cancel`() {
        val signal = CancelSignal()
        val woken = CountDownLatch(1)
        signal.onCancel { woken.countDown() }

        signal.cancel()

        assertTrue(woken.await(1, TimeUnit.SECONDS))
    }
}
