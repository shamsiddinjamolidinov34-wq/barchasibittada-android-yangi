package uz.barchasibittada

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Limit tugab to'xtagan ishlarni kalit/limit qayta ochilganda o'zi davom ettiradi: eng yaqin vaqtga signal (alarm) qo'yiladi.
 * Signal kelganda (yoki ilova ochilganda, yangi kalit qo'shilganda) VoiceJobs.resumeDue() ishlarni qayta ishga tushiradi.
 */
object VoiceResume {
    const val ACTION = "uz.barchasibittada.VOICE_RESUME"
    private const val REQ = 7101
    private const val NOTIF_ID = 102

    private fun pending(ctx: Context): PendingIntent {
        val i = Intent(ctx, VoiceResumeReceiver::class.java)
        i.action = ACTION
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, REQ, i, flags)
    }

    fun clock(ms: Long): String = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(ms))

    /** Limit tugagan ishlar uchun eng yaqin davom etish vaqtiga signal qo'yadi (bunday ish yo'q bo'lsa — signalni bekor qiladi). */
    fun reschedule(ctx: Context) {
        val app = ctx.applicationContext
        try {
            val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pending(app)
            var at = Long.MAX_VALUE
            for (j in JobStore.all()) {
                if (j.state == JobState.LIMIT && j.resumeAt > 0L && j.resumeAt < at) at = j.resumeAt
            }
            if (at == Long.MAX_VALUE) {
                am.cancel(pi)
                return
            }
            val whenMs = maxOf(at, System.currentTimeMillis() + 5000L)
            var exact = true
            try {
                if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) exact = false
            } catch (t: Throwable) {
                exact = false
            }
            try {
                if (exact) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
                }
            } catch (t: Throwable) {
                am.set(AlarmManager.RTC_WAKEUP, whenMs, pi)
            }
            Journal.i("Resume", "Avtomatik davom etish rejalashtirildi: " + clock(whenMs) + (if (exact) "" else " (taxminiy vaqt)"))
        } catch (t: Throwable) {
            Journal.w("Resume", "Avtomatik davom etishni rejalashtirib boʻlmadi", t)
        }
    }

    /** Fon xizmatini ishga tushirib bo'lmasa — foydalanuvchiga ilovani ochish haqida bildirishnoma. */
    fun notifyReady(ctx: Context, title: String) {
        try {
            VoiceService.ensureChannels(ctx)
            val open = Intent(ctx, MainActivity::class.java)
            open.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            val pi = PendingIntent.getActivity(ctx, REQ + 1, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(ctx, "result")
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Limit qayta ochildi")
                .setContentText("«" + title + "» ni davom ettirish uchun ilovani oching.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, n)
        } catch (t: Throwable) {
            Journal.w("Resume", "Bildirishnoma yuborib boʻlmadi", t)
        }
    }
}

/** Signal (alarm) va telefon qayta yoqilgani haqidagi xabarlarni qabul qiladi. */
class VoiceResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        try {
            Journal.i("Resume", "Xabar keldi: " + (intent?.action ?: ""))
            VoiceJobs.resumeDue(app)
        } catch (t: Throwable) {
            Journal.e("Resume", "Xabarni qayta ishlab boʻlmadi", t)
        }
    }
}
