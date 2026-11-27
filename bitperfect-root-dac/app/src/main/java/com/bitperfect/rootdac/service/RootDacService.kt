package com.bitperfect.rootdac.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bitperfect.rootdac.BitPerfectRootApp
import com.bitperfect.rootdac.MainActivity
import com.bitperfect.rootdac.R

/**
 * Pure USB DAC + MediaPlayback Foreground Service (ZERO MediaProjection!).
 * Keeps exclusive libusb DAC session & Root Loopback alive in background across HyperOS / Android 17.
 */
class RootDacService : Service() {

    companion object {
        private const val CHANNEL_ID = "bitperfect_root_dac_channel"
        private const val NOTIFICATION_ID = 2001
        private const val EXTRA_STATUS_TEXT = "extra_status_text"

        private const val ACTION_START_USB = "com.bitperfect.rootdac.action.START_USB"
        private const val ACTION_UPDATE_STATUS = "com.bitperfect.rootdac.action.UPDATE_STATUS"
        private const val ACTION_RELEASE_AND_STOP = "com.bitperfect.rootdac.action.RELEASE_AND_STOP"
        private const val ACTION_STOP = "com.bitperfect.rootdac.action.STOP"

        @Volatile
        private var activeInstance: RootDacService? = null

        fun startConnectedDevice(context: Context, statusText: String) {
            val intent = Intent(context, RootDacService::class.java).apply {
                action = ACTION_START_USB
                putExtra(EXTRA_STATUS_TEXT, statusText)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        /**
         * Background-safe notification update directly via NotificationManager.notify()
         * so switching tracks while inside Apple Music NEVER hits Android 14-17 startService limits!
         */
        fun updateStatus(context: Context, statusText: String) {
            val inst = activeInstance
            if (inst != null) {
                inst.updateNotificationText(statusText)
                return
            }
            val intent = Intent(context, RootDacService::class.java).apply {
                action = ACTION_UPDATE_STATUS
                putExtra(EXTRA_STATUS_TEXT, statusText)
            }
            runCatching { context.startService(intent) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, RootDacService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.stopService(intent) }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RELEASE_AND_STOP -> {
                val app = application as? BitPerfectRootApp
                app?.rootDacManager?.disconnect("Notification release")
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_USB -> {
                acquireWakeLock()
                val statusText = intent.getStringExtra(EXTRA_STATUS_TEXT) ?: "USB DAC 已独占接管"
                startForegroundSafe(statusText)
            }
            ACTION_UPDATE_STATUS -> {
                val statusText = intent.getStringExtra(EXTRA_STATUS_TEXT) ?: return START_STICKY
                updateNotificationText(statusText)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundSafe(statusText: String) {
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        try {
            startForeground(NOTIFICATION_ID, buildNotification(statusText), types)
        } catch (_: Exception) {
            runCatching {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(statusText),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            }
        }
    }

    private fun updateNotificationText(statusText: String) {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification(statusText))
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BitPerfectRootDac::IsoAudio").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { wl ->
            if (wl.isHeld) {
                runCatching { wl.release() }
            }
        }
        wakeLock = null
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val releaseIntent = Intent(this, RootDacService::class.java).apply {
            action = ACTION_RELEASE_AND_STOP
        }
        val releasePi = PendingIntent.getService(
            this,
            1,
            releaseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setContentIntent(contentPi)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.notif_action_release), releasePi)
            .build()
    }
}
