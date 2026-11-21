package com.example.heartzonemonitor.service

import android.content.Context
import androidx.core.content.ContextCompat
import com.example.heartzonemonitor.data.session.SessionRepository
import com.example.heartzonemonitor.data.session.SessionUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Abstraction for launching [RecordingService] so lifecycle coordination can be tested cleanly
 * without binding directly to Android component creation.
 */
fun interface RecordingServiceLauncher {
    fun startRecordingService()
}

/**
 * Default [RecordingServiceLauncher] delegating to [ContextCompat.startForegroundService].
 */
class AndroidRecordingServiceLauncher(
    private val context: Context
) : RecordingServiceLauncher {

    private val appContext: Context = context.applicationContext ?: context

    override fun startRecordingService() {
        ContextCompat.startForegroundService(
            appContext,
            RecordingService.createStartIntent(appContext)
        )
    }
}

/**
 * Observes [SessionRepository.uiState] and launches [RecordingService] once upon entry into an
 * active Session ([SessionUiState.Recording]), avoiding redundant service restarts across
 * `Recording <-> Paused` transitions while re-arming for subsequent Sessions.
 */
class RecordingServiceCoordinator(
    private val sessionRepository: SessionRepository,
    private val launcher: RecordingServiceLauncher,
    scope: CoroutineScope
) {
    private val coordinatorJob = SupervisorJob(scope.coroutineContext[Job])
    private val coordinatorScope = CoroutineScope(scope.coroutineContext + coordinatorJob)

    @Volatile
    private var wasActive: Boolean = false

    init {
        coordinatorScope.launch(start = CoroutineStart.UNDISPATCHED) {
            sessionRepository.uiState.collect { state ->
                when (state) {
                    is SessionUiState.Recording -> {
                        if (!wasActive) {
                            wasActive = true
                            launcher.startRecordingService()
                        }
                    }

                    is SessionUiState.Paused -> {
                        // Keep active flag armed across Recording <-> Paused transitions.
                    }

                    is SessionUiState.Completed,
                    is SessionUiState.Discarded,
                    is SessionUiState.Idle -> {
                        wasActive = false
                    }
                }
            }
        }
    }

    /**
     * Stops observing [SessionRepository.uiState].
     */
    fun close() {
        coordinatorJob.cancel()
    }
}
