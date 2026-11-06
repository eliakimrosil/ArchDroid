package com.archdroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

class TerminalForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification())
            acquireLocks()
            ArchDroidApiServer.start(this)
            ArchDroidScheduler.rescheduleAllPersisted(this)
        } catch (_: Throwable) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            TerminalSessionHolder.terminateAllSessions()
            releaseLocks()
            stopForeground(true)
            stopSelf()
            try {
                sendBroadcast(Intent(ACTION_APP_EXIT).setPackage(packageName))
            } catch (_: Throwable) {}
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        ArchDroidApiServer.stop()
        instance = null
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "ArchDroid::RuntimeWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifiLock == null) {
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(mode, "ArchDroid::WifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (_: Throwable) {}
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
        } catch (_: Throwable) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ArchDroid Runtime",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps ArchDroid runtime and CLI agents active in the background"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    fun updateNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Throwable) {}
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, TerminalForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val count = TerminalSessionHolder.sessions.size
        val countText = if (count == 1) "1 session active" else "$count sessions active"

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("ArchDroid")
            .setContentText("$countText • Arch Linux active (agy • claude • codex • hermes)")
            .setSmallIcon(R.drawable.ic_stat_archdroid)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Exit All",
                    pendingStop
                ).build()
            )
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "archdroid_runtime"
        private const val NOTIFICATION_ID = 2026
        const val ACTION_STOP = "com.archdroid.STOP"
        const val ACTION_APP_EXIT = "com.archdroid.APP_EXIT"

        @Volatile
        private var instance: TerminalForegroundService? = null

        fun updateNotification(context: Context) {
            instance?.updateNotification()
        }
    }
}
