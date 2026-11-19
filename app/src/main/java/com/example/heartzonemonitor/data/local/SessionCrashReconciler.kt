package com.example.heartzonemonitor.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.heartzonemonitor.data.local.dao.HeartRateSampleDao
import com.example.heartzonemonitor.data.local.dao.SessionDao
import com.example.heartzonemonitor.data.local.entity.SessionEntity
import com.example.heartzonemonitor.data.local.entity.SessionStatus
import kotlin.math.roundToInt

/**
 * Reconciles incomplete Sessions left in [SessionStatus.RECORDING] or [SessionStatus.PAUSED]
 * after OS process death, battery depletion, or ungraceful shutdown.
 *
 * Persisted Heart Rate Samples are salvaged into summary metrics and each incomplete Session is
 * transitioned atomically to [SessionStatus.COMPLETED_UNEXPECTEDLY] without user intervention.
 */
object SessionCrashReconciler {

    private const val DATA_GAP_THRESHOLD_MS = 3_000L
    private const val NOMINAL_SAMPLE_DURATION_MS = 1_000L

    private val INCOMPLETE_STATUS_NAMES = arrayOf(
        SessionStatus.RECORDING.name,
        SessionStatus.PAUSED.name
    )

    private val INCOMPLETE_STATUSES = listOf(
        SessionStatus.RECORDING,
        SessionStatus.PAUSED
    )

    private const val REQUIRED_TABLES_QUERY =
        "SELECT COUNT(*) FROM sqlite_master " +
            "WHERE type = 'table' AND name IN ('sessions', 'heart_rate_samples')"

    private const val SELECT_INCOMPLETE_SESSIONS_SQL =
        "SELECT id, startEpochMs, endEpochMs, elapsedDurationMs, effectiveDurationMs, pausedDurationMs " +
            "FROM sessions " +
            "WHERE status IN (?, ?) " +
            "ORDER BY startEpochMs DESC, id ASC"

    private const val SELECT_SESSION_SAMPLES_SQL =
        "SELECT id, timestampEpochMs, bpm, zoneIndex " +
            "FROM heart_rate_samples " +
            "WHERE sessionId = ? " +
            "ORDER BY timestampEpochMs ASC, id ASC"

    private const val UPDATE_SESSION_SQL =
        "UPDATE sessions SET " +
            "endEpochMs = ?, " +
            "elapsedDurationMs = ?, " +
            "effectiveDurationMs = ?, " +
            "pausedDurationMs = ?, " +
            "status = ?, " +
            "avgBpm = ?, " +
            "maxBpm = ?, " +
            "minBpm = ?, " +
            "zone1DurationMs = ?, " +
            "zone2DurationMs = ?, " +
            "zone3DurationMs = ?, " +
            "zone4DurationMs = ?, " +
            "zone5DurationMs = ? " +
            "WHERE id = ? AND status IN (?, ?)"

