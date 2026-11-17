package uz.barchasibittada

import org.json.JSONObject

enum class JobState { NEW, PREPARING, QUEUED, RUNNING, WAITING, PAUSED, LIMIT, DONE, FAILED }

/** Bitta kitob/matn uchun audiolashtirish ishi. Holati job.json faylida saqlanadi. */
data class VoiceJob(
    val id: String,
    var title: String,
    var srcName: String = "",
    var state: JobState = JobState.NEW,
    var message: String = "",
    var total: Int = 0,
    var offset: Int = 0,
    var audioMs: Long = 0L,
    var audioBytes: Long = 0L,
    var chunksDone: Int = 0,
    var skipped: Int = 0,
    var lastSnippet: String = "",
    var posLabel: String = "",
    var preview: String = "",
    var format: String = "mp3",
    /** Qisqa o'qish (suhbatdagi javob): ro'yxatda ko'rsatilmaydi, tugagach o'chiriladi. */
    var quick: Boolean = false,
    /** Matn skanerdan (OCR) o'qilgan: imlo tuzatish "faqat skanerlangan" rejimida shu belgi bo'yicha ishlaydi. */
    var scanned: Boolean = false,
    /** Limit tugaganda: shu vaqtdan (ms, epoch) keyin avtomatik davom etadi; 0 — belgilanmagan. */
    var resumeAt: Long = 0L,
    /** corrected.dat (tuzatilgan matn) ning tasdiqlangan hajmi, bayt. */
    var corrBytes: Long = 0L,
    /** Imlo tuzatish: tuzatilgan va (xavfsizlik tekshiruvidan o'tmagani uchun) asl holicha qoldirilgan bo'laklar soni. */
    var spellFixed: Int = 0,
    var spellKept: Int = 0,
    /** Ish yaratilganda tanlangan ovoz dvigateli va ovozi (VEngine.*). Bo'sh — eski ish (Gemini). Davom ettirilganda ovoz o'zgarmaydi. */
    var engine: String = "",
    var voice: String = "",
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("title", title)
        o.put("srcName", srcName)
        o.put("state", state.name)
        o.put("message", message)
        o.put("total", total)
        o.put("offset", offset)
        o.put("audioMs", audioMs)
        o.put("audioBytes", audioBytes)
        o.put("chunksDone", chunksDone)
        o.put("skipped", skipped)
        o.put("lastSnippet", lastSnippet)
        o.put("posLabel", posLabel)
        o.put("preview", preview)
        o.put("format", format)
        o.put("quick", quick)
        o.put("scanned", scanned)
        o.put("resumeAt", resumeAt)
        o.put("corrBytes", corrBytes)
        o.put("spellFixed", spellFixed)
        o.put("spellKept", spellKept)
        o.put("engine", engine)
        o.put("voice", voice)
        o.put("createdAt", createdAt)
        o.put("updatedAt", updatedAt)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): VoiceJob {
            val st = try {
                JobState.valueOf(o.optString("state", "NEW"))
            } catch (e: Exception) {
                JobState.NEW
            }
            return VoiceJob(
                id = o.getString("id"),
                title = o.optString("title", "Audio"),
                srcName = o.optString("srcName", ""),
                state = st,
                message = o.optString("message", ""),
                total = o.optInt("total", 0),
                offset = o.optInt("offset", 0),
                audioMs = o.optLong("audioMs", 0L),
                audioBytes = o.optLong("audioBytes", 0L),
                chunksDone = o.optInt("chunksDone", 0),
                skipped = o.optInt("skipped", 0),
                lastSnippet = o.optString("lastSnippet", ""),
                posLabel = o.optString("posLabel", ""),
                preview = o.optString("preview", ""),
                format = o.optString("format", "mp3"),
                quick = o.optBoolean("quick", false),
                scanned = o.optBoolean("scanned", false),
                resumeAt = o.optLong("resumeAt", 0L),
                corrBytes = o.optLong("corrBytes", 0L),
                spellFixed = o.optInt("spellFixed", 0),
                spellKept = o.optInt("spellKept", 0),
                engine = o.optString("engine", ""),
                voice = o.optString("voice", ""),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
            )
        }
    }
}
