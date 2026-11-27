package com.bitperfect.usbbridge.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bitperfect.usbbridge.BitPerfectApp
import com.bitperfect.usbbridge.MainActivity
import com.bitperfect.usbbridge.R

/**
 * In-process callback registry delivering the MediaProjection token strictly AFTER
 * UsbBridgeService has called startForeground(..., FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION).
 * This satisfies Android 14-17's strict MediaProjection FGS enforcement without SecurityException.
 */
object MediaProjectionBridgeHolder {
    @Volatile
    var onProjectionReady: ((MediaProjection) -> Unit)? = null

    @Volatile
    var onProjectionStopped: (() -> Unit)? = null

    @Volatile
    var onProjectionError: ((String) -> Unit)? = null
}

/**
 * Foreground Service keeping both the exclusive libusb DAC connection (CONNECTED_DEVICE),
 * continuous background audio playback (MEDIA_PLAYBACK — critical against HyperOS millet freezer),
 * and optional MediaProjection capture alive with PARTIAL_WAKE_LOCK.
 */
class UsbBridgeService : Service() {

    companion object {
        private const val CHANNEL_ID = "bitperfect_usb_bridge_channel"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_STATUS_TEXT = "extra_status_text"
        private const val EXTRA_MP_RESULT_CODE = "extra_mp_result_code"
        private const val EXTRA_MP_RESULT_DATA = "extra_mp_result_data"

        private const val ACTION_START_USB = "com.bitperfect.usbbridge.action.START_USB"
        private const val ACTION_START_PROJECTION = "com.bitperfect.usbbridge.action.START_PROJECTION"
        private const val ACTION_STOP_PROJECTION = "com.bitperfect.usbbridge.action.STOP_PROJECTION"
        private const val ACTION_UPDATE_STATUS = "com.bitperfect.usbbridge.action.UPDATE_STATUS"
        private const val ACTION_RELEASE_AND_STOP = "com.bitperfect.usbbridge.action.RELEASE_AND_STOP"
        private const val ACTION_STOP = "com.bitperfect.usbbridge.action.STOP"

        @Volatile
        private var activeInstance: UsbBridgeService? = null

        fun startConnectedDevice(context: Context, statusText: String) {
            val intent = Intent(context, UsbBridgeService::class.java).apply {
                action = ACTION_START_USB
                putExtra(EXTRA_STATUS_TEXT, statusText)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun startMediaProjectionCapture(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            statusText: String
        ) {
            val intent = Intent(context, UsbBridgeService::class.java).apply {
                action = ACTION_START_PROJECTION
                putExtra(EXTRA_MP_RESULT_CODE, resultCode)
                putExtra(EXTRA_MP_RESULT_DATA, resultData)
                putExtra(EXTRA_STATUS_TEXT, statusText)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stopMediaProjectionCapture(context: Context, statusText: String) {
            val inst = activeInstance
            if (inst != null) {
                inst.downgradeFromProjectionInPlace(statusText)
                return
            }
            val intent = Intent(context, UsbBridgeService::class.java).apply {
                action = ACTION_STOP_PROJECTION
                putExtra(EXTRA_STATUS_TEXT, statusText)
            }
            runCatching { context.startService(intent) }
        }

        /**
         * Background-safe notification update: updates the active FGS instance directly via
         * NotificationManager.notify() so switching songs while the user is inside Apple Music
         * NEVER triggers Android 14-17 BackgroundServiceStartNotAllowedException!
         */
        fun updateStatus(context: Context, statusText: String) {
            val inst = activeInstance
            if (inst != null) {
                inst.updateNotificationText(statusText)
                return
            }
            val intent = Intent(context, UsbBridgeService::class.java).apply {
                action = ACTION_UPDATE_STATUS
                putExtra(EXTRA_STATUS_TEXT, statusText)
            }
            runCatching { context.startService(intent) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, UsbBridgeService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.stopService(intent) }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaProjection: MediaProjection? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            mediaProjection = null
            MediaProjectionBridgeHolder.onProjectionStopped?.invoke()
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RELEASE_AND_STOP -> {
                val app = application as? BitPerfectApp
                app?.usbDacManager?.disconnect("Notification release")
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                releaseMediaProjection()
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_USB -> {
                acquireWakeLock()
                val statusText = intent.getStringExtra(EXTRA_STATUS_TEXT) ?: "USB DAC Connected"
                startForegroundWithTypes(
                    statusText = statusText,
                    includeMediaProjection = false
                )
            }
            ACTION_START_PROJECTION -> {
                acquireWakeLock()
                val statusText = intent.getStringExtra(EXTRA_STATUS_TEXT) ?: "Apple Music 独占直通中"
                // CRITICAL Android 14-17 rule:
                // Call startForeground with MEDIA_PROJECTION BEFORE getMediaProjection()!
                val ok = startForegroundWithTypes(
                    statusText = statusText,
                    includeMediaProjection = true
                )
                if (!ok) {
                    MediaProjectionBridgeHolder.onProjectionError?.invoke(
                        "启动 MEDIA_PROJECTION 前台服务失败"
                    )
                    return START_STICKY
                }

                val resultCode = intent.getIntExtra(EXTRA_MP_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = intent.getParcelableExtra(EXTRA_MP_RESULT_DATA, Intent::class.java)
                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    MediaProjectionBridgeHolder.onProjectionError?.invoke("MediaProjection 授权结果无效")
                    return START_STICKY
                }

                releaseMediaProjection()
                try {
                    val mpm = getSystemService(MediaProjectionManager::class.java)
                    val mp = mpm.getMediaProjection(resultCode, resultData)
                    if (mp == null) {
                        MediaProjectionBridgeHolder.onProjectionError?.invoke("getMediaProjection 返回 null")
                        return START_STICKY
                    }
                    // Mandatory on Android 14+ before starting capture
                    mp.registerCallback(projectionCallback, mainHandler)
                    mediaProjection = mp
                    MediaProjectionBridgeHolder.onProjectionReady?.invoke(mp)
                } catch (e: Exception) {
                    MediaProjectionBridgeHolder.onProjectionError?.invoke(
                        "创建 MediaProjection 失败: ${e.message}"
                    )
                }
            }
            ACTION_STOP_PROJECTION -> {
                downgradeFromProjectionInPlace(
                    intent.getStringExtra(EXTRA_STATUS_TEXT) ?: "USB DAC 待机"
                )
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
        releaseMediaProjection()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundWithTypes(statusText: String, includeMediaProjection: Boolean): Boolean {
        val baseTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        val fullTypes = if (includeMediaProjection) {
            baseTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            baseTypes
        }
        return try {
            startForeground(NOTIFICATION_ID, buildNotification(statusText), fullTypes)
            true
        } catch (_: Exception) {
            // Fallback without MEDIA_PLAYBACK if an OEM ROM restricts combined types
            val fallbackTypes = if (includeMediaProjection) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            runCatching {
                startForeground(NOTIFICATION_ID, buildNotification(statusText), fallbackTypes)
                true
            }.getOrDefault(false)
        }
    }

    private fun downgradeFromProjectionInPlace(statusText: String) {
        mainHandler.post {
            releaseMediaProjection()
            startForegroundWithTypes(statusText = statusText, includeMediaProjection = false)
        }
    }

    private fun updateNotificationText(statusText: String) {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification(statusText))
        }
    }

    private fun releaseMediaProjection() {
        val mp = mediaProjection
        mediaProjection = null
        if (mp != null) {
            runCatching { mp.unregisterCallback(projectionCallback) }
            runCatching { mp.stop() }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BitPerfectUsbBridge::IsoAudio").apply {
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

        val releaseIntent = Intent(this, UsbBridgeService::class.java).apply {
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
