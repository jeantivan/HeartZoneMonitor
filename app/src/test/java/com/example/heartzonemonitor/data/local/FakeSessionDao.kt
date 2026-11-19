package com.example.heartzonemonitor.data.local

import com.example.heartzonemonitor.data.local.dao.SessionDao
import com.example.heartzonemonitor.data.local.entity.SessionEntity
import com.example.heartzonemonitor.data.local.entity.SessionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FakeSessionDao(
    initialSessions: List<SessionEntity> = emptyList(),
    private val sampleDao: FakeHeartRateSampleDao? = null
) : SessionDao {

    private val mutex = Mutex()
    private val sessionsState = MutableStateFlow(sortSessions(initialSessions))

    override suspend fun insertSession(session: SessionEntity) {
        mutex.withLock {
            val current = sessionsState.value
            check(current.none { it.id == session.id }) {
                "Session with id ${session.id} already exists"
            }
            sessionsState.value = sortSessions(current + session)
        }
    }

    override suspend fun upsertSession(session: SessionEntity) {
        mutex.withLock {
            val mutable = sessionsState.value.toMutableList()
            val existingIndex = mutable.indexOfFirst { it.id == session.id }
            if (existingIndex >= 0) {
                mutable[existingIndex] = session
            } else {
                mutable.add(session)
            }
            sessionsState.value = sortSessions(mutable)
        }
    }

    override suspend fun updateSession(session: SessionEntity): Int {
        return mutex.withLock {
            val mutable = sessionsState.value.toMutableList()
            val existingIndex = mutable.indexOfFirst { it.id == session.id }
            if (existingIndex < 0) {
                0
            } else {
                mutable[existingIndex] = session
                sessionsState.value = sortSessions(mutable)
                1
            }
        }
    }

    override suspend fun getSessionById(sessionId: String): SessionEntity? =
        sessionsState.value.firstOrNull { it.id == sessionId }

    override fun observeSessionById(sessionId: String): Flow<SessionEntity?> =
        sessionsState.map { list -> list.firstOrNull { it.id == sessionId } }

    override fun observeAllSessions(): Flow<List<SessionEntity>> = sessionsState

    override suspend fun getAllSessions(): List<SessionEntity> = sessionsState.value

    override suspend fun getSessionsByStatuses(statuses: List<SessionStatus>): List<SessionEntity> {
        if (statuses.isEmpty()) return emptyList()
        val statusSet = statuses.toSet()
        return sessionsState.value.filter { it.status in statusSet }
    }

    override suspend fun deleteSessionById(sessionId: String): Int {
        val deletedCount = mutex.withLock {
            val before = sessionsState.value
            val after = before.filterNot { it.id == sessionId }
            val removed = before.size - after.size
            if (removed > 0) {
                sessionsState.value = after
            }
            removed
        }
        if (deletedCount > 0) {
            sampleDao?.deleteSamplesForSession(sessionId)
        }
        return deletedCount
    }

    override suspend fun deleteSession(session: SessionEntity): Int =
        deleteSessionById(session.id)

    private fun sortSessions(sessions: List<SessionEntity>): List<SessionEntity> {
        return sessions.sortedWith(
            compareByDescending<SessionEntity> { it.startEpochMs }
                .thenBy { it.id }
        )
    }
}
