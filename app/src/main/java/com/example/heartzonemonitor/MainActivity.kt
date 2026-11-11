package com.example.heartzonemonitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.heartzonemonitor.prototype.LiveSessionPrototypeScreen
import com.example.heartzonemonitor.ui.theme.HeartZoneMonitorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HeartZoneMonitorTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LiveSessionPrototypeScreen()
                }
            }
        }
    }
}