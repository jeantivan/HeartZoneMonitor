package com.example.heartzonemonitor.data.session

import com.example.heartzonemonitor.data.ble.BleCharacteristicNotification
import com.example.heartzonemonitor.data.ble.BleConnectionState
import com.example.heartzonemonitor.data.ble.BleTransport
import com.example.heartzonemonitor.data.ble.BleUuids
import com.example.heartzonemonitor.data.ble.HeartRateMeasurementParser
import com.example.heartzonemonitor.data.ble.HrmGattClient
import com.example.heartzonemonitor.data.local.HeartRateSampleBuffer
import com.example.heartzonemonitor.data.local.dao.HeartRateSampleDao
import com.example.heartzonemonitor.data.local.dao.RememberedHrmDao
import com.example.heartzonemonitor.data.local.dao.SessionDao
import com.example.heartzonemonitor.data.local.dao.UserProfileDao
import com.example.heartzonemonitor.data.local.entity.HeartRateSampleEntity
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import com.example.heartzonemonitor.data.local.entity.SessionEntity
import com.example.heartzonemonitor.data.local.entity.SessionStatus
import com.example.heartzonemonitor.data.local.entity.UserProfileEntity
import com.example.heartzonemonitor.data.timing.AndroidSessionClock
import com.example.heartzonemonitor.domain.calculator.SampleMetricPoint
import com.example.heartzonemonitor.domain.calculator.SessionMetricsCalculator
import com.example.heartzonemonitor.domain.calculator.ZoneCalculator
import com.example.heartzonemonitor.domain.model.HeartZone
import com.example.heartzonemonitor.domain.model.ZoneCalculationResult
import com.example.heartzonemonitor.domain.timing.DurationSnapshot
import com.example.heartzonemonitor.domain.timing.DurationTrackingState
import com.example.heartzonemonitor.domain.timing.MonotonicDurationEngine
import com.example.heartzonemonitor.domain.timing.SessionClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Year
import java.util.UUID

/**
 * Single source of truth repository managing the Session recording state machine, Active HRM
 * connection and telemetry gating, Heart Zone tagging, buffered Heart Rate Sample persistence,
 * monotonic duration tracking, micro-session guard, and Session summary finalization.
 */
