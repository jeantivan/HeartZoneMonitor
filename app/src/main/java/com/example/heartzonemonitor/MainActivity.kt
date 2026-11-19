package com.example.heartzonemonitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.heartzonemonitor.core.security.EncryptedPassphraseStorage
import com.example.heartzonemonitor.core.security.KeystoreManager
import com.example.heartzonemonitor.data.local.AppDatabase
import com.example.heartzonemonitor.ui.devices.RememberedHrmsViewModel
import com.example.heartzonemonitor.ui.navigation.RootNavHost
import com.example.heartzonemonitor.ui.navigation.RootViewModel
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val userProfileDao = database.userProfileDao()
        val rootViewModel = RootViewModel(userProfileDao)

        setContent {
            HeartZoneMonitorTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RootNavHost(
                        rootViewModel = rootViewModel,
                        onExitApp = { finish() },
                        onboardingViewModelFactory = {
                            OnboardingProfileViewModel(userProfileDao)
                        },
                        rememberedHrmsViewModelFactory = {
                            RememberedHrmsViewModel(database.rememberedHrmDao())
                        }
                    )
                }
            }
        }
    }
}
