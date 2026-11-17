package org.ledfxmobile.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.ledfxmobile.app.R
import org.ledfxmobile.app.engine.LightEngine
import org.ledfxmobile.app.ui.MainActivity

/** Keeps the light engine alive in the background (screen off, other apps open). */
class EngineService : Service() {

    companion object {
        private const val TAG = "EngineService"
        private const val ACTION_START = "org.ledfxmobile.app.START"
        private const val ACTION_STOP = "org.ledfxmobile.app.STOP"
        private const val ACTION_CONSENT = "org.ledfxmobile.app.CONSENT"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "engine"
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, EngineService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, EngineService::class.java).setAction(ACTION_STOP))
        }

        fun deliverConsent(context: Context, resultCode: Int, data: Intent) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, EngineService::class.java)
                    .setAction(ACTION_CONSENT)
                    .putExtra(EXTRA_CODE, resultCode)
                    .putExtra(EXTRA_DATA, data)
            )
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var withProjection = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                LightEngine.stop()
                LightEngine.releaseProjection()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CONSENT -> {
                handleConsent(intent)
                LightEngine.start()
            }
            else -> {
                goForeground()
                acquireLocks()
                LightEngine.start()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        LightEngine.stop()
        releaseLocks()
        super.onDestroy()
    }

    private fun handleConsent(intent: Intent) {
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        }
        // Android 14+: must already be a mediaProjection foreground service.
        withProjection = data != null
        goForeground()
        acquireLocks()
        if (data == null) return
        try {
            val p = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
            if (p != null) LightEngine.onProjectionGranted(p)
        } catch (e: Exception) {
            Log.e(TAG, "Could not start phone audio capture", e)
            withProjection = false
            goForeground()
        }
    }

    private fun goForeground() {
        var types = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (withProjection) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (types == 0) types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
        ServiceCompat.startForeground(this, NOTIF_ID, notification(), types)
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, EngineService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_running))
            .setContentText(getString(R.string.notif_running_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LedFxMobile::engine")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock == null) {
            // Low-latency Wi-Fi keeps 60 fps UDP smooth when the screen is off.
            wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "LedFxMobile::wifi")
                ?.apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release(); wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release(); wifiLock = null
    }
}
