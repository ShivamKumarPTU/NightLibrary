
package com.example.nightlibrary.setting

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.nightlibrary.R

class FloatingLauncherService : Service() {

    private var bubble: FloatingBubbleView? = null

    override fun onCreate() {
        super.onCreate()
        // Call startForeground immediately with the required type for Android 14+
        internalStartForeground()

        try {
            bubble = FloatingBubbleView(this)
            bubble?.show()
        } catch (e: Exception) {
            // Handle or log potential overlay permission issues
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Ensure startForeground is called on every start command
        internalStartForeground()
        return START_STICKY
    }

    private fun internalStartForeground() {
        val channelId = "floating_launcher"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Floating Launcher",
                NotificationManager.IMPORTANCE_MIN
            )
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Vault Quick Access")
            .setContentText("Optional quick access bubble is active")
            .setSmallIcon(R.drawable.ic_quicklauncher)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        // For Android 14+ (API 34), the foreground service type must be specified
        // if declared in the manifest.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(101, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bubble?.remove()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

