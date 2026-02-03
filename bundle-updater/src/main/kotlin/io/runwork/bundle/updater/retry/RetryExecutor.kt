package io.runwork.bundle.updater.retry

import io.runwork.bundle.updater.BundleUpdateEvent
import io.runwork.bundle.updater.RetryConfig
import io.runwork.bundle.updater.UpdateError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.time.Clock
import java.time.Instant
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val httpCodePattern = Regex("""HTTP (\d{3})""")

/**
 * Returns true if the error is an IOException or an HTTP 5xx/429 error.
 */
fun isRecoverableError(error: Throwable): Boolean {
    return when {
        error is IOException -> true
        isRecoverableHttpCode(error.message) -> true
        error.cause != null -> isRecoverableError(error.cause!!)
        else -> false
    }
}

private fun isRecoverableHttpCode(message: String?): Boolean {
    if (message == null) return false
    val match = httpCodePattern.find(message) ?: return false
    val code = match.groupValues[1].toIntOrNull() ?: return false
    return code == 429 || code in 500..599
}

/**
 * Executes operations with retry logic and exponential backoff.
 *
 * @param config Configuration for retry behavior
 * @param clock Clock for determining timestamps (injectable for testing)
 * @param delayFunction Function to call for delays (injectable for testing with virtual time)
 */
class RetryExecutor(
    private val config: RetryConfig,
    private val clock: Clock = Clock.systemUTC(),
    private val delayFunction: suspend (Duration) -> Unit = { delay(it) },
) {
    /**
     * Execute an operation with retry support.
     *
     * @param emit Callback to emit BackingOff events before each retry delay
     * @param isRecoverable Function to determine if an error should trigger a retry
     * @param operation The operation to execute
     * @return Result containing either the successful result or the final error
     */
    suspend fun <T> executeWithRetry(
        emit: suspend (BundleUpdateEvent.BackingOff) -> Unit,
        isRecoverable: (Throwable) -> Boolean = ::isRecoverableError,
        operation: suspend () -> T,
    ): Result<T> {
        var lastError: Throwable? = null
        var attempt = 0

        while (attempt <= config.maxAttempts) {
            // Check if coroutine was cancelled before attempting
            currentCoroutineContext().ensureActive()

            try {
                return Result.success(operation())
            } catch (e: CancellationException) {
                // Don't retry on cancellation - rethrow immediately
                throw e
            } catch (e: Throwable) {
                lastError = e

                // Check if this error is recoverable
                if (!isRecoverable(e)) {
                    return Result.failure(e)
                }

                // Check if we've exhausted retries
                if (attempt >= config.maxAttempts) {
                    return Result.failure(e)
                }

                // Calculate delay with exponential backoff
                val delayDuration = calculateDelay(attempt)
                val nextRetryTime = Instant.now(clock).plusMillis(delayDuration.inWholeMilliseconds)

                // Emit backoff event
                val backoffEvent = BundleUpdateEvent.BackingOff(
                    retryNumber = attempt + 1,
                    delaySeconds = delayDuration.inWholeSeconds,
                    nextRetryTime = nextRetryTime,
                    error = UpdateError(
                        message = e.message ?: "Unknown error",
                        cause = e,
                        isRecoverable = true,
                    ),
                )
                emit(backoffEvent)

                // Wait before retrying (delay is cancellable)
                delayFunction(delayDuration)
                attempt++
            }
        }

        // Should not reach here, but just in case
        return Result.failure(lastError ?: IllegalStateException("Retry exhausted without error"))
    }

    /**
     * Calculate delay for the given attempt number using exponential backoff.
     *
     * delay = min(initialDelay * multiplier^attempt, maxDelay)
     */
    private fun calculateDelay(attempt: Int): Duration {
        val multiplied = config.initialDelay.inWholeMilliseconds * config.multiplier.pow(attempt)
        val capped = min(multiplied, config.maxDelay.inWholeMilliseconds.toDouble())
        return capped.toLong().milliseconds
    }
}
