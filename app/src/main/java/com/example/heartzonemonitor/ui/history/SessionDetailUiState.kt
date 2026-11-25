package com.example.heartzonemonitor.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.heartzonemonitor.data.ble.resolveHrmDisplayName
import com.example.heartzonemonitor.data.local.dao.RememberedHrmDao
import com.example.heartzonemonitor.data.local.dao.SessionDao
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import com.example.heartzonemonitor.data.local.entity.SessionEntity
import com.example.heartzonemonitor.data.local.entity.SessionStatus
import com.example.heartzonemonitor.domain.calculator.ZoneCalculator
import com.example.heartzonemonitor.domain.model.HeartZone
import com.example.heartzonemonitor.domain.model.ZoneCalculationMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

private const val SESSION_DETAIL_DATE_PATTERN = "EEEE, MMM d, yyyy"
private const val SESSION_DETAIL_TIMESTAMP_PATTERN = "MMM d, yyyy · hh:mm:ss a"

/**
 * Formats any Session or Heart Zone duration in milliseconds as zero-padded `HH:mm:ss`.
 */
fun formatDurationHhMmSs(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}

/**
 * Frozen User Profile physiological parameters captured at the start of a Session.
 */
data class FrozenProfileSnapshot(
    val userAge: Int,
    val restingHr: Int?,
    val calculatedHrmax: Int,
    val calculationMode: ZoneCalculationMode
)

/**
 * Per-zone breakdown entry for the Session Detail Zone Distribution Card.
 */
data class SessionZoneBreakdown(
    val zone: HeartZone,
    val minBpm: Int,
    val maxBpm: Int,
    val durationMs: Long,
    val formattedDuration: String = formatDurationHhMmSs(durationMs),
    val percentageOfEffective: Int,
    val barFraction: Float
)

/**
 * Formatted display model for the upper analytical sections of the Session Detail screen.
 */
data class SessionDetailModel(
    val formattedDate: String,
    val formattedStartTimestamp: String,
    val formattedEndTimestamp: String,
    val status: SessionStatus,
    val isUnexpectedCompletion: Boolean,
    val hrmDisplayName: String,
    val hrmMacAddress: String,
    val effectiveDurationMs: Long,
    val formattedEffectiveDuration: String,
    val formattedElapsedDuration: String,
    val formattedPausedDuration: String,
    val avgBpm: Int,
    val maxBpm: Int,
    val minBpm: Int,
    val profileSnapshot: FrozenProfileSnapshot,
    val zoneBreakdowns: List<SessionZoneBreakdown>,
    val dominantZone: SessionZoneBreakdown? = zoneBreakdowns
        .filter { it.durationMs > 0L }
        .maxByOrNull { it.durationMs }
)

/**
 * UI state exposed by [SessionDetailViewModel].
 */
data class SessionDetailUiState(
    val isLoading: Boolean = true,
    val session: SessionDetailModel? = null
)

/**
 * Bundled user actions for the Session Detail screen following CODING_STANDARDS.md Section 5.
 */
data class SessionDetailActions(
    val onNavigateBack: () -> Unit = {}
)

/**
 * Computes integer percentages of [effectiveDurationMs] for each zone duration in [zoneDurationsMs]
 * using Largest Remainder apportionment against `max(effectiveDurationMs, totalZoneMs)`.
 *
 * - Zones with `0L` duration always receive `0%`.
 * - Sub-second monotonic clock offsets (e.g. `300_000ms` zoned vs `300_100ms` effective) apportion
 *   to `100%` cleanly without `99%` or `101%` rounding drift.
 * - Gap sessions where `totalZoneMs < effectiveDurationMs` apportion to the exact rounded share
 *   of [effectiveDurationMs] (`round(totalZoneMs * 100.0 / effectiveDurationMs)`).
 */
internal fun computeZonePercentages(
    zoneDurationsMs: List<Long>,
    effectiveDurationMs: Long
): List<Int> {
    val sanitized = zoneDurationsMs.map { it.coerceAtLeast(0L) }
    val totalZoneMs = sanitized.sum()
    if (totalZoneMs == 0L) {
        return List(sanitized.size) { 0 }
    }

    val safeEffectiveMs = effectiveDurationMs.coerceAtLeast(0L)
    val denominatorMs = max(safeEffectiveMs, totalZoneMs)
    val targetTotalPercent = ((totalZoneMs.toDouble() * 100.0) / denominatorMs.toDouble())
        .roundToInt()
        .coerceIn(0, 100)

    val exactQuotas = sanitized.map { (it.toDouble() * 100.0) / denominatorMs.toDouble() }
    val allocated = exactQuotas.mapIndexed { idx, quota ->
        if (sanitized[idx] == 0L) 0 else floor(quota).toInt()
    }.toMutableList()

    var remainderPoints = targetTotalPercent - allocated.sum()
    if (remainderPoints > 0) {
        val orderByRemainder = sanitized.indices
            .filter { sanitized[it] > 0L }
            .sortedWith(
                compareByDescending<Int> { exactQuotas[it] - allocated[it].toDouble() }
                    .thenByDescending { sanitized[it] }
                    .thenBy { it }
            )
        var cursor = 0
        while (remainderPoints > 0 && orderByRemainder.isNotEmpty()) {
            val targetIdx = orderByRemainder[cursor % orderByRemainder.size]
            allocated[targetIdx] += 1
            remainderPoints -= 1
            cursor += 1
        }
    }
    return allocated
}

