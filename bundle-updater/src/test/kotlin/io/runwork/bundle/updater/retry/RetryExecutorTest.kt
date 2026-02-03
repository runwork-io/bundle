package io.runwork.bundle.updater.retry

import io.runwork.bundle.updater.BundleUpdateEvent
import io.runwork.bundle.updater.RetryConfig
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RetryExecutorTest {

    private val fixedClock = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneId.of("UTC"))

    @Test
    fun executeWithRetry_succeedsOnFirstAttempt_noBackoffEvents() = runTest {
        val config = RetryConfig(
            initialDelay = 1.seconds,
            maxDelay = 60.seconds,
            multiplier = 2.0,
            maxAttempts = 3,
        )
        val backoffEvents = mutableListOf<BundleUpdateEvent.BackingOff>()
        val executor = RetryExecutor(config, fixedClock, delayFunction = { /* no-op */ })

        val result = executor.executeWithRetry(
            emit = { backoffEvents.add(it) },
            operation = { "success" }
        )

        assertTrue(result.isSuccess)
        assertEquals("success", result.getOrNull())
        assertTrue(backoffEvents.isEmpty())
    }

    @Test
    fun executeWithRetry_retriesOnRecoverableError_succeedsOnSecondAttempt() = runTest {
        val config = RetryConfig(
            initialDelay = 1.seconds,
            maxDelay = 60.seconds,
            multiplier = 2.0,
            maxAttempts = 3,
        )
        val backoffEvents = mutableListOf<BundleUpdateEvent.BackingOff>()
        var attemptCount = 0
        val executor = RetryExecutor(config, fixedClock, delayFunction = { /* no-op */ })

        val result = executor.executeWithRetry(
            emit = { backoffEvents.add(it) },
            operation = {
                attemptCount++
                if (attemptCount == 1) {
                    throw SocketTimeoutException("Timeout on first attempt")
                }
                "success"
            }
        )

        assertTrue(result.isSuccess)
        assertEquals("success", result.getOrNull())
        assertEquals(1, backoffEvents.size)
        assertEquals(1, backoffEvents[0].retryNumber)
        assertEquals(1L, backoffEvents[0].delaySeconds)
    }

    @Test
    fun executeWithRetry_stopsImmediatelyOnNonRecoverableError() = runTest {
        val config = RetryConfig(
            initialDelay = 1.seconds,
            maxDelay = 60.seconds,
            multiplier = 2.0,
            maxAttempts = 3,
        )
        val backoffEvents = mutableListOf<BundleUpdateEvent.BackingOff>()
        var attemptCount = 0
        val executor = RetryExecutor(config, fixedClock, delayFunction = { /* no-op */ })

        val nonRecoverableError = IllegalArgumentException("Invalid data")
        val result = executor.executeWithRetry(
            emit = { backoffEvents.add(it) },
            isRecoverable = { false }, // All errors non-recoverable
            operation = {
                attemptCount++
                throw nonRecoverableError
            }
        )

        assertTrue(result.isFailure)
        assertEquals(nonRecoverableError, result.exceptionOrNull())
        assertEquals(1, attemptCount) // Only one attempt
        assertTrue(backoffEvents.isEmpty()) // No backoff events for non-recoverable
    }

    @Test
    fun executeWithRetry_exhaustsRetries_returnsFailure() = runTest {
        val config = RetryConfig(
            initialDelay = 1.seconds,
            maxDelay = 60.seconds,
            multiplier = 2.0,
            maxAttempts = 3,
        )
        val backoffEvents = mutableListOf<BundleUpdateEvent.BackingOff>()
        var attemptCount = 0
        val executor = RetryExecutor(config, fixedClock, delayFunction = { /* no-op */ })

        val result = executor.executeWithRetry(
            emit = { backoffEvents.add(it) },
            operation = {
                attemptCount++
                throw SocketTimeoutException("Always fails")
            }
        )

        assertTrue(result.isFailure)
        assertIs<SocketTimeoutException>(result.exceptionOrNull())
        assertEquals(4, attemptCount) // Initial + 3 retries
        assertEquals(3, backoffEvents.size)
    }

    @Test
    fun executeWithRetry_exponentialDelayIncreases() = runTest {
        val config = RetryConfig(
            initialDelay = 1.seconds,
            maxDelay = 60.seconds,
            multiplier = 2.0,
            maxAttempts = 5,
        )
        val backoffEvents = mutableListOf<BundleUpdateEvent.BackingOff>()
        val executor = RetryExecutor(config, fixedClock, delayFunction = { /* no-op */ })

        executor.executeWithRetry(
            emit = { backoffEvents.add(it) },
            operation = { throw SocketTimeoutException("Always fails") }
        )

        assertEquals(5, backoffEvents.size)
        // Delays should be: 1s, 2s, 4s, 8s, 16s
        assertEquals(1L, backoffEvents[0].delaySeconds)
        assertEquals(2L, backoffEvents[1].delaySeconds)
        assertEquals(4L, backoffEvents[2].delaySeconds)
        assertEquals(8L, backoffEvents[3].delaySeconds)
        assertEquals(16L, backoffEvents[4].delaySeconds)
    }

    @Test
    fun executeWithRetry_respectsMaxDelayCap() = runTest {
        val config = RetryConfig(
            initialDelay = 10.seconds,
            maxDelay = 20.seconds, // Cap at 20 seconds
            multiplier = 3.0,
            maxAttempts = 4,
        )
        val backoffEvents = mutableListOf<BundleUpdateEvent.BackingOff>()
        val executor = RetryExecutor(config, fixedClock, delayFunction = { /* no-op */ })

        executor.executeWithRetry(
            emit = { backoffEvents.add(it) },
            operation = { throw SocketTimeoutException("Always fails") }
        )

        assertEquals(4, backoffEvents.size)
        // Delays would be: 10s, 30s->20s (capped), 90s->20s (capped), 270s->20s (capped)
        assertEquals(10L, backoffEvents[0].delaySeconds)
        assertEquals(20L, backoffEvents[1].delaySeconds) // Capped
        assertEquals(20L, backoffEvents[2].delaySeconds) // Capped
        assertEquals(20L, backoffEvents[3].delaySeconds) // Capped
    }

    @Test
    fun executeWithRetry_delayFunctionCalledWithCorrectDurations() = runTest {
        val config = RetryConfig(
            initialDelay = 1.seconds,
            maxDelay = 60.seconds,
            multiplier = 2.0,
            maxAttempts = 3,
        )
        val recordedDelays = mutableListOf<Duration>()
        val executor = RetryExecutor(config, fixedClock, delayFunction = { delay ->
            recordedDelays.add(delay)
        })

        executor.executeWithRetry(
            emit = { /* no-op */ },
            operation = { throw SocketTimeoutException("Always fails") }
        )

        assertEquals(3, recordedDelays.size)
        assertEquals(1000.milliseconds, recordedDelays[0])
        assertEquals(2000.milliseconds, recordedDelays[1])
        assertEquals(4000.milliseconds, recordedDelays[2])
    }

    @Test
    fun executeWithRetry_backoffEventContainsCorrectRetryNumber() = runTest {
        val config = RetryConfig(
            initialDelay = 1.seconds,
            maxDelay = 60.seconds,
            multiplier = 2.0,
            maxAttempts = 3,
        )
        val backoffEvents = mutableListOf<BundleUpdateEvent.BackingOff>()
        val executor = RetryExecutor(config, fixedClock, delayFunction = { /* no-op */ })

        executor.executeWithRetry(
            emit = { backoffEvents.add(it) },
            operation = { throw SocketTimeoutException("Always fails") }
        )

        assertEquals(3, backoffEvents.size)
        assertEquals(1, backoffEvents[0].retryNumber)
        assertEquals(2, backoffEvents[1].retryNumber)
        assertEquals(3, backoffEvents[2].retryNumber)
    }

    @Test
    fun executeWithRetry_backoffEventContainsNextRetryTime() = runTest {
        val config = RetryConfig(
            initialDelay = 5.seconds,
            maxDelay = 60.seconds,
            multiplier = 2.0,
            maxAttempts = 1,
        )
        val backoffEvents = mutableListOf<BundleUpdateEvent.BackingOff>()
        val executor = RetryExecutor(config, fixedClock, delayFunction = { /* no-op */ })

        executor.executeWithRetry(
            emit = { backoffEvents.add(it) },
            operation = { throw SocketTimeoutException("Always fails") }
        )

        assertEquals(1, backoffEvents.size)
        val expectedNextRetryTime = Instant.parse("2025-01-01T00:00:05Z") // 5 seconds after fixed clock
        assertEquals(expectedNextRetryTime, backoffEvents[0].nextRetryTime)
    }

    @Test
    fun executeWithRetry_backoffEventContainsErrorInfo() = runTest {
        val config = RetryConfig(
            initialDelay = 1.seconds,
            maxDelay = 60.seconds,
            multiplier = 2.0,
            maxAttempts = 1,
        )
        val backoffEvents = mutableListOf<BundleUpdateEvent.BackingOff>()
        val executor = RetryExecutor(config, fixedClock, delayFunction = { /* no-op */ })
        val testError = SocketTimeoutException("Test timeout error")

        executor.executeWithRetry(
            emit = { backoffEvents.add(it) },
            operation = { throw testError }
        )

        assertEquals(1, backoffEvents.size)
        assertEquals("Test timeout error", backoffEvents[0].error.message)
        assertEquals(testError, backoffEvents[0].error.cause)
        assertTrue(backoffEvents[0].error.isRecoverable)
    }

    @Test
    fun executeWithRetry_customIsRecoverableFunction() = runTest {
        val config = RetryConfig(
            initialDelay = 1.seconds,
            maxDelay = 60.seconds,
            multiplier = 2.0,
            maxAttempts = 3,
        )
        val backoffEvents = mutableListOf<BundleUpdateEvent.BackingOff>()
        var attemptCount = 0
        val executor = RetryExecutor(config, fixedClock, delayFunction = { /* no-op */ })

        // Custom: only IOException is recoverable
        val result = executor.executeWithRetry(
            emit = { backoffEvents.add(it) },
            isRecoverable = { it is IOException },
            operation = {
                attemptCount++
                if (attemptCount < 3) {
                    throw IOException("Recoverable")
                }
                "success"
            }
        )

        assertTrue(result.isSuccess)
        assertEquals(2, backoffEvents.size) // 2 retries before success
    }

    @Test
    fun executeWithRetry_zeroMaxAttempts_noRetries() = runTest {
        val config = RetryConfig(
            initialDelay = 1.seconds,
            maxDelay = 60.seconds,
            multiplier = 2.0,
            maxAttempts = 0,
        )
        val backoffEvents = mutableListOf<BundleUpdateEvent.BackingOff>()
        var attemptCount = 0
        val executor = RetryExecutor(config, fixedClock, delayFunction = { /* no-op */ })

        val result = executor.executeWithRetry(
            emit = { backoffEvents.add(it) },
            operation = {
                attemptCount++
                throw SocketTimeoutException("Fail")
            }
        )

        assertTrue(result.isFailure)
        assertEquals(1, attemptCount) // Only initial attempt
        assertTrue(backoffEvents.isEmpty())
    }

    @Test
    fun executeWithRetry_succeedsOnLastRetry() = runTest {
        val config = RetryConfig(
            initialDelay = 1.seconds,
            maxDelay = 60.seconds,
            multiplier = 2.0,
            maxAttempts = 3,
        )
        val backoffEvents = mutableListOf<BundleUpdateEvent.BackingOff>()
        var attemptCount = 0
        val executor = RetryExecutor(config, fixedClock, delayFunction = { /* no-op */ })

        val result = executor.executeWithRetry(
            emit = { backoffEvents.add(it) },
            operation = {
                attemptCount++
                if (attemptCount <= 3) { // Fail first 3, succeed on 4th (last retry)
                    throw SocketTimeoutException("Fail attempt $attemptCount")
                }
                "success on last retry"
            }
        )

        assertTrue(result.isSuccess)
        assertEquals("success on last retry", result.getOrNull())
        assertEquals(4, attemptCount)
        assertEquals(3, backoffEvents.size)
    }
}
