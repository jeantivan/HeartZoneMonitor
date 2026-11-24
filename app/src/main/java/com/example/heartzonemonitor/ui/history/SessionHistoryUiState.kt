package com.example.heartzonemonitor.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.heartzonemonitor.data.ble.resolveHrmDisplayName
import com.example.heartzonemonitor.data.local.dao.RememberedHrmDao
import com.example.heartzonemonitor.data.local.dao.SessionDao
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import com.example.heartzonemonitor.data.local.entity.SessionEntity
import com.example.heartzonemonitor.data.local.entity.SessionStatus
import com.example.heartzonemonitor.domain.model.HeartZone
import com.example.heartzonemonitor.ui.onboarding.formatMacSnippet
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * Proportional share of a Session spent in a single [HeartZone], derived directly from
 * precalculated [SessionEntity] zone duration columns.
 */
data class ZoneDistributionSegment(
    val zone: HeartZone,
    val durationMs: Long,
    val fraction: Float
)

/**
 * Pre-formatted O(1) display model for a finalized historical Session card.
 */
data class SessionHistoryItem(
    val id: String,
    val startEpochMs: Long,
    val formattedDate: String,
    val formattedStartTime: String,
    val effectiveDurationMs: Long,
    val formattedEffectiveDuration: String,
    val avgBpm: Int,
    val maxBpm: Int,
    val hrmDisplayName: String,
    val status: SessionStatus,
    val isUnexpectedCompletion: Boolean,
    val zoneSegments: List<ZoneDistributionSegment>,
    val dominantZone: ZoneDistributionSegment?
)

/**
 * Aggregate UI state rendered by the Session History screen.
 */
data class SessionHistoryUiState(
    val sessions: List<SessionHistoryItem> = emptyList(),
    val isLoading: Boolean = true,
    val sessionCount: Int = sessions.size,
    val totalEffectiveDurationMs: Long = sessions.sumOf { it.effectiveDurationMs }
) {
    val isEmpty: Boolean
        get() = !isLoading && sessions.isEmpty()
}

/**
 * Bundled user callbacks for Session History following CODING_STANDARDS.md Section 5.
 */
data class SessionHistoryActions(
    val onSessionClick: (String) -> Unit = {}
)

/**
 * Formats a Session's Effective Duration in milliseconds as zero-padded `mm:ss` under one hour
 * or `HH:mm:ss` at or above one hour.
 */
fun formatSessionEffectiveDuration(effectiveDurationMs: Long): String {
    val totalSeconds = effectiveDurationMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

/**
 * Formats a Heart Zone duration in milliseconds into a compact legend string such as `21m 44s`
 * or `1h 05m 12s`.
 */
fun formatZoneDurationCompact(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> String.format(Locale.US, "%dh %02dm %02ds", hours, minutes, seconds)
        minutes > 0L -> String.format(Locale.US, "%dm %02ds", minutes, seconds)
        else -> String.format(Locale.US, "%ds", seconds)
    }
}

/**
 * Formats the aggregate Session count and cumulative Effective Duration hours for the header.
 */
fun formatSessionHistorySummary(
    sessionCount: Int,
    totalEffectiveDurationMs: Long,
    locale: Locale = Locale.US
): String {
    val sessionWord = if (sessionCount == 1) "SESSION" else "SESSIONS"
    val hours = totalEffectiveDurationMs.coerceAtLeast(0L) / 3_600_000.0
    return String.format(locale, "%d %s · %.1f HRS", sessionCount, sessionWord, hours)
}

/**
 * Maps a finalized [SessionEntity] into an O(1) [SessionHistoryItem] using purely precalculated
 * summary columns without querying Heart Rate Samples.
 */
