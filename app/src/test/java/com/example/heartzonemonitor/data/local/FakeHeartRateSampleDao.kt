package com.example.heartzonemonitor.data.local

import com.example.heartzonemonitor.data.local.dao.HeartRateSampleDao
import com.example.heartzonemonitor.data.local.entity.HeartRateSampleEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FakeHeartRateSampleDao(
    initialSamples: List<HeartRateSampleEntity> = emptyList()
) : HeartRateSampleDao {

    private val mutex = Mutex()
    private var nextAutoId: Long = 1L
    private val samplesState: MutableStateFlow<List<HeartRateSampleEntity>>
    private val recordedBatchesInternal = mutableListOf<List<HeartRateSampleEntity>>()
    private val pendingFailures = ArrayDeque<Throwable>()

    var batchCallCount: Int = 0
        private set

    val recordedBatches: List<List<HeartRateSampleEntity>>
        get() = recordedBatchesInternal.toList()

    init {
        var nextId = (initialSamples.maxOfOrNull { it.id } ?: 0L) + 1L
        val initialized = initialSamples.map { sample ->
            if (sample.id == 0L) sample.copy(id = nextId++) else sample
        }
        nextAutoId = nextId
        samplesState = MutableStateFlow(sortSamples(initialized))
    }

    fun enqueueWriteFailure(error: Throwable) {
        pendingFailures.addLast(error)
    }

    suspend fun deleteSamplesForSession(sessionId: String) {
        mutex.withLock {
            samplesState.value = samplesState.value.filterNot { it.sessionId == sessionId }
        }
    }

    override suspend fun insertSamples(samples: List<HeartRateSampleEntity>) {
        mutex.withLock {
            insertSamplesLocked(samples)
        }
    }

    override suspend fun insertSamplesBatch(samples: List<HeartRateSampleEntity>) {
        if (samples.isEmpty()) return
        mutex.withLock {
            batchCallCount++
            insertSamplesLocked(samples)
        }
    }

    override suspend fun getSamplesForSession(sessionId: String): List<HeartRateSampleEntity> =
        samplesState.value.filter { it.sessionId == sessionId }

    override fun observeSamplesForSession(sessionId: String): Flow<List<HeartRateSampleEntity>> =
        samplesState.map { list -> list.filter { it.sessionId == sessionId } }

    override suspend fun getLatestSampleForSession(sessionId: String): HeartRateSampleEntity? =
        samplesState.value.lastOrNull { it.sessionId == sessionId }

    override suspend fun getSampleCountForSession(sessionId: String): Int =
        samplesState.value.count { it.sessionId == sessionId }

    override suspend fun getSamplesPage(
        sessionId: String,
        limit: Int,
        offset: Int
    ): List<HeartRateSampleEntity> {
        if (limit <= 0 || offset < 0) return emptyList()
        return samplesState.value
            .filter { it.sessionId == sessionId }
            .drop(offset)
            .take(limit)
    }

    private fun insertSamplesLocked(samples: List<HeartRateSampleEntity>) {
        val failure = pendingFailures.removeFirstOrNull()
        if (failure != null) {
            throw failure
        }
        val inserted = samples.map { sample ->
            if (sample.id == 0L) {
                sample.copy(id = nextAutoId++)
            } else {
                check(samplesState.value.none { it.id == sample.id }) {
                    "Heart Rate Sample with id ${sample.id} already exists"
                }
                if (sample.id >= nextAutoId) {
                    nextAutoId = sample.id + 1L
                }
                sample
            }
        }
        recordedBatchesInternal.add(inserted)
        samplesState.value = sortSamples(samplesState.value + inserted)
    }

    private fun sortSamples(samples: List<HeartRateSampleEntity>): List<HeartRateSampleEntity> {
        return samples.sortedWith(
            compareBy<HeartRateSampleEntity> { it.timestampEpochMs }
                .thenBy { it.id }
        )
    }
}
