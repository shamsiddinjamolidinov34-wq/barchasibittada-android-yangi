package uz.barchasibittada

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VUtil {
    private val BAD_NAME = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")
    private val WS = Regex("\\s+")

    /** Fayl nomi uchun faqat taqiqlangan belgilar olib tashlanadi; boshqa hech narsa qoʻshilmaydi. */
    fun safeName(s: String): String {
        var r = BAD_NAME.replace(s, " ")
        r = WS.replace(r, " ").trim().trim('.', ' ')
        if (r.length > 100) r = r.substring(0, 100).trim()
        return if (r.isEmpty()) "Audio" else r
    }

    /** Davomiylik matni joriy til qoidalari (koʻplik shakllari) bilan: "1 soat 5 daqiqa", "1 hour 5 minutes", "1 час 5 минут". */
    fun fmtDuration(ctx: Context, ms: Long): String {
        val total = ms / 1000
        val h = (total / 3600).toInt()
        val m = ((total % 3600) / 60).toInt()
        val s = (total % 60).toInt()
        val r = ctx.resources
        val parts = ArrayList<String>()
        if (h > 0) parts.add(r.getQuantityString(R.plurals.dur_hours, h, h))
        if (m > 0) parts.add(r.getQuantityString(R.plurals.dur_minutes, m, m))
        if (h == 0 && (s > 0 || parts.isEmpty())) parts.add(r.getQuantityString(R.plurals.dur_seconds, s, s))
        return parts.joinToString(" ")
    }

    fun fmtDateTime(ts: Long): String = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(ts))

    fun fmt1(d: Double): String = String.format(Locale.US, "%.1f", d)

    /** Matnning boshidan n ta belgi (bir qatorga keltirilgan). */
    fun snippet(s: String, n: Int): String {
        val t = WS.replace(s.trim().take(n * 3), " ").trim()
        return if (t.length > n) t.substring(0, n).trim() + "…" else t
    }

    /** Matnning oxiridan n ta belgi (bir qatorga keltirilgan). */
    fun tail(s: String, n: Int): String {
        val t = WS.replace(s.trim().takeLast(n * 3), " ").trim()
        return if (t.length > n) "…" + t.substring(t.length - n).trim() else t
    }

    fun isOnline(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= 23) {
                val n = cm.activeNetwork
                if (n == null) {
                    false
                } else {
                    val c = cm.getNetworkCapabilities(n)
                    c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
            } else {
                @Suppress("DEPRECATION")
                val i = cm.activeNetworkInfo
                i != null && i.isConnected
            }
        } catch (t: Throwable) {
            true
        }
    }
}
