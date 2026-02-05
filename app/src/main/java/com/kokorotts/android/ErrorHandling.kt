package com.kokorotts.android

import android.util.Log

/**
 * Sealed class representing TTS operation results.
 * Provides type-safe error handling.
 */
sealed class TTSResult<out T> {
    data class Success<T>(val data: T) : TTSResult<T>()
    data class Error<T>(
        val exception: Throwable,
        val code: ErrorCode,
        val recoverable: Boolean = false
    ) : TTSResult<T>()
    
    /**
     * Get success value or null.
     */
    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Error -> null
    }
    
    /**
     * Get success value or throw exception.
     */
    fun getOrThrow(): T = when (this) {
        is Success -> data
        is Error -> throw exception
    }
    
    /**
     * Get success value or return default.
     */
    fun getOrDefault(default: T): T = when (this) {
        is Success -> data
        is Error -> default
    }
    
    /**
     * Map success value.
     */
    inline fun <R> map(transform: (T) -> R): TTSResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> Error(exception, code, recoverable)
    }
    
    /**
     * Execute action on success.
     */
    inline fun onSuccess(action: (T) -> Unit): TTSResult<T> {
        if (this is Success) action(data)
        return this
    }
    
    /**
     * Execute action on error.
     */
    inline fun onError(action: (Throwable, ErrorCode) -> Unit): TTSResult<T> {
        if (this is Error) action(exception, code)
        return this
    }
    
    /**
     * Check if result is success.
     */
    val isSuccess: Boolean get() = this is Success
    
    /**
     * Check if result is error.
     */
    val isError: Boolean get() = this is Error
}

/**
 * Error codes for TTS operations.
 */
enum class ErrorCode {
    // Initialization errors
    ENGINE_NOT_INITIALIZED,
    MODEL_NOT_FOUND,
    MODEL_LOAD_FAILED,
    
    // Input errors
    INVALID_TEXT,
    INVALID_VOICE,
    VOICE_NOT_FOUND,
    
    // Processing errors
    PHONEMIZATION_FAILED,
    TOKENIZATION_FAILED,
    INFERENCE_FAILED,
    AUDIO_GENERATION_FAILED,
    
    // Resource errors
    OUT_OF_MEMORY,
    FILE_NOT_FOUND,
    FILE_READ_ERROR,
    
    // Runtime errors
    TIMEOUT,
    CANCELLED,
    UNKNOWN
}

/**
 * Custom exceptions for TTS errors.
 */
open class TTSException(
    message: String,
    val code: ErrorCode,
    cause: Throwable? = null
) : Exception(message, cause)

class EngineNotInitializedException(
    message: String = "TTS engine not initialized"
) : TTSException(message, ErrorCode.ENGINE_NOT_INITIALIZED)

class ModelNotFoundException(
    path: String
) : TTSException("Model not found: $path", ErrorCode.MODEL_NOT_FOUND)

class VoiceNotFoundException(
    voiceId: String
) : TTSException("Voice not found: $voiceId", ErrorCode.VOICE_NOT_FOUND)

class PhonemizationException(
    message: String,
    cause: Throwable? = null
) : TTSException(message, ErrorCode.PHONEMIZATION_FAILED, cause)

class InferenceException(
    message: String,
    cause: Throwable? = null
) : TTSException(message, ErrorCode.INFERENCE_FAILED, cause)

class AudioGenerationException(
    message: String,
    cause: Throwable? = null
) : TTSException(message, ErrorCode.AUDIO_GENERATION_FAILED, cause)

/**
 * Global error handler for the TTS service.
 */
object ErrorHandler {
    
    private const val TAG = "ErrorHandler"
    
    /**
     * Handle an error with appropriate logging and recovery.
     * 
     * @param error The error that occurred
     * @param context Context description for logging
     * @param recoverable Whether the error is recoverable
     * @return Error result with appropriate code
     */
    fun handle(
        error: Throwable,
        context: String,
        recoverable: Boolean = false
    ): TTSResult.Error<Nothing> {
        val code = when (error) {
            is TTSException -> error.code
            is OutOfMemoryError -> ErrorCode.OUT_OF_MEMORY
            is InterruptedException -> ErrorCode.CANCELLED
            else -> ErrorCode.UNKNOWN
        }
        
        // Log error with appropriate level
        when (code) {
            ErrorCode.CANCELLED -> Log.d(TAG, "$context cancelled")
            ErrorCode.OUT_OF_MEMORY -> Log.e(TAG, "$context: Out of memory!", error)
            ErrorCode.UNKNOWN -> Log.e(TAG, "$context: Unexpected error", error)
            else -> Log.w(TAG, "$context: ${error.message}", error)
        }
        
        return TTSResult.Error(error, code, recoverable)
    }
    
    /**
     * Wrap a suspend function with error handling.
     * 
     * @param context Context for error messages
     * @param recoverable Whether errors from this operation are recoverable
     * @param block The operation to execute
     * @return Result of the operation
     */
    suspend inline fun <T> wrap(
        context: String,
        recoverable: Boolean = false,
        block: () -> T
    ): TTSResult<T> {
        return try {
            TTSResult.Success(block())
        } catch (e: Exception) {
            handle(e, context, recoverable)
        }
    }
    
