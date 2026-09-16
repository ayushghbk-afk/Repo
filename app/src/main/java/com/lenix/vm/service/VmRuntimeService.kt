package com.lenix.vm.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lenix.R
import com.lenix.ui.MainActivity

/**
 * Foreground service that keeps a running guest visible to the user (ADR-012).
 *
 * Improved from Stryker's NeoTermService:
 * - Wake lock and WiFi lock to prevent sleep during long operations (like Stryker's acquireLock)
 * - Notification with actions (exit, acquire/release lock)
 * - Proper cleanup on destroy (finishIfRunning equivalent)
 * - Handles onTaskRemoved to stop guest if background not allowed
 * - Foreground service types for Android 14+
 */
class VmRuntimeService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("Lenix", "VmRuntimeService onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_STOP -> {
                Log.d("Lenix", "VmRuntimeService ACTION_STOP")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_ACQUIRE_LOCK -> {
                acquireLock()
                updateNotification()
                return START_STICKY
            }
            ACTION_RELEASE_LOCK -> {
                releaseLock()
                updateNotification()
                return START_STICKY
            }
        }

        ensureChannel()
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d("Lenix", "VmRuntimeService onDestroy, releasing locks")
        releaseLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // If user swipes away app and background not allowed, stop service
        // This prevents orphaned processes (Stryker pattern)
        Log.d("Lenix", "VmRuntimeService onTaskRemoved")
        // Don't auto-stop here — let HomeViewModel decide based on settings
        super.onTaskRemoved(rootIntent)
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Lenix runtime",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps Linux environment running"
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )

        val exitIntent = Intent(this, VmRuntimeService::class.java).apply {
            action = ACTION_STOP
        }
        val exitPending = PendingIntent.getService(
            this,
            0,
            exitIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )

        val lockAcquired = wakeLock != null
        val toggleAction = if (lockAcquired) ACTION_RELEASE_LOCK else ACTION_ACQUIRE_LOCK
        val toggleIntent = Intent(this, VmRuntimeService::class.java).apply {
            action = toggleAction
        }
        val togglePending = PendingIntent.getService(
            this,
            1,
            toggleIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Lenix")
            .setContentText(
                if (lockAcquired) "Linux environment running (wake lock held)"
                else "Linux environment is running"
            )
            .setSmallIcon(R.drawable.ic_stat_lenix)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(if (lockAcquired) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_delete,
                "Stop",
                exitPending,
            )
            .addAction(
                if (lockAcquired) android.R.drawable.ic_lock_idle_lock else android.R.drawable.ic_lock_lock,
                if (lockAcquired) "Release lock" else "Acquire lock",
                togglePending,
            )

        return builder.build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun acquireLock() {
        if (wakeLock == null) {
            try {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "Lenix::RuntimeWakeLock",
                ).apply {
                    acquire()
                }

                val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                wifiLock = wm.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "Lenix::RuntimeWifiLock",
                ).apply {
                    acquire()
                }
                Log.d("Lenix", "Wake and WiFi locks acquired")
            } catch (e: Exception) {
                Log.w("Lenix", "Failed to acquire locks: ${e.message}")
            }
        }
    }

    private fun releaseLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null

            wifiLock?.let {
                if (it.isHeld) it.release()
            }
            wifiLock = null
            Log.d("Lenix", "Wake and WiFi locks released")
        } catch (e: Exception) {
            Log.w("Lenix", "Failed to release locks: ${e.message}")
        }
    }

    companion object {
        const val CHANNEL_ID = "lenix-runtime"
        const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.lenix.action.service.stop"
        const val ACTION_ACQUIRE_LOCK = "com.lenix.action.service.lock.acquire"
        const val ACTION_RELEASE_LOCK = "com.lenix.action.service.lock.release"

        fun start(context: Context) {
            val intent = Intent(context, VmRuntimeService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.w("Lenix", "Failed to start VmRuntimeService: ${e.message}")
                // Fallback for older Android or when foreground service not allowed
                try {
                    context.startService(intent)
                } catch (_: Exception) {
                }
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, VmRuntimeService::class.java))
            } catch (e: Exception) {
                Log.w("Lenix", "Failed to stop VmRuntimeService: ${e.message}")
            }
        }
    }
}
