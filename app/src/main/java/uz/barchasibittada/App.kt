package uz.barchasibittada

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        VMsg.init(this)
        Journal.init(this)
        Journal.installCrashHandler()
        Journal.i("App", "Ilova ishga tushdi. " + Journal.deviceInfo())
        logExitReasons()
        try {
            PDFBoxResourceLoader.init(applicationContext)
        } catch (e: Throwable) {
            // PDF matn o'qish ishlamasa ham ilova ochiladi
            Journal.w("App", "PDF kutubxonasi yuklanmadi", e)
        }
        VPrefs.keys(this)
        JobStore.init(this)
        VoiceService.ensureChannels(this)
        Engine.init()
    }

    /** Android 11+: oldingi jarayon nega tugaganini (xotira yetmadi, ANR, tizim o'ldirdi...) jurnalga yozadi. */
    private fun logExitReasons() {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val sp = getSharedPreferences("journal", Context.MODE_PRIVATE)
            val last = sp.getLong("lastExit", 0L)
            var maxTs = last
            val list = am.getHistoricalProcessExitReasons(packageName, 0, 10)
            for (e in list.reversed()) {
                if (e.timestamp <= last) continue
                if (e.timestamp > maxTs) maxTs = e.timestamp
                val r = e.reason
                val name = when (r) {
                    1 -> "ilova oʻzi chiqdi"
                    2 -> "signal bilan toʻxtatildi (tizim/ishlab chiqaruvchi oʻldirgan boʻlishi mumkin)"
                    3 -> "xotira yetmadi (LOW_MEMORY)"
                    4 -> "qulash (CRASH)"
                    5 -> "native qulash"
                    6 -> "ANR (ilova javob bermadi)"
                    7 -> "ishga tushishda xato"
                    8 -> "ruxsat oʻzgardi"
                    9 -> "resursdan ortiqcha foydalanish"
                    10 -> "foydalanuvchi talabi"
                    11 -> "foydalanuvchi toʻxtatdi"
                    12 -> "bogʻliq jarayon oʻldi"
                    13 -> "boshqa sabab"
                    14 -> "muzlatildi (batareya tejash)"
                    15 -> "paket holati oʻzgardi"
                    16 -> "ilova yangilandi"
                    else -> "nomaʼlum"
                }
                val level = if (r == 3 || r == 4 || r == 5 || r == 6 || r == 7 || r == 9 || r == 14) "E" else if (r == 2) "W" else "I"
                val desc = e.description ?: "-"
                Journal.log(
                    level, "OldingiJarayon",
                    "Oldingi jarayon tugadi: " + name + " (kod " + r + "), tavsif: " + desc +
                        ", vaqt: " + VUtil.fmtDateTime(e.timestamp)
                )
            }
            sp.edit().putLong("lastExit", maxTs).apply()
        } catch (t: Throwable) {
            Journal.w("App", "Jarayon tugash sabablarini oʻqib boʻlmadi", t)
        }
    }
}
