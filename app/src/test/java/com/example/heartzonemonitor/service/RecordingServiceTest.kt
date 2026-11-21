package com.example.heartzonemonitor.service

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.heartzonemonitor.data.ble.BleConnectionState
import com.example.heartzonemonitor.data.ble.FakeBleTransport
import com.example.heartzonemonitor.data.ble.HrmGattClient
import com.example.heartzonemonitor.data.local.FakeHeartRateSampleDao
import com.example.heartzonemonitor.data.local.FakeRememberedHrmDao
import com.example.heartzonemonitor.data.local.FakeSessionDao
import com.example.heartzonemonitor.data.local.FakeUserProfileDao
import com.example.heartzonemonitor.data.local.HeartRateSampleBuffer
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity
import com.example.heartzonemonitor.data.local.entity.UserProfileEntity
import com.example.heartzonemonitor.data.session.PauseReason
import com.example.heartzonemonitor.data.session.SessionRepository
import com.example.heartzonemonitor.data.session.SessionUiState
import com.example.heartzonemonitor.data.session.StartSessionResult
import com.example.heartzonemonitor.data.session.StopSessionOutcome
import com.example.heartzonemonitor.domain.model.ZoneCalculationMode
import com.example.heartzonemonitor.domain.timing.FakeSessionClock
import com.example.heartzonemonitor.domain.timing.MonotonicDurationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], packageName = "com.example.heartzonemonitor")
class RecordingServiceTest {

