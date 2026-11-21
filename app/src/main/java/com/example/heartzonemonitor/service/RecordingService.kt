package com.example.heartzonemonitor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.heartzonemonitor.R
import com.example.heartzonemonitor.data.session.SessionRepository
import com.example.heartzonemonitor.data.session.SessionUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

/**
 * Foreground Service (`connectedDevice`) anchoring an active Session in memory and holding a
 * `PowerManager.PARTIAL_WAKE_LOCK` exclusively while in [SessionUiState.Recording].
 */
class RecordingService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)

    private var observerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var hasObservedActiveSession: Boolean = false
    private var isForegroundActive: Boolean = false
    private var lastForegroundStateClass: KClass<out SessionUiState>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel(this)
        val repository = sessionRepositoryProvider()
        val initialState = repository?.uiState?.value ?: SessionUiState.Idle()
        enterForeground(initialState)

        if (repository == null ||
            initialState is SessionUiState.Completed ||
            initialState is SessionUiState.Discarded
        ) {
            stopRecordingService()
            return START_NOT_STICKY
        }

        observerJob?.cancel()
        hasObservedActiveSession = false
        observerJob = serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            repository.uiState.collect { state ->
                handleUiState(state)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observerJob?.cancel()
        observerJob = null
        serviceJob.cancel()
        releaseWakeLock()
        wakeLock = null
        isForegroundActive = false
        lastForegroundStateClass = null
        hasObservedActiveSession = false
        super.onDestroy()
    }

    private fun handleUiState(state: SessionUiState) {
        when (state) {
            is SessionUiState.Recording -> {
                hasObservedActiveSession = true
                if (!isForegroundActive || lastForegroundStateClass != SessionUiState.Recording::class) {
                    enterForeground(state)
                }
                acquireWakeLock()
            }

            is SessionUiState.Paused -> {
                hasObservedActiveSession = true
                if (!isForegroundActive || lastForegroundStateClass != SessionUiState.Paused::class) {
                    enterForeground(state)
                }
                releaseWakeLock()
            }

            is SessionUiState.Completed,
            is SessionUiState.Discarded -> {
                stopRecordingService()
            }

            is SessionUiState.Idle -> {
                releaseWakeLock()
                if (hasObservedActiveSession) {
                    stopRecordingService()
                }
            }
        }
    }

    private fun enterForeground(state: SessionUiState) {
        val notification = notificationBuilderProvider(this, state)
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        isForegroundActive = true
        lastForegroundStateClass = state::class
    }

    private fun acquireWakeLock() {
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            ?.apply { setReferenceCounted(false) }
            ?.also { wakeLock = it }
            ?: return
        if (!lock.isHeld) {
            lock.acquire(MAX_WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) {
            lock.release()
        }
    }

    private fun stopRecordingService() {
        releaseWakeLock()
        observerJob?.cancel()
        observerJob = null
        hasObservedActiveSession = false
        if (isForegroundActive) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForegroundActive = false
            lastForegroundStateClass = null
        }
        stopSelf()
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "recording_channel"
        const val NOTIFICATION_ID = 1001
        const val WAKE_LOCK_TAG = "HeartZoneMonitor::RecordingWakeLock"
        const val MAX_WAKE_LOCK_TIMEOUT_MS = 12L * 60L * 60L * 1000L
        const val ACTION_START_RECORDING = "com.example.heartzonemonitor.action.START_RECORDING"

        private const val DEFAULT_CHANNEL_NAME = "Session Recording"
        private const val DEFAULT_CHANNEL_DESCRIPTION =
            "Ongoing Heart Rate Monitor session status"

        @Volatile
        internal var sessionRepositoryProvider: () -> SessionRepository? = {
            SessionRepository.getInstanceOrNull()
        }

        @Volatile
        internal var notificationBuilderProvider: (Context, SessionUiState) -> Notification =
            ::buildDefaultNotification

        fun createStartIntent(context: Context): Intent =
            Intent(context, RecordingService::class.java).apply {
                action = ACTION_START_RECORDING
            }

        internal fun resetTestHooks() {
            sessionRepositoryProvider = { SessionRepository.getInstanceOrNull() }
            notificationBuilderProvider = ::buildDefaultNotification
        }

        internal fun ensureNotificationChannel(context: Context) {
            val notificationManager =
                context.getSystemService(NotificationManager::class.java) ?: return
            if (notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    DEFAULT_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = DEFAULT_CHANNEL_DESCRIPTION
                    setShowBadge(false)
                }
                notificationManager.createNotificationChannel(channel)
            }
        }

        internal fun buildDefaultNotification(
            context: Context,
            state: SessionUiState
        ): Notification {
            ensureNotificationChannel(context)
            val (title, contentText) = when (state) {
                is SessionUiState.Recording ->
                    "Session Recording" to "Heart Rate Monitor session active"
                is SessionUiState.Paused ->
                    "Session Paused" to "Heart Rate Monitor session paused"
                is SessionUiState.Idle,
                is SessionUiState.Completed,
                is SessionUiState.Discarded ->
                    "Session Recording" to "Preparing Heart Rate Monitor session"
            }
            return NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(contentText)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}
