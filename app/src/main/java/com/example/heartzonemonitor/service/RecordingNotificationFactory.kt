package com.example.heartzonemonitor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.heartzonemonitor.MainActivity
import com.example.heartzonemonitor.R
import com.example.heartzonemonitor.data.session.PauseReason
import com.example.heartzonemonitor.data.session.SessionUiState
import com.example.heartzonemonitor.data.timing.AndroidSessionClock
import com.example.heartzonemonitor.domain.model.HeartZone
import com.example.heartzonemonitor.domain.timing.SessionClock
import java.util.Locale

/**
 * Constructs the ongoing low-importance Session notification displayed by [RecordingService],
 * including live BPM and [HeartZone] titles, native system chronometer seeding for
 * [SessionUiState.Recording], frozen Effective Duration text for [SessionUiState.Paused],
 * interactive Session control actions, and a deep-link content click [PendingIntent].
 */
class RecordingNotificationFactory(
    private val clock: SessionClock = AndroidSessionClock
) {
    /**
     * Builds the foreground notification representing [state].
     */
    fun build(context: Context, state: SessionUiState): Notification {
        ensureNotificationChannel(context)
        val builder = NotificationCompat.Builder(context, RecordingService.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(formatTitle(state))
            .setContentText(formatContentText(state))
            .setContentIntent(createContentPendingIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        when (state) {
            is SessionUiState.Recording -> {
                val baseEpochMs = computeChronometerBaseEpochMs(
                    effectiveDurationMs = state.effectiveDurationMs,
                    currentEpochMs = clock.currentEpochMs()
                )
                builder
                    .setUsesChronometer(true)
                    .setShowWhen(true)
                    .setWhen(baseEpochMs)
                    .addAction(
                        0,
                        ACTION_LABEL_PAUSE,
                        createServiceActionPendingIntent(
                            context = context,
                            requestCode = REQUEST_CODE_PAUSE,
                            intent = RecordingService.createPauseIntent(context)
                        )
                    )
                    .addAction(
                        0,
                        ACTION_LABEL_STOP_AND_SAVE,
                        createServiceActionPendingIntent(
                            context = context,
                            requestCode = REQUEST_CODE_STOP,
                            intent = RecordingService.createStopIntent(context)
                        )
                    )
            }

            is SessionUiState.Paused -> {
                builder
                    .setUsesChronometer(false)
                    .setShowWhen(false)
                    .addAction(
                        0,
                        ACTION_LABEL_RESUME,
                        createServiceActionPendingIntent(
                            context = context,
                            requestCode = REQUEST_CODE_RESUME,
                            intent = RecordingService.createResumeIntent(context)
                        )
                    )
                    .addAction(
                        0,
                        ACTION_LABEL_STOP_AND_SAVE,
                        createServiceActionPendingIntent(
                            context = context,
                            requestCode = REQUEST_CODE_STOP,
                            intent = RecordingService.createStopIntent(context)
                        )
                    )
            }

            is SessionUiState.Idle,
            is SessionUiState.Completed,
            is SessionUiState.Discarded -> {
                builder
                    .setUsesChronometer(false)
                    .setShowWhen(false)
            }
        }

        return builder.build()
    }

    private fun createContentPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_CODE_CONTENT,
            MainActivity.createLiveSessionIntent(context),
            PENDING_INTENT_FLAGS
        )

    private fun createServiceActionPendingIntent(
        context: Context,
        requestCode: Int,
        intent: Intent
    ): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            intent,
            PENDING_INTENT_FLAGS
        )

    companion object {
        const val ACTION_LABEL_PAUSE = "Pause"
        const val ACTION_LABEL_RESUME = "Resume"
        const val ACTION_LABEL_STOP_AND_SAVE = "Stop & Save"

        internal const val REQUEST_CODE_CONTENT = 100
        internal const val REQUEST_CODE_PAUSE = 101
        internal const val REQUEST_CODE_RESUME = 102
        internal const val REQUEST_CODE_STOP = 103

        private const val DEFAULT_CHANNEL_NAME = "Session Recording"
        private const val DEFAULT_CHANNEL_DESCRIPTION =
            "Ongoing Heart Rate Monitor session status"

        private const val TITLE_RECORDING_FALLBACK = "Session Recording"
        private const val TITLE_PAUSED_FALLBACK = "Session Paused"

        private const val CONTENT_RECORDING = "Heart Rate Monitor session recording"
        private const val CONTENT_MICRO_SESSION_PROMPT =
            "Short Session — tap to save or discard"
        private const val CONTENT_PREPARING = "Preparing Heart Rate Monitor session"

        private const val PENDING_INTENT_FLAGS =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        /**
         * Ensures the low-importance [RecordingService.NOTIFICATION_CHANNEL_ID] channel exists.
         */
        fun ensureNotificationChannel(context: Context) {
            val notificationManager =
                context.getSystemService(NotificationManager::class.java) ?: return
            if (notificationManager.getNotificationChannel(RecordingService.NOTIFICATION_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    RecordingService.NOTIFICATION_CHANNEL_ID,
                    DEFAULT_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = DEFAULT_CHANNEL_DESCRIPTION
                    setShowBadge(false)
                }
                notificationManager.createNotificationChannel(channel)
            }
        }

        /**
         * Formats the notification title displaying current BPM and [HeartZone.zoneName], handling
         * missing telemetry and paused states gracefully.
         */
        fun formatTitle(state: SessionUiState): String = when (state) {
            is SessionUiState.Recording -> {
                formatTelemetry(state.currentBpm, state.currentZone) ?: TITLE_RECORDING_FALLBACK
            }

            is SessionUiState.Paused -> {
                val telemetry = formatTelemetry(state.currentBpm, state.currentZone)
                if (telemetry != null) "Paused • $telemetry" else TITLE_PAUSED_FALLBACK
            }

            is SessionUiState.Idle,
            is SessionUiState.Completed,
            is SessionUiState.Discarded -> TITLE_RECORDING_FALLBACK
        }

        /**
         * Formats the notification content text, surfacing frozen Effective Duration while paused
         * and prompt guidance when a micro-session discard confirmation is pending.
         */
        fun formatContentText(state: SessionUiState): String = when (state) {
            is SessionUiState.Recording -> {
                if (state.pendingMicroSessionDiscardPrompt) {
                    CONTENT_MICRO_SESSION_PROMPT
                } else {
                    CONTENT_RECORDING
                }
            }

            is SessionUiState.Paused -> {
                val duration = formatEffectiveDuration(state.effectiveDurationMs)
                if (state.pendingMicroSessionDiscardPrompt) {
                    "Effective Duration $duration • Tap to save or discard short Session"
                } else {
                    when (state.reason) {
                        PauseReason.USER_MANUAL ->
                            "Paused • Effective Duration $duration"
                        PauseReason.BLE_CONNECTION_LOST ->
                            "HRM disconnected • Effective Duration $duration"
                        PauseReason.SENSOR_CONTACT_LOST ->
                            "Sensor contact lost • Effective Duration $duration"
                    }
                }
            }

            is SessionUiState.Idle,
            is SessionUiState.Completed,
            is SessionUiState.Discarded -> CONTENT_PREPARING
        }

        /**
         * Computes the epoch baseline passed to `NotificationCompat.Builder.setWhen(...)` so the
         * native OS chronometer counts forward from the Session's monotonic Effective Duration.
         */
        fun computeChronometerBaseEpochMs(
            effectiveDurationMs: Long,
            currentEpochMs: Long
        ): Long = (currentEpochMs - effectiveDurationMs.coerceAtLeast(0L)).coerceAtLeast(0L)

        /**
         * Formats a duration in milliseconds as `MM:SS` (below one hour) or `HH:MM:SS`.
         */
        fun formatEffectiveDuration(durationMs: Long): String {
            val totalSeconds = durationMs.coerceAtLeast(0L) / 1_000L
            val hours = totalSeconds / 3_600L
            val minutes = (totalSeconds % 3_600L) / 60L
            val seconds = totalSeconds % 60L
            return if (hours > 0L) {
                String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%02d:%02d", minutes, seconds)
            }
        }

        private fun formatTelemetry(bpm: Int?, zone: HeartZone?): String? = when {
            bpm != null && zone != null ->
                "$bpm BPM • Zone ${zone.zoneIndex} (${zone.zoneName})"
            bpm != null ->
                "$bpm BPM"
            zone != null ->
                "-- BPM • Zone ${zone.zoneIndex} (${zone.zoneName})"
            else -> null
        }
    }
}