    private lateinit var application: Application
    private lateinit var context: Context

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        context = application
        RecordingService.resetTestHooks()
        SessionRepository.clearInstanceForTesting()
    }

    @After
    fun tearDown() {
        RecordingService.resetTestHooks()
        SessionRepository.clearInstanceForTesting()
    }

    @Test
    fun manifestDeclaresRecordingServiceWithConnectedDeviceTypeExportedFalseAndPermissions() {
        val packageManager = context.packageManager
        val serviceInfo = packageManager.getServiceInfo(
            ComponentName(context, RecordingService::class.java),
            PackageManager.GET_META_DATA
        )
        assertFalse(serviceInfo.exported)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            serviceInfo.foregroundServiceType
        )

        @Suppress("DEPRECATION")
        val packageInfo = packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )
        val requestedPermissions = packageInfo.requestedPermissions?.toSet().orEmpty()
        assertTrue(requestedPermissions.contains(Manifest.permission.FOREGROUND_SERVICE))
        assertTrue(
            requestedPermissions.contains(Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE)
        )
        assertTrue(requestedPermissions.contains(Manifest.permission.WAKE_LOCK))
    }

    @Test
    fun onStartCommandReturnsStartNotStickyEntersForegroundAndCreatesLowImportanceChannel() =
        runTest {
            val harness = createHarness(backgroundScope)
            try {
                harness.connectAndPrimeTelemetry(bpm = 132)
                runCurrent()
                assertTrue(harness.repository.startSession() is StartSessionResult.Started)
                runCurrent()

                RecordingService.sessionRepositoryProvider = { harness.repository }
                val intent = RecordingService.createStartIntent(context)
                assertEquals(RecordingService.ACTION_START_RECORDING, intent.action)

                val controller = Robolectric.buildService(RecordingService::class.java, intent).create()
                val service = controller.get()
                assertNull(service.onBind(intent))

                val startResult = service.onStartCommand(intent, 0, 1)
                drainMainLooper()
                assertEquals(Service.START_NOT_STICKY, startResult)

                val notificationManager = context.getSystemService(NotificationManager::class.java)
                val channel =
                    notificationManager.getNotificationChannel(RecordingService.NOTIFICATION_CHANNEL_ID)
                assertNotNull(channel)
                assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
                assertFalse(channel.canShowBadge())

                val shadowService = Shadows.shadowOf(service)
                assertEquals(RecordingService.NOTIFICATION_ID, shadowService.lastForegroundNotificationId)
                val notification = shadowService.lastForegroundNotification
                assertNotNull(notification)
                assertTrue((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0)
                assertTrue((notification.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0)
                assertEquals(NotificationCompat.CATEGORY_SERVICE, notification.category)
                assertFalse(shadowService.isForegroundStopped)
                assertFalse(shadowService.isStoppedBySelf)

                controller.destroy()
            } finally {
                harness.close()
            }
        }

    @Test
    fun wakeLockIsHeldInRecordingReleasedOnManualAndAutoPauseReacquiredOnResumeAndStoppedOnCompleted() =
        runTest {
            val harness = createHarness(backgroundScope)
            try {
                harness.connectAndPrimeTelemetry(bpm = 140)
                runCurrent()
                assertTrue(harness.repository.startSession() is StartSessionResult.Started)
                runCurrent()

                RecordingService.sessionRepositoryProvider = { harness.repository }
                val intent = RecordingService.createStartIntent(context)
                val controller = Robolectric.buildService(RecordingService::class.java, intent).create()
                val service = controller.get()
                service.onStartCommand(intent, 0, 1)
                drainMainLooper()

                val wakeLock = ShadowPowerManager.getLatestWakeLock()
                assertNotNull(wakeLock)
                val shadowWakeLock = Shadows.shadowOf(wakeLock)
                assertEquals(RecordingService.WAKE_LOCK_TAG, shadowWakeLock.tag)
                assertFalse(shadowWakeLock.isReferenceCounted)
                assertTrue(wakeLock.isHeld)

                // Manual pause immediately releases wake lock while keeping foreground service alive.
                assertTrue(harness.repository.pauseSession(PauseReason.USER_MANUAL))
                drainTestAndMainLooper()
                assertFalse(wakeLock.isHeld)
                val shadowService = Shadows.shadowOf(service)
                assertFalse(shadowService.isStoppedBySelf)
                assertFalse(shadowService.isForegroundStopped)
                assertEquals(
                    "Session Paused",
                    shadowService.lastForegroundNotification.extras.getString(Notification.EXTRA_TITLE)
                )

                // Resume back to Recording re-acquires wake lock.
                assertTrue(harness.repository.resumeSession())
                drainTestAndMainLooper()
                assertTrue(wakeLock.isHeld)
                assertEquals(
                    "Session Recording",
                    shadowService.lastForegroundNotification.extras.getString(Notification.EXTRA_TITLE)
                )

                // Auto-pause (BLE_CONNECTION_LOST) immediately releases wake lock.
                assertTrue(harness.repository.pauseSession(PauseReason.BLE_CONNECTION_LOST))
                drainTestAndMainLooper()
                assertFalse(wakeLock.isHeld)
                assertFalse(shadowService.isStoppedBySelf)
                assertFalse(shadowService.isForegroundStopped)

                // Resume back to Recording re-acquires wake lock.
                assertTrue(harness.repository.resumeSession())
                drainTestAndMainLooper()
                assertTrue(wakeLock.isHeld)

                // Auto-pause (SENSOR_CONTACT_LOST) immediately releases wake lock.
                assertTrue(harness.repository.pauseSession(PauseReason.SENSOR_CONTACT_LOST))
                drainTestAndMainLooper()
                assertFalse(wakeLock.isHeld)
                assertFalse(shadowService.isStoppedBySelf)
                assertFalse(shadowService.isForegroundStopped)

                // Resume, emit sample, and complete Session -> stops foreground + self + releases wake lock.
                assertTrue(harness.repository.resumeSession())
                drainTestAndMainLooper()
                assertTrue(wakeLock.isHeld)

                harness.emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = 148, contactDetected = true)
                runCurrent()
                harness.clock.advanceBothBy(15_000L)
                val stopOutcome = harness.repository.stopSession()
                assertTrue(stopOutcome is StopSessionOutcome.Completed)
                drainTestAndMainLooper()

                assertFalse(wakeLock.isHeld)
                assertTrue(shadowService.isForegroundStopped)
                assertNull(shadowService.lastForegroundNotification)
                assertTrue(shadowService.isStoppedBySelf)
                val notificationManager =
                    context.getSystemService(NotificationManager::class.java)
                assertNull(
                    Shadows.shadowOf(notificationManager)
                        .getNotification(RecordingService.NOTIFICATION_ID)
                )

                controller.destroy()
            } finally {
                harness.close()
            }
        }

    @Test
    fun discardedSessionImmediatelyReleasesWakeLockStopsForegroundAndStopsSelf() = runTest {
        val harness = createHarness(backgroundScope)
        try {
            harness.connectAndPrimeTelemetry(bpm = 135)
            runCurrent()
            assertTrue(harness.repository.startSession() is StartSessionResult.Started)
            runCurrent()

            RecordingService.sessionRepositoryProvider = { harness.repository }
            val intent = RecordingService.createStartIntent(context)
            val controller = Robolectric.buildService(RecordingService::class.java, intent).create()
            val service = controller.get()
            service.onStartCommand(intent, 0, 1)
            drainMainLooper()

            val wakeLock = ShadowPowerManager.getLatestWakeLock()
            assertNotNull(wakeLock)
            assertTrue(wakeLock.isHeld)

            assertTrue(harness.repository.discardSession())
            drainTestAndMainLooper()

            val shadowService = Shadows.shadowOf(service)
            assertFalse(wakeLock.isHeld)
            assertTrue(shadowService.isForegroundStopped)
            assertTrue(shadowService.isStoppedBySelf)
            val notificationManager =
                context.getSystemService(NotificationManager::class.java)
            assertNull(
                Shadows.shadowOf(notificationManager)
                    .getNotification(RecordingService.NOTIFICATION_ID)
            )

            controller.destroy()
        } finally {
            harness.close()
        }
    }

    @Test
    fun startingWithNullRepositoryEntersForegroundThenImmediatelyStopsWithoutLeakingWakeLock() {
        RecordingService.sessionRepositoryProvider = { null }
        var notificationBuildCount = 0
        val defaultBuilder = RecordingService.notificationBuilderProvider
        RecordingService.notificationBuilderProvider = { ctx, state ->
            notificationBuildCount += 1
            defaultBuilder(ctx, state)
        }
        val intent = RecordingService.createStartIntent(context)
        val controller = Robolectric.buildService(RecordingService::class.java, intent).create()
        val service = controller.get()

        val startResult = service.onStartCommand(intent, 0, 1)
        drainMainLooper()

        assertEquals(Service.START_NOT_STICKY, startResult)
        assertEquals(1, notificationBuildCount)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        assertNotNull(
            notificationManager.getNotificationChannel(RecordingService.NOTIFICATION_CHANNEL_ID)
        )
        val shadowService = Shadows.shadowOf(service)
        assertTrue(shadowService.isForegroundStopped)
        assertNull(shadowService.lastForegroundNotification)
        assertTrue(shadowService.isStoppedBySelf)

        val wakeLock = ShadowPowerManager.getLatestWakeLock()
        assertTrue(wakeLock == null || !wakeLock.isHeld)

        controller.destroy()
    }

    @Test
    fun startingWhenRepositoryAlreadyCompletedOrDiscardedStopsImmediatelyWithoutHoldingWakeLock() =
        runTest {
            val harness = createHarness(backgroundScope)
            try {
                harness.connectAndPrimeTelemetry(bpm = 126)
                runCurrent()
                assertTrue(harness.repository.startSession() is StartSessionResult.Started)
                assertTrue(
                    harness.repository.stopSession(forceSave = true) is StopSessionOutcome.Completed
                )
                assertTrue(harness.repository.uiState.value is SessionUiState.Completed)

                RecordingService.sessionRepositoryProvider = { harness.repository }
                val intent = RecordingService.createStartIntent(context)
                val completedController =
                    Robolectric.buildService(RecordingService::class.java, intent).create()
                val completedService = completedController.get()

                val completedStartResult = completedService.onStartCommand(intent, 0, 1)
                drainMainLooper()

                assertEquals(Service.START_NOT_STICKY, completedStartResult)
                val completedShadow = Shadows.shadowOf(completedService)
                assertTrue(completedShadow.isForegroundStopped)
                assertNull(completedShadow.lastForegroundNotification)
                assertTrue(completedShadow.isStoppedBySelf)
                val wakeLockAfterCompleted = ShadowPowerManager.getLatestWakeLock()
                assertTrue(wakeLockAfterCompleted == null || !wakeLockAfterCompleted.isHeld)
                completedController.destroy()

                harness.repository.resetToIdle()
                runCurrent()
                assertTrue(harness.repository.startSession() is StartSessionResult.Started)
                assertTrue(harness.repository.discardSession())
                assertTrue(harness.repository.uiState.value is SessionUiState.Discarded)

                val discardedController =
                    Robolectric.buildService(RecordingService::class.java, intent).create()
                val discardedService = discardedController.get()

                val discardedStartResult = discardedService.onStartCommand(intent, 0, 2)
                drainMainLooper()

                assertEquals(Service.START_NOT_STICKY, discardedStartResult)
                val discardedShadow = Shadows.shadowOf(discardedService)
                assertTrue(discardedShadow.isForegroundStopped)
                assertNull(discardedShadow.lastForegroundNotification)
                assertTrue(discardedShadow.isStoppedBySelf)
                val wakeLockAfterDiscarded = ShadowPowerManager.getLatestWakeLock()
                assertTrue(wakeLockAfterDiscarded == null || !wakeLockAfterDiscarded.isHeld)
                discardedController.destroy()
            } finally {
                harness.close()
            }
        }

    @Test
    fun duplicateOnStartCommandAndOnDestroyReleaseWakeLockCleanly() = runTest {
        val harness = createHarness(backgroundScope)
        try {
            harness.connectAndPrimeTelemetry(bpm = 136)
            runCurrent()
            assertTrue(harness.repository.startSession() is StartSessionResult.Started)
            runCurrent()

            RecordingService.sessionRepositoryProvider = { harness.repository }
            val intent = RecordingService.createStartIntent(context)
            val controller = Robolectric.buildService(RecordingService::class.java, intent).create()
            val service = controller.get()

            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(intent, 0, 1))
            drainMainLooper()
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(intent, 0, 2))
            drainMainLooper()

            val wakeLock = ShadowPowerManager.getLatestWakeLock()
            assertNotNull(wakeLock)
            assertTrue(wakeLock.isHeld)

            controller.destroy()
            assertFalse(wakeLock.isHeld)
        } finally {
            harness.close()
        }
    }

    @Test
    fun startingInIdleWaitsUntilActiveSessionThenStopsOnResetToIdle() = runTest {
        val harness = createHarness(backgroundScope)
        try {
            harness.connectAndPrimeTelemetry(bpm = 122)
            runCurrent()
            assertTrue(harness.repository.uiState.value is SessionUiState.Idle)

            RecordingService.sessionRepositoryProvider = { harness.repository }
            val intent = RecordingService.createStartIntent(context)
            val controller = Robolectric.buildService(RecordingService::class.java, intent).create()
            val service = controller.get()

            service.onStartCommand(intent, 0, 1)
            drainMainLooper()

            val shadowService = Shadows.shadowOf(service)
            assertFalse(shadowService.isStoppedBySelf)
            val wakeLockBeforeStart = ShadowPowerManager.getLatestWakeLock()
            assertTrue(wakeLockBeforeStart == null || !wakeLockBeforeStart.isHeld)

            assertTrue(harness.repository.startSession() is StartSessionResult.Started)
            drainTestAndMainLooper()

            val wakeLock = ShadowPowerManager.getLatestWakeLock()
            assertNotNull(wakeLock)
            assertTrue(wakeLock.isHeld)

            harness.repository.stopSession(forceSave = true)
            drainTestAndMainLooper()
            assertFalse(wakeLock.isHeld)
            assertTrue(shadowService.isForegroundStopped)
            assertTrue(shadowService.isStoppedBySelf)

            controller.destroy()
        } finally {
            harness.close()
        }
    }

    @Test
    fun coordinatorLaunchesServiceOncePerActiveSessionAndReArmsAfterCompletedOrDiscarded() =
        runTest {
            val harness = createHarness(backgroundScope)
            var launchCount = 0
            val coordinator = RecordingServiceCoordinator(
                sessionRepository = harness.repository,
                launcher = RecordingServiceLauncher { launchCount += 1 },
                scope = backgroundScope
            )
            try {
                harness.connectAndPrimeTelemetry(bpm = 130)
                runCurrent()
                assertEquals(0, launchCount)

                // Session 1: Idle -> Recording triggers launcher once.
                assertTrue(harness.repository.startSession() is StartSessionResult.Started)
                runCurrent()
                assertEquals(1, launchCount)

                // Pause and resume must NOT re-trigger launcher.
                assertTrue(harness.repository.pauseSession(PauseReason.USER_MANUAL))
                runCurrent()
                assertEquals(1, launchCount)

                assertTrue(harness.repository.resumeSession())
                runCurrent()
                assertEquals(1, launchCount)

                // Complete Session 1 -> re-arms coordinator; Session 2 triggers launcher again.
                assertTrue(
                    harness.repository.stopSession(forceSave = true) is StopSessionOutcome.Completed
                )
                runCurrent()
                assertEquals(1, launchCount)

                harness.repository.resetToIdle()
                runCurrent()
                assertEquals(1, launchCount)

                assertTrue(harness.repository.startSession() is StartSessionResult.Started)
                runCurrent()
                assertEquals(2, launchCount)

                // Discard Session 2 -> re-arms coordinator; Session 3 triggers launcher again.
                assertTrue(harness.repository.discardSession())
                runCurrent()
                assertEquals(2, launchCount)

                assertTrue(harness.repository.startSession() is StartSessionResult.Started)
                runCurrent()
                assertEquals(3, launchCount)

                // After close(), further sessions do not trigger launcher.
                assertTrue(harness.repository.discardSession())
                runCurrent()
                coordinator.close()
                assertTrue(harness.repository.startSession() is StartSessionResult.Started)
                runCurrent()
                assertEquals(3, launchCount)
            } finally {
                coordinator.close()
                harness.close()
            }
        }

    @Test
    fun androidRecordingServiceLauncherStartsRecordingServiceAsForegroundService() {
        val launcher = AndroidRecordingServiceLauncher(context)
        launcher.startRecordingService()

        val startedIntent = Shadows.shadowOf(application).nextStartedService
        assertNotNull(startedIntent)
        assertEquals(RecordingService::class.java.name, startedIntent.component?.className)
        assertEquals(RecordingService.ACTION_START_RECORDING, startedIntent.action)
    }

    private fun TestScope.drainTestAndMainLooper() {
        runCurrent()
        drainMainLooper()
    }

    private fun drainMainLooper() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun createHarness(scope: CoroutineScope): TestHarness {
        val bleTransport = FakeBleTransport()
        val gattClient = HrmGattClient(bleTransport = bleTransport, scope = scope)
        val sampleDao = FakeHeartRateSampleDao()
        val sessionDao = FakeSessionDao(sampleDao = sampleDao)
        val hrmDao = FakeRememberedHrmDao(initialHrms = listOf(activeHrm()))
        val profileDao = FakeUserProfileDao(initialProfile = defaultProfile())
        val clock = FakeSessionClock(
            initialRealtimeMs = 10_000L,
            initialEpochMs = 1_700_000_000_000L
        )
        val durationEngine = MonotonicDurationEngine(clock)
        val sampleBuffer = HeartRateSampleBuffer(
            sampleDao = sampleDao,
            scope = scope,
            flushIntervalMs = HeartRateSampleBuffer.DEFAULT_FLUSH_INTERVAL_MS
        )
        var counter = 0
        val repository = SessionRepository(
            bleTransport = bleTransport,
            rememberedHrmDao = hrmDao,
            userProfileDao = profileDao,
            sessionDao = sessionDao,
            sampleDao = sampleDao,
            scope = scope,
            hrmGattClient = gattClient,
            clock = clock,
            durationEngine = durationEngine,
            sampleBuffer = sampleBuffer,
            currentYearProvider = { 2026 },
            sessionIdGenerator = { "service-test-session-${++counter}" }
        )
        return TestHarness(
            bleTransport = bleTransport,
            gattClient = gattClient,
            clock = clock,
            sampleBuffer = sampleBuffer,
            repository = repository
        )
    }

    private class TestHarness(
        val bleTransport: FakeBleTransport,
        val gattClient: HrmGattClient,
        val clock: FakeSessionClock,
        val sampleBuffer: HeartRateSampleBuffer,
        val repository: SessionRepository
    ) {
        suspend fun emitHrPacket(
            macAddress: String,
            bpm: Int,
            contactDetected: Boolean = true
        ) {
            val payload = FakeBleTransport.buildHeartRateMeasurementPacket(
                bpm = bpm,
                sensorContactSupported = true,
                sensorContactDetected = contactDetected
            )
            bleTransport.emitNotification(
                macAddress = macAddress,
                value = payload,
                timestampEpochMs = clock.currentEpochMs()
            )
        }

        suspend fun connectAndPrimeTelemetry(bpm: Int = 120) {
            bleTransport.connect(ACTIVE_HRM_MAC)
            emitHrPacket(macAddress = ACTIVE_HRM_MAC, bpm = bpm, contactDetected = true)
            repository.uiState.first { it is SessionUiState.Idle && it.canStartSession }
        }

        suspend fun close() {
            sampleBuffer.discard()
            gattClient.close()
            repository.close()
        }
    }

    private fun defaultProfile(): UserProfileEntity = UserProfileEntity(
        birthYear = 1996,
        restingHr = null,
        customHrmax = null,
        calculationMode = ZoneCalculationMode.PERCENT_HRMAX,
        updatedAtEpochMs = 1_700_000_000_000L
    )

    private fun activeHrm(): RememberedHrmEntity = RememberedHrmEntity(
        macAddress = ACTIVE_HRM_MAC,
        advertisedName = "COOSPO H808S",
        customNickname = "Chest Strap",
        lastSeenTimestamp = 1_700_000_000_000L,
        batteryLevel = 90,
        isActive = true
    )

    companion object {
        private const val ACTIVE_HRM_MAC = "AA:BB:CC:DD:EE:01"
    }
}
