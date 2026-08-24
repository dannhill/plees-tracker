/*
 * Copyright 2026 Plees AutoSleep Authors
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker.autosleep

import android.content.Context
import android.content.ContextWrapper
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Empirical unit tests verifying UnlockEventSource interface contracts,
 * mock/fake implementations, AndroidUnlockEventSource edge cases, coroutine dispatching,
 * and error resilience.
 */
class AndroidUnlockEventSourceUnitTest {

    // =========================================================================
    // 1. Mock / Fake UnlockEventSource Behavioral Verification
    // =========================================================================

    private class FakeUnlockEventSource(
        private val timestamps: List<Long> = emptyList(),
        private val throwSecurityException: Boolean = false,
        private val throwRuntimeException: Boolean = false,
        private val delayMs: Long = 0L
    ) : UnlockEventSource {
        val invocationCount = AtomicInteger(0)

        override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long): List<Long> {
            invocationCount.incrementAndGet()
            if (delayMs > 0) {
                delay(delayMs)
            }
            if (throwSecurityException) {
                throw SecurityException("Mock permission denied for PACKAGE_USAGE_STATS")
            }
            if (throwRuntimeException) {
                throw IllegalStateException("Mock UsageStatsManager service unavailable")
            }
            return timestamps.filter { it in beginInclusive until endExclusive }
        }
    }

    @Test
    fun testMockUnlockEventSource_EmptyWindow() = runBlocking {
        val source = FakeUnlockEventSource(emptyList())
        val result = source.getUnlockTimestamps(1000L, 5000L)
        assertTrue("Empty source must return empty list", result.isEmpty())
        assertEquals(1, source.invocationCount.get())
    }

    @Test
    fun testMockUnlockEventSource_WindowFiltering() = runBlocking {
        val rawEvents = listOf(500L, 1000L, 2000L, 3000L, 4999L, 5000L, 6000L)
        val source = FakeUnlockEventSource(rawEvents)

        // Query interval [1000, 5000) -> should contain 1000, 2000, 3000, 4999 (5000 is exclusive)
        val result = source.getUnlockTimestamps(1000L, 5000L)
        assertEquals(listOf(1000L, 2000L, 3000L, 4999L), result)
    }

    @Test
    fun testMockUnlockEventSource_SecurityExceptionPropagation() = runBlocking {
        val source = FakeUnlockEventSource(throwSecurityException = true)
        try {
            source.getUnlockTimestamps(0L, 1000L)
            fail("Expected SecurityException to be thrown by mock source")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("permission denied") == true)
        }
    }

    @Test
    fun testMockUnlockEventSource_RuntimeExceptionPropagation() = runBlocking {
        val source = FakeUnlockEventSource(throwRuntimeException = true)
        try {
            source.getUnlockTimestamps(0L, 1000L)
            fail("Expected IllegalStateException to be thrown by mock source")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("unavailable") == true)
        }
    }

    // =========================================================================
    // 2. Coroutine Concurrency & Cancellation Stress Tests
    // =========================================================================

    @Test
    fun testMockUnlockEventSource_ConcurrentQueries() = runBlocking {
        val events = (1000L..100000L step 1000L).toList()
        val source = FakeUnlockEventSource(events)
        val numCallers = 50

        val deferreds = (1..numCallers).map { i ->
            async(Dispatchers.Default) {
                val start = (i * 1000).toLong()
                val stop = start + 5000L
                val result = source.getUnlockTimestamps(start, stop)
                assertEquals(5, result.size)
            }
        }

        deferreds.awaitAll()
        assertEquals(numCallers, source.invocationCount.get())
    }

    @Test
    fun testMockUnlockEventSource_CancellationResponsiveness() = runBlocking {
        val source = FakeUnlockEventSource(delayMs = 5000L)

        val job = launch(Dispatchers.Default) {
            source.getUnlockTimestamps(0L, 10000L)
        }

        delay(50)
        job.cancel()
        job.join()

        assertTrue("Coroutine must be responsive to cancellation", job.isCancelled)
    }

    // =========================================================================
    // 3. AndroidUnlockEventSource Unit Verification & Invariants
    // =========================================================================

    private class StubContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? = null
    }

    @Test
    fun testAndroidUnlockEventSource_NullOrOlderSdkReturnsEmpty() = runBlocking {
        val context = StubContext()
        val source = AndroidUnlockEventSource(context)

        // On JVM host unit tests, Build.VERSION.SDK_INT is 0 (< 28), ensuring pre-API 28 returns empty list
        val result = source.getUnlockTimestamps(1000L, 5000L)
        assertNotNull(result)
        assertTrue("Pre-API 28 or missing service must safely return empty list", result.isEmpty())
    }

    @Test
    fun testAndroidUnlockEventSource_InvalidInterval_BeginGreaterOrEqualEnd() = runBlocking {
        val context = StubContext()
        val source = AndroidUnlockEventSource(context)

        // begin >= end must return empty list immediately
        val equalResult = source.getUnlockTimestamps(5000L, 5000L)
        assertTrue("begin == end must return empty list", equalResult.isEmpty())

        val invertedResult = source.getUnlockTimestamps(8000L, 5000L)
        assertTrue("begin > end must return empty list", invertedResult.isEmpty())
    }

    @Test
    fun testAndroidUnlockEventSource_ZeroAndNegativeBounds() = runBlocking {
        val context = StubContext()
        val source = AndroidUnlockEventSource(context)

        val negativeResult = source.getUnlockTimestamps(-1000L, -500L)
        assertNotNull(negativeResult)

        val zeroResult = source.getUnlockTimestamps(0L, 0L)
        assertTrue(zeroResult.isEmpty())
    }

    @Test
    fun testUnlockEventSourceInterfaceContract_Subtyping() {
        val source: UnlockEventSource = FakeUnlockEventSource(listOf(1000L, 2000L))
        assertNotNull("UnlockEventSource must support polymorphic assignment", source)
    }
}