    /**
     * Safely execute a block with automatic cleanup on error.
     * 
     * @param cleanup Cleanup action to execute on error
     * @param block The operation to execute
     * @return Result of the operation
     */
    inline fun <T> withCleanup(
        cleanup: () -> Unit,
        block: () -> T
    ): TTSResult<T> {
        return try {
            TTSResult.Success(block())
        } catch (e: Exception) {
            try {
                cleanup()
            } catch (cleanupError: Exception) {
                Log.w(TAG, "Cleanup failed", cleanupError)
            }
            TTSResult.Error(e, ErrorCode.UNKNOWN, false)
        }
    }
}

/**
 * Memory-aware resource manager for TTS operations.
 */
object MemoryManager {
    
    private const val TAG = "MemoryManager"
    
    // Memory thresholds (in MB)
    private const val LOW_MEMORY_THRESHOLD = 100
    private const val CRITICAL_MEMORY_THRESHOLD = 50
    
    private val runtime = Runtime.getRuntime()
    
    /**
     * Memory status of the system.
     */
    data class MemoryStatus(
        val totalMB: Long,
        val usedMB: Long,
        val freeMB: Long,
        val percentUsed: Int
    ) {
        val isLow: Boolean get() = freeMB < LOW_MEMORY_THRESHOLD
        val isCritical: Boolean get() = freeMB < CRITICAL_MEMORY_THRESHOLD
    }
    
    /**
     * Get current memory status.
     */
    fun getMemoryStatus(): MemoryStatus {
        val maxMemory = runtime.maxMemory() / 1024 / 1024
        val totalMemory = runtime.totalMemory() / 1024 / 1024
        val freeMemory = runtime.freeMemory() / 1024 / 1024
        val usedMemory = totalMemory - freeMemory
        val percentUsed = ((usedMemory.toFloat() / maxMemory) * 100).toInt()
        
        return MemoryStatus(
            totalMB = maxMemory,
            usedMB = usedMemory,
            freeMB = maxMemory - usedMemory,
            percentUsed = percentUsed
        )
    }
    
    /**
     * Check if there's enough memory for an operation.
     * 
     * @param requiredMB Memory required in MB
     * @return True if sufficient memory is available
     */
    fun hasEnoughMemory(requiredMB: Long): Boolean {
        val status = getMemoryStatus()
        return status.freeMB >= requiredMB
    }
    
    /**
     * Attempt to free memory by requesting garbage collection.
     * Note: This is a hint to the JVM, not a guarantee.
     */
    fun requestGarbageCollection() {
        Log.d(TAG, "Requesting garbage collection")
        System.gc()
    }
    
    /**
     * Log current memory status.
     */
    fun logMemoryStatus() {
        val status = getMemoryStatus()
        val level = when {
            status.isCritical -> android.util.Log.ERROR
            status.isLow -> android.util.Log.WARN
            else -> android.util.Log.DEBUG
        }
        
        Log.println(level, TAG, 
            "Memory: ${status.usedMB}/${status.totalMB}MB (${status.percentUsed}%), " +
            "Free: ${status.freeMB}MB"
        )
    }
    
    /**
     * Execute operation with memory check.
     * 
     * @param requiredMB Memory required
     * @param block Operation to execute
     * @return Result or null if insufficient memory
     */
    inline fun <T> withMemoryCheck(
        requiredMB: Long,
        block: () -> T
    ): T? {
        return if (hasEnoughMemory(requiredMB)) {
            block()
        } else {
            Log.w(TAG, "Insufficient memory: need ${requiredMB}MB")
            null
        }
    }
}

/**
 * Retry utility for transient failures.
 */
object RetryUtil {
    
    private const val TAG = "RetryUtil"
    
    /**
     * Retry configuration.
     */
    data class RetryConfig(
        val maxAttempts: Int = 3,
        val initialDelayMs: Long = 1000,
        val maxDelayMs: Long = 10000,
        val backoffMultiplier: Double = 2.0,
        val retryableExceptions: List<Class<out Throwable>> = listOf(
            java.io.IOException::class.java,
            java.net.SocketTimeoutException::class.java
        )
    )
    
    /**
     * Execute a block with retry logic.
     * 
     * @param config Retry configuration
     * @param operationName Name of the operation for logging
     * @param block Operation to execute
     * @return Result of the operation
     */
    suspend fun <T> withRetry(
        config: RetryConfig = RetryConfig(),
        operationName: String,
        block: suspend () -> T
    ): TTSResult<T> {
        var lastException: Throwable? = null
        var delay = config.initialDelayMs
        
        for (attempt in 1..config.maxAttempts) {
            try {
                val result = block()
                
                if (attempt > 1) {
                    Log.i(TAG, "$operationName succeeded on attempt $attempt")
                }
                
                return TTSResult.Success(result)
            } catch (e: Exception) {
                lastException = e
                
                // Check if exception is retryable
                val isRetryable = config.retryableExceptions.any { it.isInstance(e) }
                
                if (!isRetryable || attempt == config.maxAttempts) {
                    Log.e(TAG, "$operationName failed on attempt $attempt", e)
                    break
                }
                
                Log.w(TAG, "$operationName attempt $attempt failed, retrying in ${delay}ms", e)
                kotlinx.coroutines.delay(delay)
                
                delay = (delay * config.backoffMultiplier).toLong()
                    .coerceAtMost(config.maxDelayMs)
            }
        }
        
        return TTSResult.Error(
            lastException ?: Exception("Max retries exceeded"),
            ErrorCode.UNKNOWN,
            false
        )
    }
}
