package com.kokorotts.android

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manager for structured concurrency in the TTS service.
 * 
 * Provides:
 * - Lifecycle-aware coroutine scopes
 * - Automatic cleanup of resources
 * - Structured concurrency patterns
 * - Cancellation handling
 */
class CoroutineManager : Closeable {
    
    companion object {
        private const val TAG = "CoroutineManager"
    }
    
    /**
     * Main service scope with supervisor job
     * Child failures don't cancel siblings
     */
    private val serviceScope = CoroutineScope(
        Dispatchers.Default + SupervisorJob()
    )
    
    /**
     * IO scope for blocking operations
     */
    private val ioScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob()
    )
    
    /**
     * Track active jobs for debugging and cleanup
     */
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val jobCounter = AtomicInteger(0)
    
    /**
     * Flag to prevent new jobs after shutdown
     */
    private val isShutdown = AtomicBoolean(false)
    
    /**
     * Launch a job in the service scope with automatic tracking.
     * 
     * @param name Job name for tracking (auto-generated if null)
     * @param block Suspend function to execute
     * @return The launched job
     * @throws IllegalStateException if manager is shutdown
     */
    fun launch(
        name: String? = null,
        block: suspend CoroutineScope.() -> Unit
    ): Job {
        check(!isShutdown.get()) { "CoroutineManager is shutdown" }
        
        val jobName = name ?: "job-${jobCounter.incrementAndGet()}"
        
        val job = serviceScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                Log.d(TAG, "Job $jobName cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Job $jobName failed", e)
                throw e
            } finally {
                activeJobs.remove(jobName)
            }
        }
        
        activeJobs[jobName] = job
        
        // Clean up when job completes
        job.invokeOnCompletion {
            activeJobs.remove(jobName)
        }
        
        return job
    }
    
    /**
     * Launch a job in the IO scope for blocking operations.
     * 
     * @param name Job name for tracking
     * @param block Suspend function to execute
     * @return The launched job
     */
    fun launchIO(
        name: String? = null,
        block: suspend CoroutineScope.() -> Unit
    ): Job {
        check(!isShutdown.get()) { "CoroutineManager is shutdown" }
        
        val jobName = name ?: "io-job-${jobCounter.incrementAndGet()}"
        
        val job = ioScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                Log.d(TAG, "IO job $jobName cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "IO job $jobName failed", e)
                throw e
            } finally {
                activeJobs.remove(jobName)
            }
        }
        
        activeJobs[jobName] = job
        
        job.invokeOnCompletion {
            activeJobs.remove(jobName)
        }
        
        return job
    }
    
    /**
     * Execute a suspend function with proper error handling and timeout.
     * 
     * @param name Operation name for logging
     * @param timeoutMs Timeout in milliseconds (0 for no timeout)
     * @param block Suspend function to execute
     * @return Result of the block or null if failed
     */
    suspend fun <T> execute(
        name: String,
        timeoutMs: Long = 0,
        block: suspend () -> T
    ): Result<T> = withContext(Dispatchers.Default) {
        try {
            val result = if (timeoutMs > 0) {
                kotlinx.coroutines.withTimeout(timeoutMs) {
                    block()
                }
            } else {
                block()
            }
            Result.success(result)
        } catch (e: CancellationException) {
            Log.d(TAG, "Operation $name cancelled")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Operation $name failed", e)
            Result.failure(e)
        }
    }
    
    /**
     * Cancel all active jobs.
     */
    fun cancelAll(message: String = "Cancelled by manager") {
        Log.d(TAG, "Cancelling all jobs: $message")
        
        serviceScope.coroutineContext[Job]?.cancelChildren(
            CancellationException(message)
        )
        ioScope.coroutineContext[Job]?.cancelChildren(
            CancellationException(message)
        )
        
        activeJobs.clear()
    }
    
    /**
     * Cancel a specific job by name.
     * 
     * @param name Job name
     * @return True if job was found and cancelled
     */
    fun cancelJob(name: String): Boolean {
        val job = activeJobs[name]
        job?.cancel()
        return job != null
    }
    
    /**
     * Check if a job is active.
     * 
     * @param name Job name
     * @return True if job exists and is active
     */
    fun isJobActive(name: String): Boolean {
        return activeJobs[name]?.isActive == true
    }
    
    /**
     * Get count of active jobs.
     */
    fun getActiveJobCount(): Int = activeJobs.size
    
    /**
     * Get list of active job names.
     */
    fun getActiveJobNames(): List<String> = activeJobs.keys.toList()
    
    /**
     * Wait for all jobs to complete.
     * 
     * @param timeoutMs Maximum time to wait
     * @return True if all jobs completed, false if timed out
     */
    suspend fun awaitAll(timeoutMs: Long = 30000): Boolean {
        return try {
            withContext(Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(timeoutMs) {
                    activeJobs.values.forEach { it.join() }
                }
            }
            true
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(TAG, "Timeout waiting for jobs")
            false
        }
    }
    
    /**
     * Shutdown the manager and cancel all jobs.
     */
    override fun close() {
        if (isShutdown.compareAndSet(false, true)) {
            Log.d(TAG, "Shutting down CoroutineManager")
            
            cancelAll("Manager shutdown")
            
            serviceScope.cancel()
            ioScope.cancel()
            
            activeJobs.clear()
        }
    }
    
    /**
     * Check if the manager is shutdown.
     */
    fun isShutdown(): Boolean = isShutdown.get()
}

/**
 * Utility class for safe null handling.
 */
object NullSafety {
    
    /**
     * Execute block if value is not null, otherwise return default.
     */
    inline fun <T, R> T?.ifNotNull(block: (T) -> R): R? {
        return this?.let(block)
    }
    
    /**
     * Execute block if value is not null or empty.
     */
    inline fun <T : CharSequence, R> T?.ifNotNullOrEmpty(block: (T) -> R): R? {
        return if (!this.isNullOrEmpty()) block(this) else null
    }
    
    /**
     * Require not null with custom message.
     */
    fun <T> T?.requireNotNull(lazyMessage: () -> Any): T {
        return this ?: throw IllegalStateException(lazyMessage().toString())
    }
    
    /**
     * Get or default value.
     */
    fun <T> T?.orDefault(default: T): T = this ?: default
    
    /**
     * Get or compute default value.
     */
    inline fun <T> T?.orCompute(default: () -> T): T = this ?: default()
}

/**
 * Utility class for resource management.
 */
object ResourceManager {
    
    private val resources = ConcurrentHashMap<String, Closeable>()
    
    /**
     * Register a resource for automatic cleanup.
     */
    fun register(name: String, resource: Closeable) {
        resources[name]?.close() // Close existing if present
        resources[name] = resource
    }
    
    /**
     * Unregister a resource without closing.
     */
    fun unregister(name: String): Closeable? {
        return resources.remove(name)
    }
    
    /**
     * Get a registered resource.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Closeable> get(name: String): T? {
        return resources[name] as? T
    }
    
    /**
     * Close and remove a specific resource.
     */
    fun closeResource(name: String) {
        resources.remove(name)?.close()
    }
    
    /**
     * Close all registered resources.
     */
    fun closeAll() {
        resources.forEach { (name, resource) ->
            try {
                resource.close()
            } catch (e: Exception) {
                Log.w("ResourceManager", "Error closing $name", e)
            }
        }
        resources.clear()
    }
    
    /**
     * Check if a resource is registered.
     */
    fun hasResource(name: String): Boolean = resources.containsKey(name)
}
