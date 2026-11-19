package com.example.heartzonemonitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.example.heartzonemonitor.core.security.EncryptedPassphraseStorage
import com.example.heartzonemonitor.core.security.KeystoreManager
import com.example.heartzonemonitor.data.ble.AndroidBlePermissionChecker
import com.example.heartzonemonitor.data.ble.AndroidBleTransport
import com.example.heartzonemonitor.data.ble.BleDeviceDiscoveryService
import com.example.heartzonemonitor.data.ble.BlePermissionChecker
import com.example.heartzonemonitor.data.local.AppDatabase
import com.example.heartzonemonitor.ui.devices.RememberedHrmsViewModel
import com.example.heartzonemonitor.ui.navigation.RootNavHost
import com.example.heartzonemonitor.ui.navigation.RootViewModel
import com.example.heartzonemonitor.ui.onboarding.OnboardingPairingViewModel
import com.example.heartzonemonitor.ui.onboarding.OnboardingProfileViewModel
import com.example.heartzonemonitor.ui.theme.HeartZoneMonitorTheme

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
                            RememberedHrmsViewModel(
                                rememberedHrmDao = rememberedHrmDao,
                                onDisconnectActiveHrm = { macAddress ->
                                    bleTransport.disconnect(macAddress)
                                }
                            )
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        bleTransportInstance?.close()
        super.onDestroy()
    }
}