    /**
     * Reconciles incomplete Sessions directly on [db] during [AppDatabase] open without invoking
     * Room DAOs synchronously inside `onOpen`.
     *
     * @return number of Session rows transitioned to [SessionStatus.COMPLETED_UNEXPECTEDLY].
     */
    fun reconcile(db: SupportSQLiteDatabase): Int {
        if (db.isReadOnly || !hasRequiredTables(db)) {
            return 0
        }

        // Fast path: avoid acquiring a write transaction when no incomplete Sessions exist.
        if (queryIncompleteSessions(db).isEmpty()) {
            return 0
        }

        var reconciledCount = 0
        db.beginTransaction()
        try {
            val incompleteSessions = queryIncompleteSessions(db)
            if (incompleteSessions.isNotEmpty()) {
                db.compileStatement(UPDATE_SESSION_SQL).use { statement ->
                    for (session in incompleteSessions) {
                        val samples = querySamplesForSession(db, session.id)
                        val reconciled = computeReconciledValues(session, samples)

                        statement.clearBindings()
                        statement.bindLong(1, reconciled.endEpochMs)
                        statement.bindLong(2, reconciled.elapsedDurationMs)
                        statement.bindLong(3, reconciled.effectiveDurationMs)
                        statement.bindLong(4, reconciled.pausedDurationMs)
                        statement.bindString(5, reconciled.status.name)
                        statement.bindLong(6, reconciled.avgBpm.toLong())
                        statement.bindLong(7, reconciled.maxBpm.toLong())
                        statement.bindLong(8, reconciled.minBpm.toLong())
                        statement.bindLong(9, reconciled.zone1DurationMs)
                        statement.bindLong(10, reconciled.zone2DurationMs)
                        statement.bindLong(11, reconciled.zone3DurationMs)
                        statement.bindLong(12, reconciled.zone4DurationMs)
                        statement.bindLong(13, reconciled.zone5DurationMs)
                        statement.bindString(14, session.id)
                        statement.bindString(15, SessionStatus.RECORDING.name)
                        statement.bindString(16, SessionStatus.PAUSED.name)

                        reconciledCount += statement.executeUpdateDelete()
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return reconciledCount
    }

    /**
     * Reconciles incomplete Sessions through [SessionDao] and [HeartRateSampleDao] using the same
     * summary calculation logic as [reconcile] on [SupportSQLiteDatabase].
     *
     * @return list of reconciled [SessionEntity] rows updated to [SessionStatus.COMPLETED_UNEXPECTEDLY].
     */
    suspend fun reconcile(
        sessionDao: SessionDao,
        sampleDao: HeartRateSampleDao
    ): List<SessionEntity> {
        val incompleteSessions = sessionDao.getSessionsByStatuses(INCOMPLETE_STATUSES)
        if (incompleteSessions.isEmpty()) {
            return emptyList()
        }

        val reconciledSessions = ArrayList<SessionEntity>(incompleteSessions.size)
        for (session in incompleteSessions) {
            val samples = sampleDao.getSamplesForSession(session.id).map { sample ->
                SamplePoint(
                    id = sample.id,
                    timestampEpochMs = sample.timestampEpochMs,
                    bpm = sample.bpm,
                    zoneIndex = sample.zoneIndex
                )
            }
            val reconciled = computeReconciledValues(
                session = IncompleteSessionSnapshot(
                    id = session.id,
                    startEpochMs = session.startEpochMs,
                    endEpochMs = session.endEpochMs,
                    elapsedDurationMs = session.elapsedDurationMs,
                    effectiveDurationMs = session.effectiveDurationMs,
                    pausedDurationMs = session.pausedDurationMs
                ),
                samples = samples
            )
            val updatedSession = session.copy(
                endEpochMs = reconciled.endEpochMs,
                elapsedDurationMs = reconciled.elapsedDurationMs,
                effectiveDurationMs = reconciled.effectiveDurationMs,
                pausedDurationMs = reconciled.pausedDurationMs,
                status = reconciled.status,
                avgBpm = reconciled.avgBpm,
                maxBpm = reconciled.maxBpm,
                minBpm = reconciled.minBpm,
                zone1DurationMs = reconciled.zone1DurationMs,
                zone2DurationMs = reconciled.zone2DurationMs,
                zone3DurationMs = reconciled.zone3DurationMs,
                zone4DurationMs = reconciled.zone4DurationMs,
                zone5DurationMs = reconciled.zone5DurationMs
            )
            val updatedCount = sessionDao.updateSession(updatedSession)
            if (updatedCount > 0) {
                reconciledSessions.add(updatedSession)
            }
        }
        return reconciledSessions
    }

    private fun hasRequiredTables(db: SupportSQLiteDatabase): Boolean {
        db.query(REQUIRED_TABLES_QUERY).use { cursor ->
            return cursor.moveToFirst() && cursor.getInt(0) == 2
        }
    }

    private fun queryIncompleteSessions(db: SupportSQLiteDatabase): List<IncompleteSessionSnapshot> {
        val sessions = mutableListOf<IncompleteSessionSnapshot>()
        db.query(SELECT_INCOMPLETE_SESSIONS_SQL, INCOMPLETE_STATUS_NAMES).use { cursor ->
            while (cursor.moveToNext()) {
                sessions.add(
                    IncompleteSessionSnapshot(
                        id = cursor.getString(0),
                        startEpochMs = cursor.getLong(1),
                        endEpochMs = if (cursor.isNull(2)) null else cursor.getLong(2),
                        elapsedDurationMs = cursor.getLong(3),
                        effectiveDurationMs = cursor.getLong(4),
                        pausedDurationMs = cursor.getLong(5)
                    )
                )
            }
        }
        return sessions
    }

    private fun querySamplesForSession(
        db: SupportSQLiteDatabase,
        sessionId: String
    ): List<SamplePoint> {
        val samples = mutableListOf<SamplePoint>()
        db.query(SELECT_SESSION_SAMPLES_SQL, arrayOf(sessionId)).use { cursor ->
            while (cursor.moveToNext()) {
                samples.add(
                    SamplePoint(
                        id = cursor.getLong(0),
                        timestampEpochMs = cursor.getLong(1),
                        bpm = cursor.getInt(2),
                        zoneIndex = cursor.getInt(3)
                    )
                )
            }
        }
        return samples
    }

    private fun computeReconciledValues(
        session: IncompleteSessionSnapshot,
        samples: List<SamplePoint>
    ): ReconciledSessionValues {
        if (samples.isEmpty()) {
            val resolvedEndEpochMs = session.endEpochMs ?: session.startEpochMs
            val wallElapsedMs = (resolvedEndEpochMs - session.startEpochMs).coerceAtLeast(0L)
            val resolvedElapsedMs = maxOf(
                session.elapsedDurationMs,
                wallElapsedMs,
                session.effectiveDurationMs + session.pausedDurationMs
            ).coerceAtLeast(0L)
            val resolvedEffectiveMs = session.effectiveDurationMs
                .coerceAtLeast(0L)
                .coerceAtMost(resolvedElapsedMs)
            val resolvedPausedMs = (resolvedElapsedMs - resolvedEffectiveMs).coerceAtLeast(0L)

            return ReconciledSessionValues(
                endEpochMs = resolvedEndEpochMs,
                elapsedDurationMs = resolvedElapsedMs,
                effectiveDurationMs = resolvedEffectiveMs,
                pausedDurationMs = resolvedPausedMs,
                status = SessionStatus.COMPLETED_UNEXPECTEDLY,
                avgBpm = 0,
                maxBpm = 0,
                minBpm = 0,
                zone1DurationMs = 0L,
                zone2DurationMs = 0L,
                zone3DurationMs = 0L,
                zone4DurationMs = 0L,
                zone5DurationMs = 0L
            )
        }

        val orderedSamples = samples.sortedWith(
            compareBy<SamplePoint> { it.timestampEpochMs }
                .thenBy { it.id }
        )

        val resolvedEndEpochMs = orderedSamples.last().timestampEpochMs
        val avgBpm = orderedSamples.map { it.bpm }.average().roundToInt().coerceAtLeast(0)
        val maxBpm = orderedSamples.maxOf { it.bpm }.coerceAtLeast(0)
        val minBpm = orderedSamples.minOf { it.bpm }.coerceAtLeast(0)

        var zone1DurationMs = 0L
        var zone2DurationMs = 0L
        var zone3DurationMs = 0L
        var zone4DurationMs = 0L
        var zone5DurationMs = 0L

        val lastIndex = orderedSamples.lastIndex
        for (i in orderedSamples.indices) {
            val creditedDurationMs = if (i < lastIndex) {
                val delta = orderedSamples[i + 1].timestampEpochMs - orderedSamples[i].timestampEpochMs
                when {
                    delta <= 0L -> 0L
                    delta <= DATA_GAP_THRESHOLD_MS -> delta
                    else -> NOMINAL_SAMPLE_DURATION_MS
                }
            } else {
                NOMINAL_SAMPLE_DURATION_MS
            }

            when (orderedSamples[i].zoneIndex) {
                1 -> zone1DurationMs += creditedDurationMs
                2 -> zone2DurationMs += creditedDurationMs
                3 -> zone3DurationMs += creditedDurationMs
                4 -> zone4DurationMs += creditedDurationMs
                5 -> zone5DurationMs += creditedDurationMs
            }
        }

        val totalZoneDurationMs =
            zone1DurationMs + zone2DurationMs + zone3DurationMs + zone4DurationMs + zone5DurationMs
        val wallElapsedMs = (resolvedEndEpochMs - session.startEpochMs).coerceAtLeast(0L)
        val resolvedElapsedMs = maxOf(
            session.elapsedDurationMs,
            wallElapsedMs,
            totalZoneDurationMs
        ).coerceAtLeast(0L)
        val resolvedEffectiveMs = maxOf(session.effectiveDurationMs, totalZoneDurationMs)
            .coerceAtLeast(0L)
            .coerceAtMost(resolvedElapsedMs)
        val resolvedPausedMs = (resolvedElapsedMs - resolvedEffectiveMs).coerceAtLeast(0L)

        return ReconciledSessionValues(
            endEpochMs = resolvedEndEpochMs,
            elapsedDurationMs = resolvedElapsedMs,
            effectiveDurationMs = resolvedEffectiveMs,
            pausedDurationMs = resolvedPausedMs,
            status = SessionStatus.COMPLETED_UNEXPECTEDLY,
            avgBpm = avgBpm,
            maxBpm = maxBpm,
            minBpm = minBpm,
            zone1DurationMs = zone1DurationMs,
            zone2DurationMs = zone2DurationMs,
            zone3DurationMs = zone3DurationMs,
            zone4DurationMs = zone4DurationMs,
            zone5DurationMs = zone5DurationMs
        )
    }

    private data class IncompleteSessionSnapshot(
        val id: String,
        val startEpochMs: Long,
        val endEpochMs: Long?,
        val elapsedDurationMs: Long,
        val effectiveDurationMs: Long,
        val pausedDurationMs: Long
    )

    private data class SamplePoint(
        val id: Long,
        val timestampEpochMs: Long,
        val bpm: Int,
        val zoneIndex: Int
    )

    private data class ReconciledSessionValues(
        val endEpochMs: Long,
        val elapsedDurationMs: Long,
        val effectiveDurationMs: Long,
        val pausedDurationMs: Long,
        val status: SessionStatus,
        val avgBpm: Int,
        val maxBpm: Int,
        val minBpm: Int,
        val zone1DurationMs: Long,
        val zone2DurationMs: Long,
        val zone3DurationMs: Long,
        val zone4DurationMs: Long,
        val zone5DurationMs: Long
    )
}
