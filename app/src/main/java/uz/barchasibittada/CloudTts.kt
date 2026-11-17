package uz.barchasibittada

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * ChatGPT (OpenAI) va Grok (xAI) ovozi. Har bir so'rov natijasi Gemini.Kind turlariga ajratiladi, shuning uchun
 * VoiceCore ularni Gemini bilan bir xil qoidalar bilan boshqaradi (kalit almashtirish, limit kutish, qayta urinish).
 * OpenAI — xom PCM (24 kHz, 16-bit mono), Grok — MP3.
 */
object CloudTts {
    private const val OPENAI_URL = "https://api.openai.com/v1/audio/speech"
    private const val XAI_URL = "https://api.x.ai/v1/tts"

    class Res(
        val kind: Gemini.Kind,
        val bytes: ByteArray? = null,
        val mp3: Boolean = false,
        val rate: Int = 24000,
        val code: Int = 0,
        val retryMs: Long = 0L,
        val message: String = ""
    )

    @Volatile
    private var active: HttpURLConnection? = null

    /** Ketayotgan so'rovni uzadi (foydalanuvchi «Pauza» bosganda). */
    fun cancel() {
        val c = active ?: return
        Thread {
            try {
                c.disconnect()
            } catch (t: Throwable) {
            }
        }.start()
    }

    fun openai(key: String, model: String, voice: String, text: String, speedPct: Int, style: String, cancelled: () -> Boolean): Res {
        val o = JSONObject()
        o.put("model", model)
        o.put("input", text)
        o.put("voice", voice)
        o.put("response_format", "pcm")
        val m = model.lowercase(Locale.ROOT)
        if (m.startsWith("gpt-4o")) {
            // gpt-4o-mini-tts: o'qish uslubi «instructions» orqali beriladi (tezlik parametri qabul qilinmaydi).
            if (style.isNotBlank()) o.put("instructions", style.trim())
        } else if (speedPct != 100) {
            o.put("speed", speedPct.coerceIn(25, 400) / 100.0)
        }
        return call(OPENAI_URL, key, o.toString(), false, cancelled)
    }

    fun xai(key: String, voice: String, text: String, cancelled: () -> Boolean): Res {
        val o = JSONObject()
        o.put("text", text)
        o.put("voice_id", voice)
        o.put("language", "auto")
        o.put("output_format", JSONObject().put("codec", "mp3").put("sample_rate", 24000).put("bit_rate", 128000))
        return call(XAI_URL, key, o.toString(), true, cancelled)
    }

    private fun call(url: String, key: String, body: String, mp3: Boolean, cancelled: () -> Boolean): Res {
        var c: HttpURLConnection? = null
        try {
            val data = body.toByteArray(Charsets.UTF_8)
            val conn = URL(url).openConnection() as HttpURLConnection
            c = conn
            active = conn
            conn.requestMethod = "POST"
            conn.connectTimeout = 30000
            conn.readTimeout = 300000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer " + key)
            conn.setFixedLengthStreamingMode(data.size)
            conn.outputStream.use { it.write(data) }
            val code = conn.responseCode
            if (code in 200..299) {
                val raw = conn.inputStream.use { it.readBytes() }
                if (raw.size < 1000) {
                    return Res(Gemini.Kind.NO_AUDIO, code = code, message = "Audio juda qisqa (" + raw.size + " bayt)")
                }
                if (mp3) return Res(Gemini.Kind.OK, raw, true, 24000, code)
                val len = raw.size and 1.inv()
                return Res(Gemini.Kind.OK, if (len == raw.size) raw else raw.copyOf(len), false, 24000, code)
            }
            return classify(code, readErr(conn), conn.getHeaderField("Retry-After"))
        } catch (e: OutOfMemoryError) {
            return Res(Gemini.Kind.BAD_CHUNK, message = "Xotira yetmadi (OutOfMemory) — bo'lak kichraytiriladi")
        } catch (t: Throwable) {
            if (cancelled()) return Res(Gemini.Kind.CANCELLED)
            return Res(Gemini.Kind.NETWORK, message = Gemini.describe(t))
        } finally {
            active = null
            try {
                c?.disconnect()
            } catch (t: Throwable) {
            }
        }
    }

    private fun readErr(c: HttpURLConnection): String {
        val s = c.errorStream ?: return ""
        try {
            val all = s.readBytes()
            return String(if (all.size > 100000) all.copyOf(100000) else all, Charsets.UTF_8)
        } catch (t: Throwable) {
            return ""
        } finally {
            try {
                s.close()
            } catch (t: Throwable) {
            }
        }
    }

    private fun shortMsg(body: String): String {
        try {
            val o = JSONObject(body)
            val e = o.opt("error")
            if (e is JSONObject) {
                val m = e.optString("message", "")
                if (m.isNotBlank()) return m.take(400)
            } else if (e is String && e.isNotBlank()) {
                return e.take(400)
            }
            val m2 = o.optString("message", "")
            if (m2.isNotBlank()) return m2.take(400)
        } catch (t: Throwable) {
        }
        return body.take(300)
    }

