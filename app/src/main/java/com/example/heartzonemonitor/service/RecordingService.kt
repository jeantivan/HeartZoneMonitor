package com.example.heartzonemonitor.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import com.example.heartzonemonitor.data.session.PauseReason
import com.example.heartzonemonitor.data.session.SessionRepository
import com.example.heartzonemonitor.data.session.SessionUiState
import com.example.heartzonemonitor.data.timing.AndroidSessionClock
import com.example.heartzonemonitor.domain.timing.SessionClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Foreground Service (`connectedDevice`) anchoring an active Session in memory, holding a
 * `PowerManager.PARTIAL_WAKE_LOCK` exclusively while in [SessionUiState.Recording], rendering the
 * debounced chronometer notification, and dispatching interactive notification actions directly to
 * [SessionRepository].
 */
class RecordingService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)

    private var observerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var hasObservedActiveSession: Boolean = false
    private var isForegroundActive: Boolean = false
    private var notificationDebouncer: RecordingNotificationDebouncer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel(this)
        val repository = sessionRepositoryProvider()
        val action = intent?.action

        if (isForegroundActive && observerJob?.isActive == true) {
            val currentState = repository?.uiState?.value
            if (repository == null ||
                currentState is SessionUiState.Completed ||
                currentState is SessionUiState.Discarded
            ) {
                stopRecordingService()
                return START_NOT_STICKY
            }
            dispatchActionIfNeeded(repository, action)
            return START_NOT_STICKY
        }

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
        dispatchActionIfNeeded(repository, action)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observerJob?.cancel()
        observerJob = null
        serviceJob.cancel()
        releaseWakeLock()
        wakeLock = null
        notificationDebouncer?.reset()
        notificationDebouncer = null
        isForegroundActive = false
        hasObservedActiveSession = false
        super.onDestroy()
    }

    private fun handleUiState(state: SessionUiState) {
        when (state) {
            is SessionUiState.Recording -> {
                hasObservedActiveSession = true
                updateForegroundOrNotify(state)
                acquireWakeLock()
            }

            is SessionUiState.Paused -> {
                hasObservedActiveSession = true
                updateForegroundOrNotify(state)
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

    private fun dispatchActionIfNeeded(repository: SessionRepository, action: String?) {
        when (action) {
            ACTION_PAUSE_SESSION -> {
                serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    repository.pauseSession(PauseReason.USER_MANUAL)
                }
            }

            ACTION_RESUME_SESSION -> {
                serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    repository.resumeSession()
                }
            }

            ACTION_STOP_SESSION -> {
                serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    repository.stopSession()
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
        getOrCreateDebouncer().recordEmitted(state)
    }

    private fun updateForegroundOrNotify(state: SessionUiState) {
        if (!isForegroundActive) {
            enterForeground(state)
            return
        }
        if (getOrCreateDebouncer().consumeIfShouldEmit(state)) {
            val notification = notificationBuilderProvider(this, state)
            notificationPoster(this, NOTIFICATION_ID, notification)
        }
    }

    private fun getOrCreateDebouncer(): RecordingNotificationDebouncer =
        notificationDebouncer ?: RecordingNotificationDebouncer(sessionClockProvider())
            .also { notificationDebouncer = it }

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
        notificationDebouncer?.reset()
        notificationDebouncer = null
        hasObservedActiveSession = false
        if (isForegroundActive) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
            isForegroundActive = false
        }
        stopSelf()
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "recording_channel"
        const val NOTIFICATION_ID = 1001
        const val WAKE_LOCK_TAG = "HeartZoneMonitor::RecordingWakeLock"
        const val MAX_WAKE_LOCK_TIMEOUT_MS = 12L * 60L * 60L * 1000L
        const val ACTION_START_RECORDING = "com.example.heartzonemonitor.action.START_RECORDING"
        const val ACTION_PAUSE_SESSION = "com.example.heartzonemonitor.action.PAUSE_SESSION"
        const val ACTION_RESUME_SESSION = "com.example.heartzonemonitor.action.RESUME_SESSION"
        const val ACTION_STOP_SESSION = "com.example.heartzonemonitor.action.STOP_SESSION"

        @Volatile
        internal var sessionRepositoryProvider: () -> SessionRepository? = {
            SessionRepository.getInstanceOrNull()
        }

        @Volatile
        internal var sessionClockProvider: () -> SessionClock = { AndroidSessionClock }

        @Volatile
        internal var notificationBuilderProvider: (Context, SessionUiState) -> Notification =
            ::buildDefaultNotification

        @Volatile
        internal var notificationPoster: (Context, Int, Notification) -> Unit =
            ::postNotificationToManager

        fun createStartIntent(context: Context): Intent =
            Intent(context, RecordingService::class.java).apply {
                action = ACTION_START_RECORDING
            }

        fun createPauseIntent(context: Context): Intent =
            Intent(context, RecordingService::class.java).apply {
                action = ACTION_PAUSE_SESSION
            }

        fun createResumeIntent(context: Context): Intent =
            Intent(context, RecordingService::class.java).apply {
                action = ACTION_RESUME_SESSION
            }

        fun createStopIntent(context: Context): Intent =
            Intent(context, RecordingService::class.java).apply {
                action = ACTION_STOP_SESSION
            }

        internal fun resetTestHooks() {
            sessionRepositoryProvider = { SessionRepository.getInstanceOrNull() }
            sessionClockProvider = { AndroidSessionClock }
            notificationBuilderProvider = ::buildDefaultNotification
            notificationPoster = ::postNotificationToManager
        }

        internal fun ensureNotificationChannel(context: Context) {
            RecordingNotificationFactory.ensureNotificationChannel(context)
        }

        internal fun buildDefaultNotification(
            context: Context,
            state: SessionUiState
        ): Notification =
            RecordingNotificationFactory(sessionClockProvider()).build(context, state)

        private fun postNotificationToManager(
            context: Context,
            notificationId: Int,
            notification: Notification
        ) {
            val notificationManager =
                context.getSystemService(NotificationManager::class.java) ?: return
            try {
                notificationManager.notify(notificationId, notification)
            } catch (_: SecurityException) {
                // Foreground notification visibility is OS-managed; fail safely if permission revoked.
            }
        }
    }
}
