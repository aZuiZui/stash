package io.github.azuizui.stash

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ca.pkay.rcloneexplorer.Activities.MainActivity
import ca.pkay.rcloneexplorer.R

/** Keeps the local teldrive server running while the app (or a sync task) may need it. */
class TeldriveService : Service() {

    @Volatile private var running = false
    private var watchdog: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (!running) {
            running = true
            watchdog = Thread {
                // Restart teldrive if it dies (e.g. DB unreachable at boot, NAS restarted).
                while (running) {
                    try {
                        if (!TeldriveServer.isRunning()) TeldriveServer.start(this)
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "teldrive start failed", e)
                    }
                    try { Thread.sleep(15_000) } catch (e: InterruptedException) { break }
                }
            }.apply { isDaemon = true; name = "teldrive-watchdog"; start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        watchdog?.interrupt()
        TeldriveServer.stop()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Teldrive server", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_twotone_cloud_24)
            .setContentTitle("Stash: teldrive server running")
            .setContentText("Local server on 127.0.0.1:${TeldriveServer.PORT}")
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val TAG = "TeldriveService"
        private const val CHANNEL = "teldrive_server"
        private const val NOTIFICATION_ID = 0x7e1d
        const val ACTION_STOP = "io.github.azuizui.stash.STOP"

        fun start(ctx: Context) {
            if (!TeldriveServer.hasDb(ctx)) return
            ContextCompat.startForegroundService(ctx, Intent(ctx, TeldriveService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TeldriveService::class.java))
        }
    }
}

/** Starts the server after boot so scheduled sync tasks find it. */
class TeldriveBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && TeldriveServer.isSetUp(context)) {
            TeldriveService.start(context)
        }
    }
}
