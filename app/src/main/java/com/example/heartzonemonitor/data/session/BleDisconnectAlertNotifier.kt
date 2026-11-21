package com.example.heartzonemonitor.data.session

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.heartzonemonitor.R

/**
 * Dispatches an alert when an Active HRM link fails to recover before the Session grace period
 * expires and the Session transitions to `Paused(BLE_CONNECTION_LOST)`.
 */
fun interface BleDisconnectAlertNotifier {
    fun notifyBleConnectionLost(hrmDisplayName: String, sessionId: String)
}

/**
 * No-op [BleDisconnectAlertNotifier] used as the default in unit tests or headless contexts.
 */
object NoOpBleDisconnectAlertNotifier : BleDisconnectAlertNotifier {
    override fun notifyBleConnectionLost(hrmDisplayName: String, sessionId: String) = Unit
}

/**
 * Android notification implementation of [BleDisconnectAlertNotifier] posting high-priority
 * Session alert notifications on [CHANNEL_ID] while respecting API 33+ `POST_NOTIFICATIONS`.
 */
class AndroidBleDisconnectAlertNotifier(
    private val context: Context
) : BleDisconnectAlertNotifier {

    @SuppressLint("MissingPermission")
    override fun notifyBleConnectionLost(hrmDisplayName: String, sessionId: String) {
        val notificationManager =
            context.getSystemService(NotificationManager::class.java) ?: return
        ensureAlertChannel(notificationManager)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val resolvedName = hrmDisplayName.ifBlank { DEFAULT_HRM_DISPLAY_NAME }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Heart Rate Monitor disconnected")
            .setContentText(
                "Session paused after connection loss to $resolvedName. Attempting to reconnect."
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .build()

        try {
            notificationManager.notify(sessionId, NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission may be revoked concurrently; fail safely without crashing recording flow.
        }
    }

    private fun ensureAlertChannel(notificationManager: NotificationManager) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = CHANNEL_DESCRIPTION
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "session_alerts_channel"
        const val CHANNEL_NAME = "Session Alerts"
        const val CHANNEL_DESCRIPTION =
            "Alerts when a Heart Rate Monitor disconnects during an active Session."
        const val NOTIFICATION_ID = 1002
        private const val DEFAULT_HRM_DISPLAY_NAME = "Heart Rate Monitor"
    }
}
