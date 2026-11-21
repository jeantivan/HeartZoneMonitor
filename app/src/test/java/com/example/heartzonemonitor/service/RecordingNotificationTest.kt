package com.example.heartzonemonitor.service

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.example.heartzonemonitor.MainActivity
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
import com.example.heartzonemonitor.domain.calculator.ZoneCalculator
import com.example.heartzonemonitor.domain.model.HeartZone
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
class RecordingNotificationTest {

    private lateinit var application: Application
    private lateinit var context: Context

    private val defaultZoneCalculation = ZoneCalculator().calculateZones(
        age = 30,
        restingHr = null,
        customHrmax = null
    )

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
    fun recordingChannelUsesLowImportanceNoBadgeAndOngoingServiceFlags() {
        val clock = FakeSessionClock(initialRealtimeMs = 10_000L, initialEpochMs = 1_700_000_000_000L)
        val factory = RecordingNotificationFactory(clock)

        val notification = factory.build(
            context = context,
            state = recordingState(bpm = 142, zone = HeartZone.ZONE_3, effectiveDurationMs = 95_000L)
        )

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val channel = notificationManager.getNotificationChannel(RecordingService.NOTIFICATION_CHANNEL_ID)
        assertNotNull(channel)
        assertEquals("recording_channel", channel.id)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertFalse(channel.canShowBadge())

        assertEquals(RecordingService.NOTIFICATION_CHANNEL_ID, notification.channelId)
        assertTrue((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        assertTrue((notification.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0)
        assertEquals(NotificationCompat.CATEGORY_SERVICE, notification.category)
        assertEquals(NotificationCompat.VISIBILITY_PUBLIC, notification.visibility)
    }

    @Test
    fun notificationTitleDisplaysCurrentBpmAndHeartZoneNameForAllZonesAndFallbacks() {
        assertEquals(
            "105 BPM • Zone 1 (Active Recovery)",
            RecordingNotificationFactory.formatTitle(recordingState(bpm = 105, zone = HeartZone.ZONE_1))
        )
        assertEquals(
            "124 BPM • Zone 2 (Aerobic Base)",
            RecordingNotificationFactory.formatTitle(recordingState(bpm = 124, zone = HeartZone.ZONE_2))
        )
        assertEquals(
            "142 BPM • Zone 3 (Tempo)",
            RecordingNotificationFactory.formatTitle(recordingState(bpm = 142, zone = HeartZone.ZONE_3))
        )
        assertEquals(
            "160 BPM • Zone 4 (Lactate Threshold)",
            RecordingNotificationFactory.formatTitle(recordingState(bpm = 160, zone = HeartZone.ZONE_4))
        )
        assertEquals(
            "178 BPM • Zone 5 (VO2max)",
            RecordingNotificationFactory.formatTitle(recordingState(bpm = 178, zone = HeartZone.ZONE_5))
        )
        assertEquals(
            "132 BPM",
            RecordingNotificationFactory.formatTitle(recordingState(bpm = 132, zone = null))
        )
        assertEquals(
            "-- BPM • Zone 2 (Aerobic Base)",
            RecordingNotificationFactory.formatTitle(recordingState(bpm = null, zone = HeartZone.ZONE_2))
        )
        assertEquals(
            "Session Recording",
            RecordingNotificationFactory.formatTitle(recordingState(bpm = null, zone = null))
        )
        assertEquals(
            "Paused • 142 BPM • Zone 3 (Tempo)",
            RecordingNotificationFactory.formatTitle(pausedState(bpm = 142, zone = HeartZone.ZONE_3))
        )
        assertEquals(
            "Paused • 142 BPM",
            RecordingNotificationFactory.formatTitle(pausedState(bpm = 142, zone = null))
        )
        assertEquals(
            "Session Paused",
            RecordingNotificationFactory.formatTitle(pausedState(bpm = null, zone = null))
        )
    }

    @Test
    fun recordingChronometerUsesMonotonicEffectiveDurationBaselineAndStopsWhenPaused() {
        val clock = FakeSessionClock(initialRealtimeMs = 10_000L, initialEpochMs = 1_700_000_100_000L)
        val factory = RecordingNotificationFactory(clock)

        val recordingNotification = factory.build(
            context = context,
            state = recordingState(bpm = 142, zone = HeartZone.ZONE_3, effectiveDurationMs = 45_000L)
        )
        assertTrue(recordingNotification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(recordingNotification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
        assertEquals(1_700_000_055_000L, recordingNotification.`when`)

        // Paused state stops native chronometer and renders formatted Effective Duration in content text.
        val pausedNotification = factory.build(
            context = context,
            state = pausedState(
                bpm = 142,
                zone = HeartZone.ZONE_3,
                effectiveDurationMs = 45_000L,
                reason = PauseReason.USER_MANUAL
            )
        )
        assertFalse(pausedNotification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertFalse(pausedNotification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
        assertEquals(
            "Paused • Effective Duration 00:45",
            pausedNotification.extras.getString(Notification.EXTRA_TEXT)
        )

        // Multi-hour Effective Duration formats as HH:MM:SS and reflects auto-pause reasons.
        val blePausedNotification = factory.build(
            context = context,
            state = pausedState(
                bpm = null,
                zone = null,
                effectiveDurationMs = 3_725_000L,
                reason = PauseReason.BLE_CONNECTION_LOST
            )
        )
        assertEquals(
            "HRM disconnected • Effective Duration 01:02:05",
            blePausedNotification.extras.getString(Notification.EXTRA_TEXT)
        )

        val contactPausedNotification = factory.build(
            context = context,
            state = pausedState(
                bpm = null,
                zone = null,
                effectiveDurationMs = 312_000L,
                reason = PauseReason.SENSOR_CONTACT_LOST
            )
        )
        assertEquals(
            "Sensor contact lost • Effective Duration 05:12",
            contactPausedNotification.extras.getString(Notification.EXTRA_TEXT)
        )

        val pausedPromptNotification = factory.build(
            context = context,
            state = pausedState(
                bpm = 136,
                zone = HeartZone.ZONE_3,
                effectiveDurationMs = 5_000L,
                pendingMicroSessionDiscardPrompt = true
            )
        )
        assertEquals(
            "Effective Duration 00:05 • Tap to save or discard short Session",
            pausedPromptNotification.extras.getString(Notification.EXTRA_TEXT)
        )

        // Resuming after a 20s pause seeds chronometer base to currentEpochMs - effectiveDurationMs.
        clock.advanceBothBy(20_000L)
        val resumedNotification = factory.build(
            context = context,
            state = recordingState(bpm = 144, zone = HeartZone.ZONE_3, effectiveDurationMs = 45_000L)
        )
        assertTrue(resumedNotification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(resumedNotification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
        assertEquals(1_700_000_075_000L, resumedNotification.`when`)
    }

    @Test
    fun actionButtonsAndContentDeepLinkPendingIntentsTargetServiceAndSingleTopMainActivity() {
        val packageManager = context.packageManager
        val activityInfo = packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            PackageManager.GET_META_DATA
        )
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, activityInfo.launchMode)

        val clock = FakeSessionClock()
        val factory = RecordingNotificationFactory(clock)

        val recordingNotification = factory.build(
            context = context,
            state = recordingState(bpm = 142, zone = HeartZone.ZONE_3)
        )

        // Content click intent deep-links straight into MainActivity Live Session.
        val contentPendingIntent = recordingNotification.contentIntent
        assertNotNull(contentPendingIntent)
        val shadowContentPi = Shadows.shadowOf(contentPendingIntent)
        assertTrue(shadowContentPi.isActivity)
        assertTrue((shadowContentPi.flags and PendingIntent.FLAG_IMMUTABLE) != 0)
        val contentIntent = shadowContentPi.savedIntent
        assertEquals(MainActivity::class.java.name, contentIntent.component?.className)
        assertTrue(contentIntent.getBooleanExtra(MainActivity.EXTRA_OPEN_LIVE_SESSION, false))
        assertTrue((contentIntent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0)
        assertTrue((contentIntent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0)

        // Recording actions: [Pause] and [Stop & Save].
        assertEquals(2, recordingNotification.actions.size)
        assertEquals("Pause", recordingNotification.actions[0].title.toString())
        val pausePi = Shadows.shadowOf(recordingNotification.actions[0].actionIntent)
        assertTrue(pausePi.isService)
        assertTrue((pausePi.flags and PendingIntent.FLAG_IMMUTABLE) != 0)
        assertEquals(RecordingService::class.java.name, pausePi.savedIntent.component?.className)
        assertEquals(RecordingService.ACTION_PAUSE_SESSION, pausePi.savedIntent.action)

        assertEquals("Stop & Save", recordingNotification.actions[1].title.toString())
        val stopPi = Shadows.shadowOf(recordingNotification.actions[1].actionIntent)
        assertTrue(stopPi.isService)
        assertTrue((stopPi.flags and PendingIntent.FLAG_IMMUTABLE) != 0)
        assertEquals(RecordingService::class.java.name, stopPi.savedIntent.component?.className)
        assertEquals(RecordingService.ACTION_STOP_SESSION, stopPi.savedIntent.action)

        // Paused actions: [Resume] and [Stop & Save].
        val pausedNotification = factory.build(
            context = context,
            state = pausedState(bpm = 142, zone = HeartZone.ZONE_3)
        )
        assertEquals(2, pausedNotification.actions.size)
        assertEquals("Resume", pausedNotification.actions[0].title.toString())
        val resumePi = Shadows.shadowOf(pausedNotification.actions[0].actionIntent)
        assertTrue(resumePi.isService)
        assertTrue((resumePi.flags and PendingIntent.FLAG_IMMUTABLE) != 0)
        assertEquals(RecordingService.ACTION_RESUME_SESSION, resumePi.savedIntent.action)

        assertEquals("Stop & Save", pausedNotification.actions[1].title.toString())
        assertEquals(
            RecordingService.ACTION_STOP_SESSION,
            Shadows.shadowOf(pausedNotification.actions[1].actionIntent).savedIntent.action
        )

        // Idle state omits active Session action buttons.
        val idleNotification = factory.build(context = context, state = SessionUiState.Idle())
        assertTrue(idleNotification.actions.isNullOrEmpty())
    }

    @Test
    fun mainActivityConsumesLiveSessionDeepLinkOnCreateAndOnNewIntent() {
        val launchIntent = MainActivity.createLiveSessionIntent(context)
        val controller = Robolectric.buildActivity(MainActivity::class.java, launchIntent).create()
        val activity = controller.get()
        drainMainLooper()

        assertEquals(1, activity.openLiveSessionRequestId)
        assertFalse(activity.intent.getBooleanExtra(MainActivity.EXTRA_OPEN_LIVE_SESSION, false))

        // Unrelated new intent does not increment deep-link request counter.
        controller.newIntent(Intent(context, MainActivity::class.java))
        drainMainLooper()
        assertEquals(1, activity.openLiveSessionRequestId)

        // Subsequent notification tap via singleTop onNewIntent increments counter and consumes extra.
        controller.newIntent(MainActivity.createLiveSessionIntent(context))
        drainMainLooper()
        assertEquals(2, activity.openLiveSessionRequestId)
        assertFalse(activity.intent.getBooleanExtra(MainActivity.EXTRA_OPEN_LIVE_SESSION, false))

        // Configuration-change recreation preserves openLiveSessionRequestId without re-triggering,
        // and a subsequent notification tap advances monotonically to 3.
        val savedState = android.os.Bundle()
        controller.saveInstanceState(savedState)
        controller.destroy()

        val recreatedController =
            Robolectric.buildActivity(MainActivity::class.java, launchIntent).create(savedState)
        val recreatedActivity = recreatedController.get()
        drainMainLooper()
        assertEquals(2, recreatedActivity.openLiveSessionRequestId)

        recreatedController.newIntent(MainActivity.createLiveSessionIntent(context))
        drainMainLooper()
        assertEquals(3, recreatedActivity.openLiveSessionRequestId)

        recreatedController.destroy()
    }

    @Test
    fun debouncerSuppressesOneBpmJitterAndEmitsOnTwoBpmShiftZoneChangeStateChangeOrFiveSeconds() {
        val clock = FakeSessionClock(initialRealtimeMs = 10_000L, initialEpochMs = 1_700_000_000_000L)
        val debouncer = RecordingNotificationDebouncer(clock = clock)

        // Initial emission always emits.
        val initial = recordingState(bpm = 140, zone = HeartZone.ZONE_3)
        assertTrue(debouncer.consumeIfShouldEmit(initial))

        // 0 or 1 BPM jitter inside 5s window with same zone/state is suppressed.
        clock.advanceRealtimeBy(1_000L)
        assertFalse(debouncer.consumeIfShouldEmit(recordingState(bpm = 140, zone = HeartZone.ZONE_3)))
        clock.advanceRealtimeBy(1_000L)
        assertFalse(debouncer.consumeIfShouldEmit(recordingState(bpm = 141, zone = HeartZone.ZONE_3)))
        clock.advanceRealtimeBy(1_000L)
        assertFalse(debouncer.consumeIfShouldEmit(recordingState(bpm = 139, zone = HeartZone.ZONE_3)))

        // Cumulative >= 2 BPM shift relative to last displayed BPM (140 -> 142) emits immediately.
        clock.advanceRealtimeBy(500L)
        assertTrue(debouncer.consumeIfShouldEmit(recordingState(bpm = 142, zone = HeartZone.ZONE_3)))

        // Heart Zone transition emits immediately even on a 1 BPM shift.
        clock.advanceRealtimeBy(500L)
        assertTrue(debouncer.consumeIfShouldEmit(recordingState(bpm = 143, zone = HeartZone.ZONE_4)))

        // Wall-clock jumps (positive or negative) never trigger or stall monotonic 5s debounce.
        clock.advanceWallClockBy(600_000L)
        assertFalse(debouncer.consumeIfShouldEmit(recordingState(bpm = 143, zone = HeartZone.ZONE_4)))
        clock.advanceWallClockBy(-1_200_000L)
        clock.advanceRealtimeBy(4_999L)
        assertFalse(debouncer.consumeIfShouldEmit(recordingState(bpm = 143, zone = HeartZone.ZONE_4)))

        // At exactly 5,000 ms monotonic interval since last emission, update is allowed.
        clock.advanceRealtimeBy(1L)
        assertTrue(debouncer.consumeIfShouldEmit(recordingState(bpm = 143, zone = HeartZone.ZONE_4)))

        // Null <-> non-null BPM transitions emit immediately.
        clock.advanceRealtimeBy(500L)
        assertTrue(debouncer.consumeIfShouldEmit(recordingState(bpm = null, zone = HeartZone.ZONE_4)))
        clock.advanceRealtimeBy(500L)
        assertTrue(debouncer.consumeIfShouldEmit(recordingState(bpm = 143, zone = HeartZone.ZONE_4)))

        // Micro-session prompt flag change emits immediately.
        clock.advanceRealtimeBy(500L)
        assertTrue(
            debouncer.consumeIfShouldEmit(
                recordingState(
                    bpm = 143,
                    zone = HeartZone.ZONE_4,
                    pendingMicroSessionDiscardPrompt = true
                )
            )
        )

        // State class change (Recording -> Paused) and PauseReason changes emit immediately.
        clock.advanceRealtimeBy(500L)
        assertTrue(
            debouncer.consumeIfShouldEmit(
                pausedState(bpm = 143, zone = HeartZone.ZONE_4, reason = PauseReason.USER_MANUAL)
            )
        )
        clock.advanceRealtimeBy(500L)
        assertTrue(
            debouncer.consumeIfShouldEmit(
                pausedState(
                    bpm = 143,
                    zone = HeartZone.ZONE_4,
                    reason = PauseReason.BLE_CONNECTION_LOST
                )
            )
        )
    }

    @Test
    fun actionIntentsDispatchPauseResumeAndStopWithoutRestartingObserverOrBreakingDebounce() =
        runTest {
            val harness = createHarness(backgroundScope)
            try {
                harness.connectAndPrimeTelemetry(bpm = 140)
                runCurrent()
                assertTrue(harness.repository.startSession() is StartSessionResult.Started)
                runCurrent()

                var notifyCount = 0
                val defaultPoster = RecordingService.notificationPoster
                RecordingService.sessionRepositoryProvider = { harness.repository }
                RecordingService.sessionClockProvider = { harness.clock }
                RecordingService.notificationPoster = { ctx, id, notification ->
                    notifyCount += 1
                    defaultPoster(ctx, id, notification)
                }

                val startIntent = RecordingService.createStartIntent(context)
                val controller =
                    Robolectric.buildService(RecordingService::class.java, startIntent).create()
                val service = controller.get()
                service.onStartCommand(startIntent, 0, 1)
                drainMainLooper()

                val shadowService = Shadows.shadowOf(service)
                val notificationManager =
                    context.getSystemService(NotificationManager::class.java)
                val shadowNotificationManager = Shadows.shadowOf(notificationManager)

                // Initial startForeground does not emit a redundant notify() call.
                assertEquals(0, notifyCount)
                val initialNotification = shadowService.lastForegroundNotification
                assertNotNull(initialNotification)
                assertEquals(
                    "140 BPM • Zone 3 (Tempo)",
                    initialNotification.extras.getString(Notification.EXTRA_TITLE)
                )
                assertTrue(initialNotification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))

                // Advance 45s of Recording so Effective Duration > 0 before pausing.
                harness.clock.advanceBothBy(45_000L)

                // Tap [Pause] action from the displayed notification.
                val pauseIntent =
                    Shadows.shadowOf(initialNotification.actions[0].actionIntent).savedIntent
                service.onStartCommand(pauseIntent, 0, 2)
                drainTestAndMainLooper()

                val pausedState = harness.repository.uiState.value as? SessionUiState.Paused
                assertNotNull(pausedState)
                assertEquals(PauseReason.USER_MANUAL, pausedState!!.reason)
                assertEquals(45_000L, pausedState.effectiveDurationMs)

                val wakeLock = ShadowPowerManager.getLatestWakeLock()
                assertNotNull(wakeLock)
                assertFalse(wakeLock.isHeld)
                assertEquals(1, notifyCount)

                val pausedNotification =
                    shadowNotificationManager.getNotification(RecordingService.NOTIFICATION_ID)
                assertNotNull(pausedNotification)
                assertEquals(
                    "Paused • 140 BPM • Zone 3 (Tempo)",
                    pausedNotification.extras.getString(Notification.EXTRA_TITLE)
                )
                assertEquals(
                    "Paused • Effective Duration 00:45",
                    pausedNotification.extras.getString(Notification.EXTRA_TEXT)
                )
                assertFalse(pausedNotification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
                assertEquals("Resume", pausedNotification.actions[0].title.toString())

                // Debouncer state remains intact after action dispatch: 1 BPM jitter within 5s is suppressed.
                harness.clock.advanceBothBy(1_000L)
                harness.emitHrPacket(ACTIVE_HRM_MAC, bpm = 141)
                drainTestAndMainLooper()
                assertEquals(1, notifyCount)

                // Wait another 19s paused (20s total pause), then tap [Resume] action.
                harness.clock.advanceBothBy(19_000L)
                val resumeIntent =
                    Shadows.shadowOf(pausedNotification.actions[0].actionIntent).savedIntent
                service.onStartCommand(resumeIntent, 0, 3)
                drainTestAndMainLooper()

                val resumedState = harness.repository.uiState.value as? SessionUiState.Recording
                assertNotNull(resumedState)
                assertEquals(45_000L, resumedState!!.effectiveDurationMs)
                assertTrue(wakeLock.isHeld)
                assertEquals(2, notifyCount)

                val resumedNotification =
                    shadowNotificationManager.getNotification(RecordingService.NOTIFICATION_ID)
                assertNotNull(resumedNotification)
                assertTrue(resumedNotification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
                assertEquals(
                    harness.clock.currentEpochMs() - 45_000L,
                    resumedNotification.`when`
                )
                assertEquals("Pause", resumedNotification.actions[0].title.toString())

                // Emit sample and tap [Stop & Save] -> completes Session and tears down foreground service.
                harness.clock.advanceBothBy(1_000L)
                harness.emitHrPacket(ACTIVE_HRM_MAC, bpm = 144)
                drainTestAndMainLooper()
                assertEquals(3, notifyCount)

                val stopIntent =
                    Shadows.shadowOf(resumedNotification.actions[1].actionIntent).savedIntent
                service.onStartCommand(stopIntent, 0, 4)
                drainTestAndMainLooper()

                assertTrue(harness.repository.uiState.value is SessionUiState.Completed)
                assertFalse(wakeLock.isHeld)
                assertTrue(shadowService.isForegroundStopped)
                assertTrue(shadowService.isStoppedBySelf)
                assertNull(
                    shadowNotificationManager.getNotification(RecordingService.NOTIFICATION_ID)
                )

                controller.destroy()
            } finally {
                harness.close()
            }
        }

    @Test
    fun stopActionOnMicroSessionKeepsForegroundActiveAndUpdatesNotificationWithPrompt() =
        runTest {
            val harness = createHarness(backgroundScope)
            try {
                harness.connectAndPrimeTelemetry(bpm = 136)
                runCurrent()
                assertTrue(harness.repository.startSession() is StartSessionResult.Started)
                runCurrent()

                var notifyCount = 0
                val defaultPoster = RecordingService.notificationPoster
                RecordingService.sessionRepositoryProvider = { harness.repository }
                RecordingService.sessionClockProvider = { harness.clock }
                RecordingService.notificationPoster = { ctx, id, notification ->
                    notifyCount += 1
                    defaultPoster(ctx, id, notification)
                }

                val startIntent = RecordingService.createStartIntent(context)
                val controller =
                    Robolectric.buildService(RecordingService::class.java, startIntent).create()
                val service = controller.get()
                service.onStartCommand(startIntent, 0, 1)
                drainMainLooper()

                // Advance only 3s with 0 persisted samples -> micro-session guard triggers on Stop & Save.
                harness.clock.advanceBothBy(3_000L)
                val stopIntent = RecordingService.createStopIntent(context)
                service.onStartCommand(stopIntent, 0, 2)
                drainTestAndMainLooper()

                val activeState = harness.repository.uiState.value as? SessionUiState.Recording
                assertNotNull(activeState)
                assertTrue(activeState!!.pendingMicroSessionDiscardPrompt)

                val shadowService = Shadows.shadowOf(service)
                assertFalse(shadowService.isForegroundStopped)
                assertFalse(shadowService.isStoppedBySelf)
                val wakeLock = ShadowPowerManager.getLatestWakeLock()
                assertNotNull(wakeLock)
                assertTrue(wakeLock.isHeld)

                // Prompt flag change immediately emits an updated notification despite < 5s elapsed.
                assertEquals(1, notifyCount)
                val notificationManager =
                    context.getSystemService(NotificationManager::class.java)
                val promptNotification = Shadows.shadowOf(notificationManager)
                    .getNotification(RecordingService.NOTIFICATION_ID)
                assertNotNull(promptNotification)
                assertEquals(
                    "Short Session — tap to save or discard",
                    promptNotification.extras.getString(Notification.EXTRA_TEXT)
                )
                val contentIntent = Shadows.shadowOf(promptNotification.contentIntent).savedIntent
                assertTrue(contentIntent.getBooleanExtra(MainActivity.EXTRA_OPEN_LIVE_SESSION, false))

                controller.destroy()
            } finally {
                harness.close()
            }
        }

    @Test
    fun actionIntentReceivedBeforeObserverAttachedEntersForegroundAttachesObserverAndExecutesAction() =
        runTest {
            val harness = createHarness(backgroundScope)
            try {
                harness.connectAndPrimeTelemetry(bpm = 138)
                runCurrent()
                assertTrue(harness.repository.startSession() is StartSessionResult.Started)
                runCurrent()

                RecordingService.sessionRepositoryProvider = { harness.repository }
                RecordingService.sessionClockProvider = { harness.clock }

                val pauseIntent = RecordingService.createPauseIntent(context)
                val controller =
                    Robolectric.buildService(RecordingService::class.java, pauseIntent).create()
                val service = controller.get()
                service.onStartCommand(pauseIntent, 0, 1)
                drainTestAndMainLooper()

                val pausedState = harness.repository.uiState.value as? SessionUiState.Paused
                assertNotNull(pausedState)
                assertEquals(PauseReason.USER_MANUAL, pausedState!!.reason)

                val shadowService = Shadows.shadowOf(service)
                assertFalse(shadowService.isForegroundStopped)
                assertFalse(shadowService.isStoppedBySelf)
                val wakeLock = ShadowPowerManager.getLatestWakeLock()
                assertTrue(wakeLock == null || !wakeLock.isHeld)

                // Subsequent repository transitions continue to be observed cleanly.
                assertTrue(harness.repository.resumeSession())
                drainTestAndMainLooper()
                assertTrue(ShadowPowerManager.getLatestWakeLock()!!.isHeld)

                assertTrue(harness.repository.discardSession())
                drainTestAndMainLooper()
                assertTrue(shadowService.isForegroundStopped)
                assertTrue(shadowService.isStoppedBySelf)

                controller.destroy()
            } finally {
                harness.close()
            }
        }

    @Test
    fun recordingServiceDebouncesNotifyCallsEndToEndAcrossJitterZoneShiftAndFiveSecondWindow() =
        runTest {
            val harness = createHarness(backgroundScope)
            try {
                // Age 30, PERCENT_HRMAX -> HRmax = 187.
                // Zone 3 (70–80%): 131..149 BPM; Zone 4 (80–90%): 150..168 BPM.
                harness.connectAndPrimeTelemetry(bpm = 140)
                runCurrent()
                assertTrue(harness.repository.startSession() is StartSessionResult.Started)
                runCurrent()

                var notifyCount = 0
                val defaultPoster = RecordingService.notificationPoster
                RecordingService.sessionRepositoryProvider = { harness.repository }
                RecordingService.sessionClockProvider = { harness.clock }
                RecordingService.notificationPoster = { ctx, id, notification ->
                    notifyCount += 1
                    defaultPoster(ctx, id, notification)
                }

                val startIntent = RecordingService.createStartIntent(context)
                val controller =
                    Robolectric.buildService(RecordingService::class.java, startIntent).create()
                val service = controller.get()
                service.onStartCommand(startIntent, 0, 1)
                drainMainLooper()

                val notificationManager =
                    context.getSystemService(NotificationManager::class.java)
                val shadowNotificationManager = Shadows.shadowOf(notificationManager)

                assertEquals(0, notifyCount)

                // 0–1 BPM jitter within 5s window in Zone 3 is suppressed.
                harness.clock.advanceBothBy(1_000L)
                harness.emitHrPacket(ACTIVE_HRM_MAC, bpm = 141)
                drainTestAndMainLooper()
                assertEquals(0, notifyCount)

                harness.clock.advanceBothBy(1_000L)
                harness.emitHrPacket(ACTIVE_HRM_MAC, bpm = 139)
                drainTestAndMainLooper()
                assertEquals(0, notifyCount)

                // Shift of >= 2 BPM relative to last displayed (140 -> 142) emits immediately.
                harness.clock.advanceBothBy(1_000L)
                harness.emitHrPacket(ACTIVE_HRM_MAC, bpm = 142)
                drainTestAndMainLooper()
                assertEquals(1, notifyCount)
                assertEquals(
                    "142 BPM • Zone 3 (Tempo)",
                    shadowNotificationManager
                        .getNotification(RecordingService.NOTIFICATION_ID)
                        .extras
                        .getString(Notification.EXTRA_TITLE)
                )

                // 1 BPM shift at +4s since last notify is suppressed.
                harness.clock.advanceBothBy(4_000L)
                harness.emitHrPacket(ACTIVE_HRM_MAC, bpm = 143)
                drainTestAndMainLooper()
                assertEquals(1, notifyCount)

                // At +5s monotonic since last notify, same 143 BPM emits.
                harness.clock.advanceBothBy(1_000L)
                harness.emitHrPacket(ACTIVE_HRM_MAC, bpm = 143)
                drainTestAndMainLooper()
                assertEquals(2, notifyCount)
                assertEquals(
                    "143 BPM • Zone 3 (Tempo)",
                    shadowNotificationManager
                        .getNotification(RecordingService.NOTIFICATION_ID)
                        .extras
                        .getString(Notification.EXTRA_TITLE)
                )

                // Move to Zone 3 upper boundary (149 BPM -> >= 2 BPM shift emits).
                harness.clock.advanceBothBy(1_000L)
                harness.emitHrPacket(ACTIVE_HRM_MAC, bpm = 149)
                drainTestAndMainLooper()
                assertEquals(3, notifyCount)
                assertEquals(
                    "149 BPM • Zone 3 (Tempo)",
                    shadowNotificationManager
                        .getNotification(RecordingService.NOTIFICATION_ID)
                        .extras
                        .getString(Notification.EXTRA_TITLE)
                )

                // Crossing into Zone 4 by just +1 BPM (149 -> 150) emits immediately due to Heart Zone transition!
                harness.clock.advanceBothBy(1_000L)
                harness.emitHrPacket(ACTIVE_HRM_MAC, bpm = 150)
                drainTestAndMainLooper()
                assertEquals(4, notifyCount)
                assertEquals(
                    "150 BPM • Zone 4 (Lactate Threshold)",
                    shadowNotificationManager
                        .getNotification(RecordingService.NOTIFICATION_ID)
                        .extras
                        .getString(Notification.EXTRA_TITLE)
                )

                controller.destroy()
            } finally {
                harness.close()
            }
        }

    private fun recordingState(
        bpm: Int?,
        zone: HeartZone?,
        effectiveDurationMs: Long = 10_000L,
        pendingMicroSessionDiscardPrompt: Boolean = false
    ): SessionUiState.Recording = SessionUiState.Recording(
        sessionId = "notif-test-session",
        activeHrm = activeHrm(),
        startEpochMs = 1_700_000_000_000L,
        effectiveDurationMs = effectiveDurationMs,
        pausedDurationMs = 0L,
        elapsedDurationMs = effectiveDurationMs,
        currentBpm = bpm,
        currentZone = zone,
        sensorContact = true,
        batteryLevel = 90,
        sampleCount = 10,
        zoneCalculation = defaultZoneCalculation,
        pendingMicroSessionDiscardPrompt = pendingMicroSessionDiscardPrompt
    )

    private fun pausedState(
        bpm: Int?,
        zone: HeartZone?,
        effectiveDurationMs: Long = 45_000L,
        reason: PauseReason = PauseReason.USER_MANUAL,
        pendingMicroSessionDiscardPrompt: Boolean = false
    ): SessionUiState.Paused = SessionUiState.Paused(
        sessionId = "notif-test-session",
        activeHrm = activeHrm(),
        reason = reason,
        startEpochMs = 1_700_000_000_000L,
        effectiveDurationMs = effectiveDurationMs,
        pausedDurationMs = 15_000L,
        elapsedDurationMs = effectiveDurationMs + 15_000L,
        currentBpm = bpm,
        currentZone = zone,
        sensorContact = true,
        batteryLevel = 90,
        sampleCount = 45,
        zoneCalculation = defaultZoneCalculation,
        pendingMicroSessionDiscardPrompt = pendingMicroSessionDiscardPrompt
    )

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
            sessionIdGenerator = { "notif-service-session-${++counter}" }
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
