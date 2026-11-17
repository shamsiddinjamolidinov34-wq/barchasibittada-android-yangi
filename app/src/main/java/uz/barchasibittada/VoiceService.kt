package uz.barchasibittada

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Fon xizmati: navbatdagi audiolashtirish ishlarini birma-bir bajaradi (ishning o'zi VoiceCore da).
 * Har bir bo'lakdan keyin audio faylga yoziladi va ish holati diskka saqlanadi — ilova o'chsa ham
 * kelgan joyidan davom etadi. Barcha muhim hodisalar va xatolar xato jurnaliga yoziladi.
 */
class VoiceService : Service(), VoiceHost {

    companion object {
        const val ACTION_START = "uz.barchasibittada.VOICE_START"
        const val ACTION_PAUSE = "uz.barchasibittada.VOICE_PAUSE"
        const val ACTION_PAUSE_ALL = "uz.barchasibittada.VOICE_PAUSE_ALL"
        const val EXTRA_ID = "id"
        const val EXTRA_RESET = "reset"
        private const val NOTIF_ID = 101

        @Volatile
        var running = false

        /** Bildirishnoma kanallari: progress — ish davomida, result — yakuniy natija. */
        fun ensureChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            try {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(
                    NotificationChannel("progress", ctx.getString(R.string.ch_progress_name), NotificationManager.IMPORTANCE_LOW)
                )
                nm.createNotificationChannel(
                    NotificationChannel("result", ctx.getString(R.string.ch_result_name), NotificationManager.IMPORTANCE_DEFAULT)
                )
            } catch (t: Throwable) {
                Journal.e("App", "Bildirishnoma kanallarini yaratib boʻlmadi", t)
            }
        }

        fun start(ctx: Context, id: String, reset: Boolean = false) {
            try {
                val i = Intent(ctx, VoiceService::class.java)
                i.action = ACTION_START
                i.putExtra(EXTRA_ID, id)
                i.putExtra(EXTRA_RESET, reset)
                ContextCompat.startForegroundService(ctx, i)
            } catch (t: Throwable) {
                Journal.e("Service", "Fon xizmatini ishga tushirib boʻlmadi", t)
                JobStore.update(id) {
                    it.state = JobState.PAUSED
                    it.message = VMsg.make(R.string.msg_start_failed, t.javaClass.simpleName)
                }
            }
        }

        /**
         * Fondan (signal orqali) ishga tushirish. Android ruxsat bermasa, ish holati o'zgartirilmaydi va false qaytadi.
         */
        fun startQuiet(ctx: Context, id: String, reset: Boolean = false): Boolean {
            return try {
                val i = Intent(ctx, VoiceService::class.java)
                i.action = ACTION_START
                i.putExtra(EXTRA_ID, id)
                i.putExtra(EXTRA_RESET, reset)
                ContextCompat.startForegroundService(ctx, i)
                true
            } catch (t: Throwable) {
                Journal.w("Service", "Fondan xizmatni ishga tushirib boʻlmadi (Android cheklovi): " + t.javaClass.simpleName)
                false
            }
        }

        fun pause(ctx: Context, id: String) {
            try {
                val i = Intent(ctx, VoiceService::class.java)
                i.action = ACTION_PAUSE
                i.putExtra(EXTRA_ID, id)
                ctx.startService(i)
            } catch (t: Throwable) {
                Journal.e("Service", "Pauza buyrugʻini yuborib boʻlmadi", t)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val queue = java.util.LinkedList<String>()
    private var worker: Thread? = null
    private var lastStartId = 0

    @Volatile
    private var curId: String? = null

    @Volatile
    private var stopCur = false
    private var wake: PowerManager.WakeLock? = null

    private val core: VoiceCore by lazy { VoiceCore(applicationContext, this) }

    /** Tilga bogʻliq xabar belgisi (diskka yoziladi, koʻrsatilganda matnga aylanadi). */
    private fun tok(id: Int, vararg a: Any?): String = VMsg.make(id, *a)

    /** Belgini matnga aylantiradi (bildirishnoma va jurnal uchun). Oddiy matn oʻzgarmaydi. */
    private fun txt(stored: String): String = VMsg.text(this, stored)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        ensureChannels(applicationContext)
        Journal.i("Service", "Audiolashtirish xizmati yaratildi")
    }

    override fun onDestroy() {
        running = false
        stopCur = true
        VEngine.cancelAll()
        releaseWake()
        Journal.i("Service", "Audiolashtirish xizmati toʻxtadi")
        super.onDestroy()
    }

    /**
     * Android 15+: dataSync turidagi fon xizmati sutkasiga ~6 soat ishlay oladi. Chegaraga yetganda tizim shu
     * funksiyani chaqiradi; xizmat tez to'xtatilmasa, ilova qulaydi. Ishlar pauzaga qo'yiladi — "Davom ettirish" davom ettiradi.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Journal.w("Service", "Android fon xizmati vaqt chegarasiga yetdi (dataSync, taxminan 6 soat/sutka). Ishlar pauzaga qoʻyildi.")
        pauseAll()
        try {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } catch (t: Throwable) {
        }
        try {
            stopSelf()
        } catch (t: Throwable) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(lock) { lastStartId = startId }
        try {
            goForeground(getString(R.string.notif_working))
        } catch (t: Throwable) {
            Journal.e("Service", "startForeground xatosi (fon ruxsati/bildirishnoma sozlamalarini tekshiring)", t)
        }
        val act = intent?.action
        val id = intent?.getStringExtra(EXTRA_ID)
        if (intent == null) {
            recover()
        } else if (act == ACTION_START && id != null) {
            enqueue(id, intent.getBooleanExtra(EXTRA_RESET, false))
        } else if (act == ACTION_PAUSE && id != null) {
            pauseJob(id)
        } else if (act == ACTION_PAUSE_ALL) {
            pauseAll()
        }
        ensureWorker()
        return START_STICKY
    }

    // ---------------------------------------------------------------- navbat

    private fun recover() {
        val ids = ArrayList<String>()
        for (j in JobStore.all()) if (j.state in JobStore.ACTIVE) ids.add(j.id)
        Journal.w("Service", "Xizmat qayta ishga tushdi (tizim oʻldirgan boʻlishi mumkin). Tiklanadigan ishlar: " + ids.size)
        for (x in ids) enqueue(x, false)
    }

    private fun enqueue(id: String, reset: Boolean) {
        if (reset) KeyPool.clearStates(applicationContext)
        var added = false
        synchronized(lock) {
            if (curId != id && !queue.contains(id)) {
                queue.add(id)
                added = true
            }
        }
        if (added) {
            JobStore.update(id) {
                if (it.state != JobState.RUNNING) {
                    it.state = JobState.QUEUED
                    it.message = tok(R.string.msg_queued)
                }
            }
            Journal.i("Service", "Navbatga qoʻshildi: " + id + (if (reset) " (kalit holati tiklandi)" else ""))
        }
    }

    private fun pauseJob(id: String) {
        if (curId == id) {
            stopCur = true
            VEngine.cancelAll()
            return
        }
        var removed = false
        synchronized(lock) { removed = queue.remove(id) }
        if (removed) {
            JobStore.update(id) {
                it.state = JobState.PAUSED
                it.message = tok(R.string.msg_unqueued)
            }
        }
    }

    private fun pauseAll() {
        stopCur = true
        VEngine.cancelAll()
        val ids = ArrayList<String>()
        synchronized(lock) {
            ids.addAll(queue)
            queue.clear()
        }
        for (x in ids) {
            JobStore.update(x) {
                it.state = JobState.PAUSED
                it.message = VoiceCore.USER_PAUSE
            }
        }
    }

    private fun ensureWorker() {
        var idle = false
        var exitId = 0
        synchronized(lock) {
            if (worker == null) {
                if (queue.isEmpty()) {
                    idle = true
                    exitId = lastStartId
                } else {
                    val t = Thread({ workLoop() }, "tts-worker")
                    worker = t
                    t.start()
                }
            }
        }
        if (idle) finishService(exitId)
    }

    private fun finishService(exitId: Int) {
        if (stopSelfResult(exitId)) {
            try {
                stopForeground(true)
            } catch (t: Throwable) {
            }
        }
    }

    private fun workLoop() {
        acquireWake()
        var exitId = 0
        try {
            while (true) {
                var id: String? = null
                synchronized(lock) {
                    id = queue.poll()
                    if (id == null) {
                        worker = null
                        exitId = lastStartId
                    }
                }
                val cur = id ?: break
                runOne(cur)
            }
        } catch (t: Throwable) {
            Journal.e("Service", "Ishchi oqim kutilmagan xato bilan tugadi", t)
            synchronized(lock) {
                worker = null
                exitId = lastStartId
            }
        } finally {
            releaseWake()
            val ex = exitId
            handler.post { finishService(ex) }
        }
    }

    private fun runOne(id: String) {
        stopCur = false
        curId = id
        val j0 = JobStore.get(id)
        if (j0 == null) {
            curId = null
            return
        }
        try {
            core.process(id)
        } catch (t: Throwable) {
            Journal.e("Job", "Kutilmagan xato: '" + j0.title + "'", t)
            val m = tok(R.string.msg_unexpected, t.javaClass.simpleName + ": " + (t.message ?: ""))
            JobStore.update(id) {
                it.state = JobState.FAILED
                it.message = m
            }
            notifyResult(getString(R.string.notif_failed_title, j0.title), txt(m), id)
        } finally {
            curId = null
        }
    }


    // ---------------------------------------------------------------- VoiceHost (yadro uchun)

    override fun stopRequested(): Boolean = stopCur

    override fun progress(title: String, text: String) {
        updateNotif(title, text)
    }

    override fun result(title: String, text: String, jobId: String) {
        notifyResult(title, text, jobId)
    }

    override fun limitReached(jobId: String, resumeAt: Long) {
        VoiceResume.reschedule(applicationContext)
    }

    // ---------------------------------------------------------------- uyg'oq turish, bildirishnomalar

    private fun acquireWake() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val w = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "barchasibittada:ovoz")
            w.setReferenceCounted(false)
            w.acquire(6L * 60L * 60L * 1000L)
            wake = w
        } catch (t: Throwable) {
            Journal.w("Service", "WakeLock olib boʻlmadi", t)
        }
    }

    override fun refreshWake() {
        try {
            wake?.acquire(6L * 60L * 60L * 1000L)
        } catch (t: Throwable) {
        }
    }

    private fun releaseWake() {
        try {
            val w = wake
            if (w != null && w.isHeld) w.release()
        } catch (t: Throwable) {
        }
        wake = null
    }

    private fun pFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)

    private fun buildNotification(rawTitle: String, rawText: String): Notification {
        val title = txt(rawTitle)
        val text = txt(rawText)
        val openI = Intent(this, MainActivity::class.java)
        openI.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(this, 0, openI, pFlags())
        val pauseI = Intent(this, VoiceService::class.java)
        pauseI.action = ACTION_PAUSE_ALL
        val pause = PendingIntent.getService(this, 1, pauseI, pFlags())
        return NotificationCompat.Builder(this, "progress")
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.act_pause), pause)
            .build()
    }

    private fun goForeground(text: String) {
        val n = buildNotification(getString(R.string.app_name), text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotif(title: String, text: String) {
        try {
            val n = buildNotification(title, text)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, n)
        } catch (t: Throwable) {
        }
    }

    private fun notifyResult(title: String, rawText: String, jobId: String) {
        try {
            val text = txt(rawText)
            val openI = Intent(this, MainActivity::class.java)
            openI.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val open = PendingIntent.getActivity(this, 2, openI, pFlags())
            val n = NotificationCompat.Builder(this, "result")
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(open)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(1000 + (jobId.hashCode() and 0xFFFF), n)
        } catch (t: Throwable) {
            Journal.w("Bildirishnoma", "Natija bildirishnomasini yuborib boʻlmadi", t)
        }
    }
}
