package com.example.heartzonemonitor

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.example.heartzonemonitor.core.security.EncryptedPassphraseStorage
import com.example.heartzonemonitor.core.security.KeystoreManager
import com.example.heartzonemonitor.data.ble.AndroidBlePermissionChecker
import com.example.heartzonemonitor.data.ble.AndroidBleTransport
import com.example.heartzonemonitor.data.ble.BleDeviceDiscoveryService
import com.example.heartzonemonitor.data.ble.BlePermissionChecker
import com.example.heartzonemonitor.data.local.AppDatabase
import com.example.heartzonemonitor.data.session.AndroidBleDisconnectAlertNotifier
import com.example.heartzonemonitor.data.session.SessionRepository
import com.example.heartzonemonitor.service.AndroidRecordingServiceLauncher
import com.example.heartzonemonitor.service.RecordingServiceCoordinator
import com.example.heartzonemonitor.ui.devices.RememberedHrmsViewModel
import com.example.heartzonemonitor.ui.navigation.RootNavHost
import com.example.heartzonemonitor.ui.navigation.RootViewModel
import com.example.heartzonemonitor.ui.onboarding.OnboardingPairingViewModel
import com.example.heartzonemonitor.ui.onboarding.OnboardingProfileViewModel
import com.example.heartzonemonitor.ui.session.LiveSessionViewModel
import com.example.heartzonemonitor.ui.theme.HeartZoneMonitorTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MainActivity : ComponentActivity() {

    internal val database: AppDatabase by lazy {
        try {
            val storage = EncryptedPassphraseStorage(filesDir, KeystoreManager())
            val passphrase = storage.getOrCreatePassphrase()
            AppDatabase.buildEncrypted(this, passphrase)
        } catch (e: Exception) {
            // Fallback for Robolectric / test harnesses where AndroidKeyStore is unavailable
            AppDatabase.buildInMemory(this)
        }
    }

    private val blePermissionChecker: BlePermissionChecker by lazy {
        AndroidBlePermissionChecker(applicationContext)
    }

    private var bleTransportInstance: AndroidBleTransport? = null

    private val bleTransport: AndroidBleTransport by lazy {
        AndroidBleTransport(
            context = applicationContext,
            permissionChecker = blePermissionChecker
        ).also { bleTransportInstance = it }
    }

    private val bleDiscoveryService: BleDeviceDiscoveryService by lazy {
        BleDeviceDiscoveryService(
            bleTransport = bleTransport,
            scope = lifecycleScope,
            permissionChecker = blePermissionChecker
        )
    }

    private val appScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    private val sessionRepository: SessionRepository by lazy {
        SessionRepository.getInstance(
            bleTransport = bleTransport,
            rememberedHrmDao = database.rememberedHrmDao(),
            userProfileDao = database.userProfileDao(),
            sessionDao = database.sessionDao(),
            sampleDao = database.heartRateSampleDao(),
            scope = appScope,
            disconnectAlertNotifier = AndroidBleDisconnectAlertNotifier(applicationContext)
        )
    }

    private var recordingServiceCoordinatorInstance: RecordingServiceCoordinator? = null

    private val recordingServiceCoordinator: RecordingServiceCoordinator by lazy {
        RecordingServiceCoordinator(
            sessionRepository = sessionRepository,
            launcher = AndroidRecordingServiceLauncher(applicationContext),
            scope = appScope
        ).also { recordingServiceCoordinatorInstance = it }
    }

    internal var openLiveSessionRequestId by mutableIntStateOf(0)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openLiveSessionRequestId =
            savedInstanceState?.getInt(STATE_OPEN_LIVE_SESSION_REQUEST_ID, 0) ?: 0
        if (savedInstanceState == null) {
            handleDeepLinkIntent(intent)
        }

        val userProfileDao = database.userProfileDao()
        val rememberedHrmDao = database.rememberedHrmDao()
        val rootViewModel = RootViewModel(
            userProfileDao = userProfileDao,
            rememberedHrmDao = rememberedHrmDao
        )

        setContent {
            HeartZoneMonitorTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RootNavHost(
                        rootViewModel = rootViewModel,
                        onExitApp = { finish() },
                        onboardingViewModelFactory = {
                            OnboardingProfileViewModel(userProfileDao)
                        },
                        onboardingPairingViewModelFactory = {
                            OnboardingPairingViewModel(
                                discoveryService = bleDiscoveryService,
                                rememberedHrmDao = rememberedHrmDao,
                                permissionChecker = blePermissionChecker
                            )
                        },
                        rememberedHrmsViewModelFactory = {
                            recordingServiceCoordinator
                            RememberedHrmsViewModel(
                                rememberedHrmDao = rememberedHrmDao,
                                isSessionActiveFlow = sessionRepository.isSessionActiveFlow,
                                onDisconnectActiveHrm = { macAddress ->
                                    bleTransport.disconnect(macAddress)
                                }
                            )
                        },
                        liveSessionViewModelFactory = {
                            recordingServiceCoordinator
                            LiveSessionViewModel(sessionRepository)
                        },
                        openLiveSessionRequestId = openLiveSessionRequestId
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_OPEN_LIVE_SESSION_REQUEST_ID, openLiveSessionRequestId)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLinkIntent(intent)
    }

    private fun handleDeepLinkIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_LIVE_SESSION, false) == true) {
            openLiveSessionRequestId += 1
            intent.removeExtra(EXTRA_OPEN_LIVE_SESSION)
        }
    }

    override fun onDestroy() {
        recordingServiceCoordinatorInstance?.close()
        recordingServiceCoordinatorInstance = null
        if (!isChangingConfigurations && SessionRepository.getInstanceOrNull()?.isSessionActive != true) {
            bleTransportInstance?.close()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_OPEN_LIVE_SESSION =
            "com.example.heartzonemonitor.extra.OPEN_LIVE_SESSION"
        private const val STATE_OPEN_LIVE_SESSION_REQUEST_ID =
            "com.example.heartzonemonitor.state.OPEN_LIVE_SESSION_REQUEST_ID"

        fun createLiveSessionIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_OPEN_LIVE_SESSION, true)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
    }
}
