package com.mineradio.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log

class MineradioApp : Application() {

    companion object {
        const val CHANNEL_PLAYBACK = "mineradio_playback"
        lateinit var instance: MineradioApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            createNotificationChannel()
        } catch (e: Exception) {
            Log.w("MineradioApp", "Notification channel failed: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_PLAYBACK,
                "音乐播放",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "播放控制" }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