class SessionRepository(
    private val bleTransport: BleTransport,
    private val rememberedHrmDao: RememberedHrmDao,
    private val userProfileDao: UserProfileDao,
    private val sessionDao: SessionDao,
    private val sampleDao: HeartRateSampleDao,
    scope: CoroutineScope,
    hrmGattClient: HrmGattClient? = null,
    private val clock: SessionClock = AndroidSessionClock,
    private val durationEngine: MonotonicDurationEngine = MonotonicDurationEngine(clock),
    sampleBuffer: HeartRateSampleBuffer? = null,
    private val zoneCalculator: ZoneCalculator = ZoneCalculator(),
    private val metricsCalculator: SessionMetricsCalculator = SessionMetricsCalculator(),
    private val currentYearProvider: () -> Int = { Year.now().value },
    private val sessionIdGenerator: () -> String = { UUID.randomUUID().toString() }
) {
    private val repositoryJob = SupervisorJob(scope.coroutineContext[Job])
    private val repositoryScope = CoroutineScope(scope.coroutineContext + repositoryJob)

    private val ownsGattClient = hrmGattClient == null
    private val gattClient: HrmGattClient = hrmGattClient ?: HrmGattClient(
        bleTransport = bleTransport,
        scope = repositoryScope,
        onBatteryLevelUpdated = { macAddress, level ->
            rememberedHrmDao.updateBatteryLevel(macAddress, level)
        }
    )

    private val sampleBuffer: HeartRateSampleBuffer = sampleBuffer ?: HeartRateSampleBuffer(
        sampleDao = sampleDao,
        scope = repositoryScope
    )

    private val transitionMutex = Mutex()
    private val stateLock = Any()

    private var currentActiveHrm: RememberedHrmEntity? = null
    private var currentUserProfile: UserProfileEntity? = null
    private var currentConnectionState: BleConnectionState = gattClient.connectionState.value
    private var liveBatteryLevel: Int? = null
    private var latestBpm: Int? = null
    private var latestSensorContact: Boolean? = null
    private var hasValidTelemetry: Boolean = false
    private var telemetryMacAddress: String? = null
    private var activeSession: ActiveSessionContext? = null

    private val _uiState = MutableStateFlow<SessionUiState>(
        SessionUiState.Idle(connectionState = currentConnectionState)
    )
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    /**
     * Returns `true` when a Session is currently open in [SessionUiState.Recording] or
     * [SessionUiState.Paused], enforcing Active HRM switch lockout and lifecycle guards.
     */
    val isSessionActive: Boolean
        get() = when (_uiState.value) {
            is SessionUiState.Recording, is SessionUiState.Paused -> true
            is SessionUiState.Idle, is SessionUiState.Completed, is SessionUiState.Discarded -> false
        }

    /**
     * Reactive stream emitting `true` while a Session is [SessionUiState.Recording] or
     * [SessionUiState.Paused], and `false` otherwise.
     */
    val isSessionActiveFlow: Flow<Boolean> =
        uiState
            .map { state -> state is SessionUiState.Recording || state is SessionUiState.Paused }
            .distinctUntilChanged()

    init {
        repositoryScope.launch(start = CoroutineStart.UNDISPATCHED) {
            userProfileDao.getProfileFlow().collect { profile ->
                onUserProfileUpdated(profile)
            }
        }
        repositoryScope.launch(start = CoroutineStart.UNDISPATCHED) {
            rememberedHrmDao.observeActiveHrm().collect { activeHrm ->
                onActiveHrmUpdated(activeHrm)
            }
        }
        repositoryScope.launch(start = CoroutineStart.UNDISPATCHED) {
            gattClient.connectionState.collect { connectionState ->
                onConnectionStateUpdated(connectionState)
            }
        }
        repositoryScope.launch(start = CoroutineStart.UNDISPATCHED) {
            gattClient.batteryLevel.collect { batteryLevel ->
                onBatteryLevelUpdated(batteryLevel)
            }
        }
        repositoryScope.launch(start = CoroutineStart.UNDISPATCHED) {
            bleTransport.characteristicNotifications.collect { notification ->
                handleCharacteristicNotification(notification)
            }
        }
    }

    /**
     * Starts a new Session if no Session is active, a valid User Profile exists, an Active HRM
     * is designated and in [BleConnectionState.Connected], and valid heart rate telemetry has been
     * received from that Active HRM. Performs zero database writes if any precondition fails.
     */
    suspend fun startSession(): StartSessionResult = transitionMutex.withLock {
        synchronized(stateLock) {
            if (activeSession != null) {
                return@withLock StartSessionResult.Rejected(
                    StartSessionRejectionReason.SESSION_ALREADY_ACTIVE
                )
            }
        }

        val profile = userProfileDao.getProfile()
        val activeHrm = rememberedHrmDao.getActiveHrm()
        val connectionState = bleTransport.connectionState.value

        val rejectionReason = synchronized(stateLock) {
            currentUserProfile = profile
            val previousMac = currentActiveHrm?.macAddress
            currentActiveHrm = activeHrm
            if (previousMac != activeHrm?.macAddress) {
                clearLiveTelemetryLocked()
                liveBatteryLevel = null
            }

            currentConnectionState = connectionState
            val isConnectedToActiveHrm = activeHrm != null &&
                connectionState is BleConnectionState.Connected &&
                connectionState.macAddress == activeHrm.macAddress
            if (!isConnectedToActiveHrm) {
                clearLiveTelemetryLocked()
                liveBatteryLevel = null
            }

            when {
                profile == null -> StartSessionRejectionReason.USER_PROFILE_MISSING
                activeHrm == null -> StartSessionRejectionReason.NO_ACTIVE_HRM
                !isConnectedToActiveHrm -> StartSessionRejectionReason.HRM_NOT_CONNECTED
                !isLiveTelemetryValidForLocked(activeHrm.macAddress) ->
                    StartSessionRejectionReason.TELEMETRY_UNAVAILABLE
                else -> null
            }.also { reason ->
                if (reason != null && _uiState.value is SessionUiState.Idle) {
                    publishIdleStateLocked()
                }
            }
        }

        if (rejectionReason != null) {
            return@withLock StartSessionResult.Rejected(rejectionReason)
        }

        val resolvedProfile = requireNotNull(profile)
        val resolvedHrm = requireNotNull(activeHrm)
        val userAge = (currentYearProvider() - resolvedProfile.birthYear)
            .coerceIn(MIN_USER_AGE, MAX_USER_AGE)
        val zoneCalculation = try {
            zoneCalculator.calculateZones(
                age = userAge,
                restingHr = resolvedProfile.restingHr,
                customHrmax = resolvedProfile.customHrmax
            )
        } catch (_: IllegalArgumentException) {
            synchronized(stateLock) {
                if (_uiState.value is SessionUiState.Idle) {
                    publishIdleStateLocked()
                }
            }
            return@withLock StartSessionResult.Rejected(
                StartSessionRejectionReason.INVALID_USER_PROFILE
            )
        }

        if (durationEngine.currentState() != DurationTrackingState.IDLE) {
            durationEngine.reset()
        }
        val startSnapshot = durationEngine.start()
        val startEpochMs = requireNotNull(startSnapshot.startEpochMs)
        val sessionId = sessionIdGenerator()

        val initialSession = SessionEntity(
            id = sessionId,
            startEpochMs = startEpochMs,
            endEpochMs = null,
            elapsedDurationMs = startSnapshot.elapsedDurationMs,
            effectiveDurationMs = startSnapshot.effectiveDurationMs,
            pausedDurationMs = startSnapshot.pausedDurationMs,
            status = SessionStatus.RECORDING,
            hrmMacAddress = resolvedHrm.macAddress,
            userAge = userAge,
            calculatedHrmax = zoneCalculation.hrMax,
            restingHr = resolvedProfile.restingHr,
            calculationMode = zoneCalculation.calculationMode
        )

        try {
            sessionDao.insertSession(initialSession)
        } catch (t: Throwable) {
            durationEngine.reset()
            throw t
        }

        sampleBuffer.startPeriodicFlush()

        val sessionContext = ActiveSessionContext(
            sessionId = sessionId,
            activeHrm = resolvedHrm,
            startEpochMs = startEpochMs,
            zoneCalculation = zoneCalculation,
            sessionEntity = initialSession
        )

        synchronized(stateLock) {
            activeSession = sessionContext
            publishActiveStateLocked(sessionContext, startSnapshot)
        }

        StartSessionResult.Started(sessionId)
    }

    /**
     * Pauses an active [SessionUiState.Recording] Session with the given [reason], immediately
     * flushing buffered Heart Rate Samples and updating the Session status to [SessionStatus.PAUSED].
     */
    suspend fun pauseSession(reason: PauseReason = PauseReason.USER_MANUAL): Boolean =
        transitionMutex.withLock {
            val session = synchronized(stateLock) {
                val current = activeSession
                if (current == null || current.pauseReason != null) null else current
            } ?: return@withLock false

            val snapshot = durationEngine.pause()
            sampleBuffer.onSessionPaused()

            val updatedEntity = session.sessionEntity.copy(
                status = SessionStatus.PAUSED,
                elapsedDurationMs = snapshot.elapsedDurationMs,
                effectiveDurationMs = snapshot.effectiveDurationMs,
                pausedDurationMs = snapshot.pausedDurationMs
            )
            sessionDao.updateSession(updatedEntity)

            synchronized(stateLock) {
                session.sessionEntity = updatedEntity
                session.pauseReason = reason
                publishActiveStateLocked(session, snapshot)
            }
            true
        }

    /**
     * Resumes a [SessionUiState.Paused] Session back to [SessionUiState.Recording], clearing any
     * pending micro-session discard prompt and updating the Session status to [SessionStatus.RECORDING].
     */
    suspend fun resumeSession(): Boolean = transitionMutex.withLock {
        val session = synchronized(stateLock) {
            val current = activeSession
            if (current == null || current.pauseReason == null) null else current
        } ?: return@withLock false

        val snapshot = durationEngine.resume()
        val updatedEntity = session.sessionEntity.copy(
            status = SessionStatus.RECORDING,
            elapsedDurationMs = snapshot.elapsedDurationMs,
            effectiveDurationMs = snapshot.effectiveDurationMs,
            pausedDurationMs = snapshot.pausedDurationMs
        )
        sessionDao.updateSession(updatedEntity)

        synchronized(stateLock) {
            session.sessionEntity = updatedEntity
            session.pauseReason = null
            session.pendingMicroSessionDiscardPrompt = false
            publishActiveStateLocked(session, snapshot)
        }
        true
    }

    /**
     * Stops and finalizes an active [SessionUiState.Recording] or [SessionUiState.Paused] Session.
     *
     * Unless [forceSave] is `true`, stopping a micro-session (0 recorded Heart Rate Samples or
     * Effective Duration below [MICRO_SESSION_THRESHOLD_MS]) sets `pendingMicroSessionDiscardPrompt`
     * on the active state and returns [StopSessionOutcome.MicroSessionDiscardPromptRequired]
     * without finalizing the Session.
     */
    suspend fun stopSession(forceSave: Boolean = false): StopSessionOutcome =
        transitionMutex.withLock {
            val session = synchronized(stateLock) { activeSession }
                ?: return@withLock StopSessionOutcome.Ignored

            val preStopSnapshot = durationEngine.snapshot()
            val sampleCount = synchronized(stateLock) { session.sampleCount }
            val isMicroSession = sampleCount == 0 ||
                preStopSnapshot.effectiveDurationMs < MICRO_SESSION_THRESHOLD_MS

            if (!forceSave && isMicroSession) {
                synchronized(stateLock) {
                    session.pendingMicroSessionDiscardPrompt = true
                    publishActiveStateLocked(session, preStopSnapshot)
                }
                return@withLock StopSessionOutcome.MicroSessionDiscardPromptRequired(
                    sessionId = session.sessionId,
                    effectiveDurationMs = preStopSnapshot.effectiveDurationMs,
                    sampleCount = sampleCount
                )
            }

            val finalSnapshot = durationEngine.stop()
            sampleBuffer.onSessionStopped()

            val persistedSamples = sampleDao.getSamplesForSession(session.sessionId)
            val summaryMetrics = metricsCalculator.calculate(
                persistedSamples.map { sample ->
                    SampleMetricPoint(
                        timestampEpochMs = sample.timestampEpochMs,
                        bpm = sample.bpm,
                        zoneIndex = sample.zoneIndex
                    )
                }
            )

            val completedEntity = session.sessionEntity.copy(
                endEpochMs = finalSnapshot.endEpochMs ?: clock.currentEpochMs(),
                elapsedDurationMs = finalSnapshot.elapsedDurationMs,
                effectiveDurationMs = finalSnapshot.effectiveDurationMs,
                pausedDurationMs = finalSnapshot.pausedDurationMs,
                status = SessionStatus.COMPLETED,
                avgBpm = summaryMetrics.avgBpm,
                maxBpm = summaryMetrics.maxBpm,
                minBpm = summaryMetrics.minBpm,
                zone1DurationMs = summaryMetrics.zone1DurationMs,
                zone2DurationMs = summaryMetrics.zone2DurationMs,
                zone3DurationMs = summaryMetrics.zone3DurationMs,
                zone4DurationMs = summaryMetrics.zone4DurationMs,
                zone5DurationMs = summaryMetrics.zone5DurationMs
            )
            sessionDao.updateSession(completedEntity)

            synchronized(stateLock) {
                activeSession = null
                _uiState.value = SessionUiState.Completed(completedEntity)
            }

            StopSessionOutcome.Completed(completedEntity)
        }

    /**
     * Explicitly finalizes and saves a micro-session after the user confirms saving despite the
     * micro-session guard prompt.
     */
    suspend fun confirmSaveMicroSession(): StopSessionOutcome = stopSession(forceSave = true)

    /**
     * Dismisses an active micro-session discard prompt while keeping the Session open in its
     * current [SessionUiState.Recording] or [SessionUiState.Paused] state.
     */
    fun dismissMicroSessionPrompt() {
        synchronized(stateLock) {
            val session = activeSession ?: return
            if (!session.pendingMicroSessionDiscardPrompt) return
            session.pendingMicroSessionDiscardPrompt = false
            publishActiveStateLocked(session)
        }
    }

    /**
     * Discards an active Session by clearing buffered Heart Rate Samples without flushing,
     * deleting the Session record (cascading any previously flushed samples), resetting monotonic
     * duration state, and emitting [SessionUiState.Discarded].
     */
    suspend fun discardSession(): Boolean = transitionMutex.withLock {
        val session = synchronized(stateLock) { activeSession } ?: return@withLock false

        sampleBuffer.discard()
        sessionDao.deleteSessionById(session.sessionId)
        durationEngine.reset()

        synchronized(stateLock) {
            activeSession = null
            _uiState.value = SessionUiState.Discarded(session.sessionId)
        }
        true
    }

    /**
     * Returns from a terminal state ([SessionUiState.Completed] or [SessionUiState.Discarded])
     * back to [SessionUiState.Idle] with current Active HRM connection and telemetry readiness.
     */
    fun resetToIdle() {
        synchronized(stateLock) {
            if (activeSession != null) return
            if (durationEngine.currentState() != DurationTrackingState.IDLE) {
                durationEngine.reset()
            }
            publishIdleStateLocked()
        }
    }

    /**
     * Refreshes monotonic Effective Duration, Paused Duration, and Elapsed Duration values in
     * [uiState] for an active Session.
     */
    fun refreshDurations() {
        synchronized(stateLock) {
            val session = activeSession ?: return
            publishActiveStateLocked(session)
        }
    }

    /**
     * Flushes currently buffered Heart Rate Samples when the application moves to the background.
     */
    suspend fun onAppBackgrounded() {
        transitionMutex.withLock {
            val hasActive = synchronized(stateLock) { activeSession != null }
            if (!hasActive) return
            sampleBuffer.onAppBackgrounded()
        }
    }

    /**
     * Cancels all internal collector and periodic flush coroutines owned by this repository.
     */
    fun close() {
        repositoryJob.cancel()
        if (ownsGattClient) {
            gattClient.close()
        }
    }

    private suspend fun handleCharacteristicNotification(notification: BleCharacteristicNotification) {
        if (notification.characteristicUuid != BleUuids.HEART_RATE_MEASUREMENT_CHAR_UUID) {
            return
        }
        val measurement = HeartRateMeasurementParser.parse(
            payload = notification.value,
            timestampEpochMs = notification.timestampEpochMs
        ) ?: return

        transitionMutex.withLock {
            val liveConnectionState = bleTransport.connectionState.value
            val isValidMeasurement = measurement.bpm in MIN_VALID_BPM..MAX_VALID_BPM &&
                measurement.sensorContact

            val sampleToEnqueue: HeartRateSampleEntity? = synchronized(stateLock) {
                val previousState = currentConnectionState
                currentConnectionState = liveConnectionState
                val session = activeSession
                val targetHrm = session?.activeHrm ?: currentActiveHrm
                if (targetHrm == null ||
                    liveConnectionState !is BleConnectionState.Connected ||
                    liveConnectionState.macAddress != targetHrm.macAddress
                ) {
                    if (previousState != liveConnectionState) {
                        clearLiveTelemetryLocked()
                        liveBatteryLevel = null
                        republishCurrentStateLocked()
                    }
                    return@synchronized null
                }
                if (notification.macAddress != targetHrm.macAddress) {
                    return@synchronized null
                }
                if (previousState != liveConnectionState) {
                    clearLiveTelemetryLocked()
                    liveBatteryLevel = null
                }

                latestSensorContact = measurement.sensorContact
                telemetryMacAddress = notification.macAddress
                if (isValidMeasurement) {
                    latestBpm = measurement.bpm
                    hasValidTelemetry = true
                } else {
                    hasValidTelemetry = false
                    if (measurement.bpm !in MIN_VALID_BPM..MAX_VALID_BPM) {
                        latestBpm = null
                    }
                }

                if (session != null && session.pauseReason == null && isValidMeasurement) {
                    val zone = session.zoneCalculation.getZoneForBpm(measurement.bpm)
                        ?: HeartZone.ZONE_1
                    val timestampEpochMs = measurement.timestampEpochMs
                        .takeIf { it > 0L } ?: clock.currentEpochMs()
                    HeartRateSampleEntity(
                        sessionId = session.sessionId,
                        timestampEpochMs = timestampEpochMs,
                        bpm = measurement.bpm,
                        zoneIndex = zone.zoneIndex,
                        sensorContact = measurement.sensorContact
                    )
                } else {
                    republishCurrentStateLocked()
                    null
                }
            }

            if (sampleToEnqueue != null) {
                sampleBuffer.enqueue(sampleToEnqueue)
                synchronized(stateLock) {
                    val session = activeSession
                    if (session != null && session.sessionId == sampleToEnqueue.sessionId) {
                        session.sampleCount += 1
                        publishActiveStateLocked(session)
                    }
                }
            }
        }
    }

    private fun onUserProfileUpdated(profile: UserProfileEntity?) {
        synchronized(stateLock) {
            currentUserProfile = profile
            if (activeSession == null && _uiState.value is SessionUiState.Idle) {
                publishIdleStateLocked()
            }
        }
    }

    private fun onActiveHrmUpdated(newActiveHrm: RememberedHrmEntity?) {
        synchronized(stateLock) {
            val previousMac = currentActiveHrm?.macAddress
            val newMac = newActiveHrm?.macAddress
            currentActiveHrm = newActiveHrm

            val session = activeSession
            if (session != null) {
                if (newActiveHrm != null && newActiveHrm.macAddress == session.activeHrm.macAddress) {
                    session.activeHrm = newActiveHrm
                    publishActiveStateLocked(session)
                }
                return
            }

            if (previousMac != newMac) {
                clearLiveTelemetryLocked()
                liveBatteryLevel = null
            }
            if (_uiState.value is SessionUiState.Idle) {
                publishIdleStateLocked()
            }
        }
    }

    private fun onConnectionStateUpdated(newState: BleConnectionState) {
        synchronized(stateLock) {
            val previousState = currentConnectionState
            currentConnectionState = newState
            val targetMac = activeSession?.activeHrm?.macAddress ?: currentActiveHrm?.macAddress
            val connectedToTarget = targetMac != null &&
                newState is BleConnectionState.Connected &&
                newState.macAddress == targetMac

            if (!connectedToTarget || previousState != newState) {
                clearLiveTelemetryLocked()
                liveBatteryLevel = null
            }
            republishCurrentStateLocked()
        }
    }

    private fun onBatteryLevelUpdated(level: Int?) {
        synchronized(stateLock) {
            val previousState = currentConnectionState
            val liveConnectionState = bleTransport.connectionState.value
            currentConnectionState = liveConnectionState
            val targetMac = activeSession?.activeHrm?.macAddress ?: currentActiveHrm?.macAddress
            val connectedToTarget = targetMac != null &&
                liveConnectionState is BleConnectionState.Connected &&
                liveConnectionState.macAddress == targetMac
            if (!connectedToTarget || previousState != liveConnectionState) {
                clearLiveTelemetryLocked()
            }
            liveBatteryLevel = if (connectedToTarget) level else null
            republishCurrentStateLocked()
        }
    }

    private fun clearLiveTelemetryLocked() {
        hasValidTelemetry = false
        latestBpm = null
        latestSensorContact = null
        telemetryMacAddress = null
    }

    private fun isLiveTelemetryValidForLocked(macAddress: String): Boolean {
        val bpm = latestBpm ?: return false
        return hasValidTelemetry &&
            telemetryMacAddress == macAddress &&
            bpm in MIN_VALID_BPM..MAX_VALID_BPM &&
            latestSensorContact == true
    }

    private fun resolveBatteryLevelLocked(hrm: RememberedHrmEntity?): Int? {
        if (hrm == null) return null
        val connectedToHrm = currentConnectionState is BleConnectionState.Connected &&
            (currentConnectionState as BleConnectionState.Connected).macAddress == hrm.macAddress
        return if (connectedToHrm) {
            liveBatteryLevel ?: hrm.batteryLevel
        } else {
            hrm.batteryLevel
        }
    }

    private fun computeZoneCalculationOrNull(profile: UserProfileEntity?): ZoneCalculationResult? {
        if (profile == null) return null
        val userAge = (currentYearProvider() - profile.birthYear)
            .coerceIn(MIN_USER_AGE, MAX_USER_AGE)
        return try {
            zoneCalculator.calculateZones(
                age = userAge,
                restingHr = profile.restingHr,
                customHrmax = profile.customHrmax
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun republishCurrentStateLocked() {
        val session = activeSession
        if (session != null) {
            publishActiveStateLocked(session)
        } else if (_uiState.value is SessionUiState.Idle) {
            publishIdleStateLocked()
        }
    }

    private fun publishIdleStateLocked() {
        val activeHrm = currentActiveHrm
        val connectionState = currentConnectionState
        val isConnectedToActiveHrm = activeHrm != null &&
            connectionState is BleConnectionState.Connected &&
            connectionState.macAddress == activeHrm.macAddress
        val telemetryValid = activeHrm != null &&
            isConnectedToActiveHrm &&
            isLiveTelemetryValidForLocked(activeHrm.macAddress)
        val zoneCalculation = computeZoneCalculationOrNull(currentUserProfile)
        val currentZone = if (telemetryValid && zoneCalculation != null) {
            latestBpm?.let { bpm -> zoneCalculation.getZoneForBpm(bpm) ?: HeartZone.ZONE_1 }
        } else {
            null
        }
        val canStart = zoneCalculation != null && isConnectedToActiveHrm && telemetryValid

        _uiState.value = SessionUiState.Idle(
            activeHrm = activeHrm,
            connectionState = connectionState,
            currentBpm = if (isConnectedToActiveHrm) latestBpm else null,
            currentZone = currentZone,
            sensorContact = if (isConnectedToActiveHrm) latestSensorContact else null,
            batteryLevel = resolveBatteryLevelLocked(activeHrm),
            isTelemetryValid = telemetryValid,
            canStartSession = canStart
        )
    }

    private fun publishActiveStateLocked(
        session: ActiveSessionContext,
        snapshot: DurationSnapshot = durationEngine.snapshot()
    ) {
        val currentZone = latestBpm?.let { bpm ->
            session.zoneCalculation.getZoneForBpm(bpm) ?: HeartZone.ZONE_1
        }
        val batteryLevel = resolveBatteryLevelLocked(session.activeHrm)
        val pauseReason = session.pauseReason

        _uiState.value = if (pauseReason == null) {
            SessionUiState.Recording(
                sessionId = session.sessionId,
                activeHrm = session.activeHrm,
                startEpochMs = session.startEpochMs,
                effectiveDurationMs = snapshot.effectiveDurationMs,
                pausedDurationMs = snapshot.pausedDurationMs,
                elapsedDurationMs = snapshot.elapsedDurationMs,
                currentBpm = latestBpm,
                currentZone = currentZone,
                sensorContact = latestSensorContact,
                batteryLevel = batteryLevel,
                sampleCount = session.sampleCount,
                zoneCalculation = session.zoneCalculation,
                pendingMicroSessionDiscardPrompt = session.pendingMicroSessionDiscardPrompt
            )
        } else {
            SessionUiState.Paused(
                sessionId = session.sessionId,
                activeHrm = session.activeHrm,
                reason = pauseReason,
                startEpochMs = session.startEpochMs,
                effectiveDurationMs = snapshot.effectiveDurationMs,
                pausedDurationMs = snapshot.pausedDurationMs,
                elapsedDurationMs = snapshot.elapsedDurationMs,
                currentBpm = latestBpm,
                currentZone = currentZone,
                sensorContact = latestSensorContact,
                batteryLevel = batteryLevel,
                sampleCount = session.sampleCount,
                zoneCalculation = session.zoneCalculation,
                pendingMicroSessionDiscardPrompt = session.pendingMicroSessionDiscardPrompt
            )
        }
    }

    private data class ActiveSessionContext(
        val sessionId: String,
        var activeHrm: RememberedHrmEntity,
        val startEpochMs: Long,
        val zoneCalculation: ZoneCalculationResult,
        var sessionEntity: SessionEntity,
        var sampleCount: Int = 0,
        var pauseReason: PauseReason? = null,
        var pendingMicroSessionDiscardPrompt: Boolean = false
    )

    companion object {
        const val MICRO_SESSION_THRESHOLD_MS = 10_000L
        const val MIN_USER_AGE = 10
        const val MAX_USER_AGE = 110
        const val MIN_VALID_BPM = 1
        const val MAX_VALID_BPM = HeartRateSampleEntity.MAX_BPM

        @Volatile
        private var instance: SessionRepository? = null

        fun getInstance(
            bleTransport: BleTransport,
            rememberedHrmDao: RememberedHrmDao,
            userProfileDao: UserProfileDao,
            sessionDao: SessionDao,
            sampleDao: HeartRateSampleDao,
            scope: CoroutineScope,
            hrmGattClient: HrmGattClient? = null,
            clock: SessionClock = AndroidSessionClock
        ): SessionRepository {
            return instance ?: synchronized(this) {
                instance ?: SessionRepository(
                    bleTransport = bleTransport,
                    rememberedHrmDao = rememberedHrmDao,
                    userProfileDao = userProfileDao,
                    sessionDao = sessionDao,
                    sampleDao = sampleDao,
                    scope = scope,
                    hrmGattClient = hrmGattClient,
                    clock = clock
                ).also { instance = it }
            }
        }

        fun getInstanceOrNull(): SessionRepository? = instance

        internal fun clearInstanceForTesting() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}
