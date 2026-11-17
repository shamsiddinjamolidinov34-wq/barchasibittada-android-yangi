package uz.barchasibittada

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Uzoq ishlar (audiokitob, maqola, kitob) paytida ilovani fonda tirik ushlab turadi.
 */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra("text") ?: "Ish bajarilmoqda"
        try {
            val n = buildNotification(this, text)
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "ishlar"
        private const val NOTIF_ID = 4711

        private fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(CHANNEL) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL, "Uzoq ishlar", NotificationManager.IMPORTANCE_LOW)
                    )
                }
            }
        }

        private fun buildNotification(ctx: Context, text: String): Notification {
            ensureChannel(ctx)
            val open = Intent(ctx, MainActivity::class.java)
            val pi = android.app.PendingIntent.getActivity(
                ctx, 0, open, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            return Notification.Builder(ctx, CHANNEL)
                .setContentTitle("barchasibittada")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        }

        fun start(text: String) {
            try {
                val ctx = Store.app
                val i = Intent(ctx, KeepAliveService::class.java)
                i.putExtra("text", text)
                ctx.startForegroundService(i)
            } catch (e: Exception) {
                // xizmat ishga tushmasa ham ish ilova ochiq paytda davom etadi
            }
        }

        fun update(text: String) {
            try {
                val ctx = Store.app
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID, buildNotification(ctx, text))
            } catch (e: Exception) {
                // e'tiborsiz
            }
        }

        fun stop() {
            try {
                val ctx = Store.app
                ctx.stopService(Intent(ctx, KeepAliveService::class.java))
            } catch (e: Exception) {
                // e'tiborsiz
            }
        }
    }
}