    private fun classify(code: Int, body: String, retryAfter: String?): Res {
        val low = body.lowercase(Locale.ROOT)
        val msg = shortMsg(body)
        val money = low.contains("insufficient_quota") || low.contains("billing") || low.contains("credit") ||
            low.contains("exceeded your current quota") || low.contains("balance")
        if (code == 402) return Res(Gemini.Kind.QUOTA_DAY, code = code, message = msg)
        if (code == 429) {
            if (money) return Res(Gemini.Kind.QUOTA_DAY, code = code, message = msg)
            val sec = retryAfter?.trim()?.toDoubleOrNull()
            val ms = if (sec != null && sec > 0.0) (sec * 1000.0).toLong() + 500L else 20000L
            return Res(Gemini.Kind.QUOTA_MIN, code = code, retryMs = ms, message = msg)
        }
        if (code == 401) return Res(Gemini.Kind.AUTH, code = code, message = msg)
        if (code == 403) {
            if (money) return Res(Gemini.Kind.QUOTA_DAY, code = code, message = msg)
            return Res(Gemini.Kind.AUTH, code = code, message = msg)
        }
        if (code == 404) return Res(Gemini.Kind.MODEL, code = code, message = msg)
        if (code == 400) {
            if (low.contains("model") && (low.contains("not found") || low.contains("does not exist") || low.contains("invalid model"))) {
                return Res(Gemini.Kind.MODEL, code = code, message = msg)
            }
            return Res(Gemini.Kind.BAD_CHUNK, code = code, message = msg)
        }
        if (code == 408 || code == 409 || code >= 500) return Res(Gemini.Kind.SERVER, code = code, message = msg)
        return Res(Gemini.Kind.BAD_CHUNK, code = code, message = msg)
    }
}

/**
 * Gemini bo'lmagan xizmatlar (ChatGPT, Grok) uchun kalitlar navbati: avval 1-kalit, limitga yetsa yoki yaroqsiz bo'lsa 2-, 3-...
 * (Keys.MAX tagacha). Holat faqat shu ish davomida xotirada turadi: yangi urinishda kalit qayta sinab ko'riladi.
 */
class PlainKeys(private val prov: String) {
    class Slot(val slot: Int, val key: String)

    sealed class Pick {
        class Use(val slot: Slot) : Pick()
        class Wait(val ms: Long) : Pick()
        object None : Pick()
    }

    private val until = HashMap<Int, Long>()
    private val dead = HashSet<Int>()
    private val spent = HashSet<Int>()
    private var keys: List<Slot> = emptyList()

    fun reload() {
        val l = ArrayList<Slot>()
        for (i in 1..Keys.MAX) {
            val k = Keys.getSlot(prov, i)
            if (k.isNotEmpty()) l.add(Slot(i, k))
        }
        keys = l
        Journal.registerSecrets(l.map { it.key })
    }

    fun keyCount(): Int = keys.size

    fun allDead(): Boolean = keys.isNotEmpty() && keys.all { dead.contains(it.slot) }

    /** Kalitlarning hammasi yaroqsiz yoki limiti/krediti tugagan (kutib bo'lmaydi). */
    fun allUnusable(): Boolean = keys.isNotEmpty() && keys.all { dead.contains(it.slot) || spent.contains(it.slot) }

    fun pick(now: Long): Pick {
        var minWait = Long.MAX_VALUE
        for (k in keys) {
            if (dead.contains(k.slot) || spent.contains(k.slot)) continue
            val u = until[k.slot] ?: 0L
            if (u > now) {
                if (u - now < minWait) minWait = u - now
                continue
            }
            return Pick.Use(k)
        }
        return if (minWait != Long.MAX_VALUE) Pick.Wait(minWait) else Pick.None
    }

    fun cooldown(slot: Int, ms: Long) {
        val w = if (ms < 3000L) 3000L else if (ms > 600000L) 600000L else ms
        until[slot] = System.currentTimeMillis() + w
    }

    fun markDead(slot: Int) {
        dead.add(slot)
    }

    fun markSpent(slot: Int) {
        spent.add(slot)
    }

    fun success(slot: Int) {
        until.remove(slot)
    }

    /** Foydalanuvchiga ko'rsatiladigan holat: qaysi kalit nima sababdan ishlamayapti. */
    fun statusText(): String {
        val parts = ArrayList<String>()
        for (k in keys) {
            val st = if (dead.contains(k.slot)) "yaroqsiz" else if (spent.contains(k.slot)) "limiti yoki krediti tugagan" else "band"
            parts.add("kalit " + k.slot + ": " + st)
        }
        return parts.joinToString("; ")
    }
}
