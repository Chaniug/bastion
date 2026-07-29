package com.bastion.app.logging

import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Unit tests for [runCatchingObserved] / [logSwallowed] / [setSwallowedExceptionSink].
 *
 * These run on the JVM (`testDebugUnitTest`), where `BuildConfig.DEBUG == true`, so swallowed
 * exceptions actually flow to the configured sink. We install an in-memory sink via
 * [setSwallowedExceptionSink] to avoid touching `android.util.Log` and to keep assertions hermetic.
 *
 * Both the rate-limit counters and the active sink are module-level mutable state, so every test
 * uses a unique (UUID) tag and resets the sink in [setUp] to stay isolated from other tests.
 */
class SwallowedExceptionLoggerTest {

    /** Captured sink invocations for the currently-installed sink. */
    private lateinit var records: MutableList<SinkCall>

    @Before
    fun setUp() {
        records = mutableListOf()
        setSwallowedExceptionSink { tag, priority, throwable ->
            records.add(SinkCall(tag, priority, throwable))
        }
    }

    @Test
    fun runCatchingObserved_forwardsThrownExceptionToSink() {
        val tag = uniqueTag()
        val boom = RuntimeException("boom")
        val result = runCatchingObserved(tag = tag) { throw boom }

        // Semantics preserved: the returned Result is unchanged.
        assertTrue(result.isFailure)
        assertSame(boom, result.exceptionOrNull())

        // The swallowed exception is observed exactly once, with the right tag / priority / throwable.
        assertEquals(1, records.size)
        val call = records[0]
        assertEquals(tag, call.tag)
        assertEquals(Log.WARN, call.priority)
        assertSame(boom, call.throwable)
    }

    @Test
    fun runCatchingObserved_doesNotForwardOnSuccess() {
        val tag = uniqueTag()
        val result = runCatchingObserved(tag = tag) { "ok" }

        assertTrue(result.isSuccess)
        assertEquals("ok", result.getOrNull())
        assertTrue("no exception should be reported on the success path", records.isEmpty())
    }

    @Test
    fun setSwallowedExceptionSink_replacesTheActiveSink() {
        // Install a second sink on top of the @Before sink.
        val replacement = mutableListOf<SinkCall>()
        setSwallowedExceptionSink { tag, priority, throwable ->
            replacement.add(SinkCall(tag, priority, throwable))
        }

        logSwallowed(uniqueTag(), Log.WARN, RuntimeException("replaced"))

        // The new sink captured the call...
        assertEquals(1, replacement.size)
        // ...and the previously installed @Before sink received nothing (it was replaced).
        assertTrue(records.isEmpty())
    }

    @Test
    fun logSwallowed_isRateLimitedPerTag() {
        val tag = uniqueTag()
        // Exceed RATE_MAX (50) in a tight loop; the sink must be throttled to exactly RATE_MAX.
        repeat(120) {
            runCatchingObserved(tag = tag) { throw RuntimeException("swallowed #$it") }
        }
        assertEquals(50, records.size)
    }

    private data class SinkCall(
        val tag: String,
        val priority: Int,
        val throwable: Throwable?,
    )

    private fun uniqueTag(): String = "SwallowedExceptionLoggerTest_${UUID.randomUUID()}"
}
