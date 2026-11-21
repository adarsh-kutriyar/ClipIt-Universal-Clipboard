package com.clipit.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class ClipItApplication : Application() {

    companion object {
        const val CHANNEL_ID_SERVICE = "clipit_service_channel"
        const val CHANNEL_ID_SYNC = "clipit_sync_channel"
        lateinit var instance: ClipItApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.clipit.android.data.ClipboardRepository.init(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID_SERVICE,
                "ClipIt Background Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active Bluetooth Low Energy connection status with your Mac"
                setShowBadge(false)
            }

            val syncChannel = NotificationChannel(
                CHANNEL_ID_SYNC,
                "Clipboard Sync Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications when clipboard content is synced"
            }

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(syncChannel)
        }
    }
}