/**
 * Observes a single Session from [SessionDao.observeSessionById] and optional
 * [RememberedHrmDao.observeAllHrms] to expose formatted Session Detail analytics.
 */
class SessionDetailViewModel(
    sessionId: String,
    sessionDao: SessionDao,
    rememberedHrmDao: RememberedHrmDao? = null,
    private val zoneCalculator: ZoneCalculator = ZoneCalculator(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.US
) : ViewModel() {

    private val dateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern(SESSION_DETAIL_DATE_PATTERN, locale)
    private val timestampFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern(SESSION_DETAIL_TIMESTAMP_PATTERN, locale)

    private val hrmsFlow: Flow<List<RememberedHrmEntity>> =
        rememberedHrmDao?.observeAllHrms() ?: flowOf(emptyList())

    val uiState: StateFlow<SessionDetailUiState> = combine(
        sessionDao.observeSessionById(sessionId),
        hrmsFlow
    ) { session, hrms ->
        if (session == null) {
            SessionDetailUiState(isLoading = false, session = null)
        } else {
            val hrmsByNormalizedMac =
                hrms.associateBy { it.macAddress.trim().uppercase(Locale.ROOT) }
            SessionDetailUiState(
                isLoading = false,
                session = session.toSessionDetailModel(
                    hrmsByNormalizedMac = hrmsByNormalizedMac,
                    zoneCalculator = zoneCalculator,
                    zoneId = zoneId,
                    dateFormatter = dateFormatter,
                    timestampFormatter = timestampFormatter
                )
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SessionDetailUiState(isLoading = true)
    )

    companion object {
        const val UNKNOWN_HRM_DISPLAY_NAME = "Unknown HRM"
    }
}

internal fun SessionEntity.toSessionDetailModel(
    hrmsByNormalizedMac: Map<String, RememberedHrmEntity>,
    zoneCalculator: ZoneCalculator = ZoneCalculator(),
    zoneId: ZoneId = ZoneId.of("UTC"),
    dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern(SESSION_DETAIL_DATE_PATTERN, Locale.US),
    timestampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern(SESSION_DETAIL_TIMESTAMP_PATTERN, Locale.US)
): SessionDetailModel {
    val zonedStart = Instant.ofEpochMilli(startEpochMs).atZone(zoneId)
    val formattedEnd = endEpochMs?.let { endMs ->
        timestampFormatter.format(Instant.ofEpochMilli(endMs).atZone(zoneId))
    } ?: "--"

    val normalizedMac = hrmMacAddress.trim().uppercase(Locale.ROOT)
    val matchedHrm = hrmsByNormalizedMac[normalizedMac]
    val hrmDisplayName = if (matchedHrm != null) {
        resolveHrmDisplayName(matchedHrm)
    } else {
        SessionDetailViewModel.UNKNOWN_HRM_DISPLAY_NAME
    }

    val effectiveRestingHr = restingHr?.takeIf {
        calculationMode == ZoneCalculationMode.KARVONEN && it < calculatedHrmax
    }
    val zoneResult = zoneCalculator.calculateZones(
        age = userAge,
        restingHr = effectiveRestingHr,
        customHrmax = calculatedHrmax
    )

    val zoneDurationsMs = listOf(
        zone1DurationMs.coerceAtLeast(0L),
        zone2DurationMs.coerceAtLeast(0L),
        zone3DurationMs.coerceAtLeast(0L),
        zone4DurationMs.coerceAtLeast(0L),
        zone5DurationMs.coerceAtLeast(0L)
    )
    val totalZoneMs = zoneDurationsMs.sum()
    val denominatorMs = max(effectiveDurationMs.coerceAtLeast(0L), totalZoneMs)
    val percentages = computeZonePercentages(
        zoneDurationsMs = zoneDurationsMs,
        effectiveDurationMs = effectiveDurationMs
    )

    val zoneBreakdowns = zoneResult.zones.mapIndexed { index, range ->
        val durationMs = zoneDurationsMs[index]
        val barFraction = if (denominatorMs > 0L && durationMs > 0L) {
            (durationMs.toDouble() / denominatorMs.toDouble()).toFloat()
        } else {
            0f
        }
        SessionZoneBreakdown(
            zone = range.zone,
            minBpm = range.minBpm,
            maxBpm = range.maxBpm,
            durationMs = durationMs,
            formattedDuration = formatDurationHhMmSs(durationMs),
            percentageOfEffective = percentages[index],
            barFraction = barFraction
        )
    }

    return SessionDetailModel(
        formattedDate = dateFormatter.format(zonedStart),
        formattedStartTimestamp = timestampFormatter.format(zonedStart),
        formattedEndTimestamp = formattedEnd,
        status = status,
        isUnexpectedCompletion = status == SessionStatus.COMPLETED_UNEXPECTEDLY,
        hrmDisplayName = hrmDisplayName,
        hrmMacAddress = normalizedMac,
        effectiveDurationMs = effectiveDurationMs,
        formattedEffectiveDuration = formatDurationHhMmSs(effectiveDurationMs),
        formattedElapsedDuration = formatDurationHhMmSs(elapsedDurationMs),
        formattedPausedDuration = formatDurationHhMmSs(pausedDurationMs),
        avgBpm = avgBpm,
        maxBpm = maxBpm,
        minBpm = minBpm,
        profileSnapshot = FrozenProfileSnapshot(
            userAge = userAge,
            restingHr = restingHr,
            calculatedHrmax = calculatedHrmax,
            calculationMode = calculationMode
        ),
        zoneBreakdowns = zoneBreakdowns
    )
}
