package com.example.heartzonemonitor.data.local

import com.example.heartzonemonitor.data.local.dao.HeartRateSampleDao
import com.example.heartzonemonitor.data.local.entity.HeartRateSampleEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Thread-safe in-memory buffer for 1 Hz Heart Rate Samples that periodically flushes batches
 * in atomic database transactions and supports immediate lifecycle flushes.
 */
class HeartRateSampleBuffer(
    private val sampleDao: HeartRateSampleDao,
    private val scope: CoroutineScope,
    private val flushIntervalMs: Long = DEFAULT_FLUSH_INTERVAL_MS
) {
    private val bufferMutex = Mutex()
    private val flushMutex = Mutex()
    private val buffer = ArrayDeque<HeartRateSampleEntity>()
    private val jobLock = Any()
    private var flushJob: Job? = null

    init {
        require(flushIntervalMs > 0L) { "flushIntervalMs must be positive" }
    }

    /** Appends a Heart Rate Sample to the in-memory buffer. */
    suspend fun enqueue(sample: HeartRateSampleEntity) {
        bufferMutex.withLock {
            buffer.addLast(sample)
        }
    }

    /** Starts the periodic flush loop if it is not already running. */
    fun startPeriodicFlush() {
        synchronized(jobLock) {
            if (flushJob?.isActive == true) {
                return
            }
            flushJob = scope.launch {
                while (isActive) {
                    delay(flushIntervalMs)
                    try {
                        flush()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Samples remain restored at the front of the buffer for the next retry.
                    }
                }
            }
        }
    }

    /**
     * Atomically drains currently buffered Heart Rate Samples and writes them via
     * [HeartRateSampleDao.insertSamplesBatch]. On failure, restores the drained batch to the
     * front of the buffer in original order before rethrowing.
     */
    suspend fun flush() {
        flushMutex.withLock {
            val batch = bufferMutex.withLock {
                if (buffer.isEmpty()) {
                    emptyList()
                } else {
                    buffer.toList().also { buffer.clear() }
                }
            }
            if (batch.isEmpty()) return@withLock

            try {
                sampleDao.insertSamplesBatch(batch)
            } catch (t: Throwable) {
                withContext(NonCancellable) {
                    bufferMutex.withLock {
                        for (index in batch.lastIndex downTo 0) {
                            buffer.addFirst(batch[index])
                        }
                    }
                }
                throw t
            }
        }
    }

    /** Immediately flushes buffered Heart Rate Samples when a Session is paused. */
    suspend fun onSessionPaused() {
        flush()
    }

    /** Cancels periodic flushing and immediately flushes remaining Heart Rate Samples on stop. */
    suspend fun onSessionStopped() {
        val jobToCancel = synchronized(jobLock) {
            flushJob.also { flushJob = null }
        }
        jobToCancel?.cancelAndJoin()
        flush()
    }

    /**
     * Cancels any active periodic flush loop and clears all buffered Heart Rate Samples
     * without persisting them to [HeartRateSampleDao].
     */
    suspend fun discard() {
        val jobToCancel = synchronized(jobLock) {
            flushJob.also { flushJob = null }
        }
        jobToCancel?.cancelAndJoin()
        flushMutex.withLock {
            bufferMutex.withLock {
                buffer.clear()
            }
        }
    }

    /** Immediately flushes buffered Heart Rate Samples when the app moves to the background. */
    suspend fun onAppBackgrounded() {
        flush()
    }

    /** Returns the number of Heart Rate Samples currently waiting in memory. */
    suspend fun pendingCount(): Int {
        return bufferMutex.withLock {
            buffer.size
        }
    }

    companion object {
        const val DEFAULT_FLUSH_INTERVAL_MS = 5_000L
    }
}