internal fun SessionEntity.toSessionHistoryItem(
    hrmsByNormalizedMac: Map<String, RememberedHrmEntity>,
    zoneId: ZoneId,
    dateFormatter: DateTimeFormatter,
    timeFormatter: DateTimeFormatter
): SessionHistoryItem {
    val zonedStart = Instant.ofEpochMilli(startEpochMs).atZone(zoneId)
    val zoneDurations = listOf(
        HeartZone.ZONE_1 to zone1DurationMs.coerceAtLeast(0L),
        HeartZone.ZONE_2 to zone2DurationMs.coerceAtLeast(0L),
        HeartZone.ZONE_3 to zone3DurationMs.coerceAtLeast(0L),
        HeartZone.ZONE_4 to zone4DurationMs.coerceAtLeast(0L),
        HeartZone.ZONE_5 to zone5DurationMs.coerceAtLeast(0L)
    )
    val totalZoneDurationMs = zoneDurations.sumOf { it.second }
    val segments = zoneDurations.map { (zone, durationMs) ->
        val fraction = if (totalZoneDurationMs > 0L) {
            (durationMs.toDouble() / totalZoneDurationMs.toDouble()).toFloat()
        } else {
            0f
        }
        ZoneDistributionSegment(
            zone = zone,
            durationMs = durationMs,
            fraction = fraction
        )
    }
    val dominantZone = if (totalZoneDurationMs > 0L) {
        segments.maxByOrNull { it.durationMs }
    } else {
        null
    }
    val matchedHrm = hrmsByNormalizedMac[hrmMacAddress.trim().uppercase(Locale.ROOT)]
    val hrmDisplayName = if (matchedHrm != null) {
        resolveHrmDisplayName(matchedHrm)
    } else {
        formatMacSnippet(hrmMacAddress)
    }

    return SessionHistoryItem(
        id = id,
        startEpochMs = startEpochMs,
        formattedDate = dateFormatter.format(zonedStart),
        formattedStartTime = timeFormatter.format(zonedStart),
        effectiveDurationMs = effectiveDurationMs,
        formattedEffectiveDuration = formatSessionEffectiveDuration(effectiveDurationMs),
        avgBpm = avgBpm,
        maxBpm = maxBpm,
        hrmDisplayName = hrmDisplayName,
        status = status,
        isUnexpectedCompletion = status == SessionStatus.COMPLETED_UNEXPECTEDLY,
        zoneSegments = segments,
        dominantZone = dominantZone
    )
}

/**
 * Observes finalized recorded Sessions directly from [SessionDao.observeAllSessions] in strict
 * O(1) per-session complexity using precalculated summary metrics (no Heart Rate Sample queries
 * or table joins).
 */
class SessionHistoryViewModel(
    sessionDao: SessionDao,
    rememberedHrmDao: RememberedHrmDao? = null,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val locale: Locale = Locale.US
) : ViewModel() {

    private val dateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy", locale)
    private val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("hh:mm a", locale)

    private val hrmsFlow: Flow<List<RememberedHrmEntity>> =
        rememberedHrmDao?.observeAllHrms() ?: flowOf(emptyList())

    val uiState: StateFlow<SessionHistoryUiState> = combine(
        sessionDao.observeAllSessions(),
        hrmsFlow
    ) { sessions, hrms ->
        val hrmsByNormalizedMac = hrms.associateBy { it.macAddress.trim().uppercase(Locale.ROOT) }
        val finalizedItems = sessions
            .asSequence()
            .filter { it.status in FINALIZED_SESSION_STATUSES }
            .sortedWith(
                compareByDescending<SessionEntity> { it.startEpochMs }
                    .thenBy { it.id }
            )
            .map { session ->
                session.toSessionHistoryItem(
                    hrmsByNormalizedMac = hrmsByNormalizedMac,
                    zoneId = zoneId,
                    dateFormatter = dateFormatter,
                    timeFormatter = timeFormatter
                )
            }
            .toList()

        SessionHistoryUiState(
            sessions = finalizedItems,
            isLoading = false,
            sessionCount = finalizedItems.size,
            totalEffectiveDurationMs = finalizedItems.sumOf { it.effectiveDurationMs }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SessionHistoryUiState(isLoading = true)
    )

    companion object {
        private val FINALIZED_SESSION_STATUSES = setOf(
            SessionStatus.COMPLETED,
            SessionStatus.COMPLETED_UNEXPECTEDLY
        )
    }
}
