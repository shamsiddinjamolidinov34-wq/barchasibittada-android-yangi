package uz.barchasibittada

import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class Pcm(val bytes: ByteArray, val rate: Int)

/** Gemini TTS javobidagi audioni (xom PCM yoki WAV) 16-bit mono PCM ga keltiradi. */
object AudioUtil {
    private fun le16(b: ByteArray, p: Int): Int = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, p: Int): Int =
        (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
            ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)

    fun toPcm(b: ByteArray, mime: String?): Pcm? {
        if (b.size >= 12 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() &&
            b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte()
        ) return parseWav(b)
        var rate = 24000
        if (mime != null) {
            val m = Regex("rate=(\\d+)").find(mime)
            if (m != null) rate = m.groupValues[1].toIntOrNull() ?: 24000
        }
        val len = b.size and 1.inv()
        return Pcm(if (len == b.size) b else b.copyOf(len), rate)
    }

    private fun parseWav(b: ByteArray): Pcm? {
        var pos = 12
        var rate = 24000
        var channels = 1
        var bits = 16
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.ISO_8859_1)
            val sz = le32(b, pos + 4)
            if (id == "fmt " && pos + 24 <= b.size) {
                channels = le16(b, pos + 10)
                rate = le32(b, pos + 12)
                bits = le16(b, pos + 22)
            } else if (id == "data") {
                val start = pos + 8
                var len = sz
                if (len < 0 || start.toLong() + len > b.size) len = b.size - start
                if (channels != 1 || bits != 16) return null
                len = len and 1.inv()
                return Pcm(b.copyOfRange(start, start + len), rate)
            }
            if (sz < 0 || pos.toLong() + 8 + sz > b.size) break
            pos += 8 + sz + (sz and 1)
        }
        return null
    }
}

/** Gemini API bilan ishlash (HttpURLConnection, qoʻshimcha kutubxonasiz). */
object Gemini {
    const val BASE = "https://generativelanguage.googleapis.com/v1beta"

    /** OCR soʻrovi tanasini yuborish (yuklash) shu muddatda tugamasa, aloqa uziladi: sekin tarmoqda daqiqalab osilib qolmaslik uchun. */
    private const val OCR_UPLOAD_LIMIT_MS = 90000L

    enum class Kind { OK, QUOTA_DAY, QUOTA_MIN, AUTH, REGION, MODEL, SERVER, NETWORK, BAD_CHUNK, NO_AUDIO, CANCELLED }

    class Result(
        val kind: Kind,
        val pcm: ByteArray? = null,
        val rate: Int = 24000,
        val code: Int = 0,
        val retryMs: Long = 0L,
        val message: String = "",
        /** OCR javobi: model qaytargan matn va tugash sababi (STOP, MAX_TOKENS, SAFETY, RECITATION...). */
        val text: String? = null,
        val finish: String? = null
    )

    class KeyCheck(
        val ok: Boolean,
        val code: Int,
        val message: String,
        val ttsModels: List<String>,
        val ocrModels: List<String> = emptyList()
    )

    private class Parsed {
        var data: String? = null
        var mime: String? = null
        var finish: String? = null
        var block: String? = null
        var text: String? = null
    }

    @Volatile
    private var active: HttpURLConnection? = null

    /** Ketayotgan soʻrovni uzadi (foydalanuvchi "Pauza" bosganda). */
    fun cancelActive() {
        val c = active ?: return
        Thread {
            try {
                c.disconnect()
            } catch (t: Throwable) {
            }
        }.start()
    }

    private fun isLegacy(model: String): Boolean {
        val m = model.lowercase(Locale.ROOT)
        return m.contains("2.5") || m.contains("3.1") || m.contains("2.0")
    }

    /**
     * gemini-3.8-* modellari: text — soʻzma-soʻz oʻqiladigan matn, uslub speech_metadata.style da, ovoz voiceConfig.voice da.
     * Eski (2.5/3.1) modellar: ovoz prebuiltVoiceConfig.voiceName da, uslub matn oldiga "uslub: " koʻrinishida qoʻshiladi.
     */
    fun buildBody(model: String, voice: String, text: String, style: String): String {
        val legacy = isLegacy(model)
        val part = JSONObject()
        if (legacy) {
            part.put("text", if (style.isNotBlank()) style.trim() + ": " + text else text)
        } else {
            part.put("text", text)
            if (style.isNotBlank()) part.put("speech_metadata", JSONObject().put("style", style.trim()))
        }
        val content = JSONObject()
        content.put("role", "user")
        content.put("parts", JSONArray().put(part))
        val voiceCfg = JSONObject()
        if (legacy) {
            voiceCfg.put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))
        } else {
            voiceCfg.put("voice", voice)
        }
        val gen = JSONObject()
        gen.put("responseModalities", JSONArray().put("AUDIO"))
        gen.put("speechConfig", JSONObject().put("voiceConfig", voiceCfg))
        val root = JSONObject()
        root.put("contents", JSONArray().put(content))
        root.put("generationConfig", gen)
        return root.toString()
    }

    fun synthesize(
        key: String,
        model: String,
        voice: String,
        text: String,
        style: String,
        cancelled: () -> Boolean
    ): Result {
        var c: HttpURLConnection? = null
        try {
            val body = buildBody(model, voice, text, style).toByteArray(Charsets.UTF_8)
            val conn = URL(BASE + "/models/" + model + ":generateContent").openConnection() as HttpURLConnection
            c = conn
            active = conn
            conn.requestMethod = "POST"
            conn.connectTimeout = 30000
            conn.readTimeout = 300000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code in 200..299) {
                val parsed = conn.inputStream.use { parseOk(it) }
                return interpret(parsed)
            }
            return classify(code, readErr(conn))
        } catch (e: OutOfMemoryError) {
            return Result(Kind.BAD_CHUNK, message = "Xotira yetmadi (OutOfMemory) — boʻlak kichraytiriladi")
        } catch (t: Throwable) {
            if (cancelled()) return Result(Kind.CANCELLED)
            return Result(Kind.NETWORK, message = describe(t))
        } finally {
            active = null
            try {
                c?.disconnect()
            } catch (t: Throwable) {
            }
        }
    }

    private fun parseOk(ins: InputStream): Parsed {
        val p = Parsed()
        val r = JsonReader(InputStreamReader(ins, Charsets.UTF_8))
        try {
            walk(r, p)
        } finally {
            try {
                r.close()
            } catch (t: Throwable) {
            }
        }
        return p
    }

    private fun walk(r: JsonReader, p: Parsed) {
        val t = r.peek()
        if (t == JsonToken.BEGIN_OBJECT) {
            r.beginObject()
            while (r.hasNext()) {
                val name = r.nextName()
                if (r.peek() == JsonToken.STRING) {
                    val v = r.nextString()
                    if (name == "data") {
                        if (p.data == null && v.length > 100) p.data = v
                    } else if (name == "mimeType" || name == "mime_type") {
                        if (p.mime == null) p.mime = v
                    } else if (name == "finishReason") {
                        p.finish = v
                    } else if (name == "blockReason") {
                        p.block = v
                    } else if (name == "text") {
                        if (p.text == null) p.text = v.take(300)
                    }
                } else {
                    walk(r, p)
                }
            }
            r.endObject()
        } else if (t == JsonToken.BEGIN_ARRAY) {
            r.beginArray()
            while (r.hasNext()) walk(r, p)
            r.endArray()
        } else {
            r.skipValue()
        }
    }

    private fun interpret(p: Parsed): Result {
        val blk = p.block
        if (blk != null) return Result(Kind.BAD_CHUNK, message = "Matn bloklandi (blockReason=" + blk + ")")
        val data = p.data
        if (data == null) {
            var why = "finishReason=" + (p.finish ?: "?")
            val tx = p.text
            if (tx != null) why += ", model matn qaytardi: " + tx
            return Result(Kind.NO_AUDIO, message = "Audio qaytmadi (" + why + ")")
        }
        val bytes: ByteArray = try {
            Base64.decode(data, Base64.DEFAULT)
        } catch (t: Throwable) {
            return Result(Kind.NO_AUDIO, message = "Base64 dekodlash xatosi")
        }
        val pcm = AudioUtil.toPcm(bytes, p.mime)
            ?: return Result(Kind.NO_AUDIO, message = "Audio formati tanilmadi (" + (p.mime ?: "?") + ")")
        if (pcm.bytes.size < 2000) {
            return Result(Kind.NO_AUDIO, message = "Audio juda qisqa (" + pcm.bytes.size + " bayt)")
        }
        return Result(Kind.OK, pcm = pcm.bytes, rate = pcm.rate)
    }

    private fun classify(code: Int, body: String): Result {
        val low = body.lowercase(Locale.ROOT)
        val msg = shortMsg(body)
        if (code == 429) {
            val zeroQuota = Regex("\"quotavalue\"\\s*:\\s*\"0\"").containsMatchIn(low) || low.contains("limit: 0")
            val daily = zeroQuota || low.contains("perday") || low.contains("per day") ||
                low.contains("daily") || low.contains("requests_per_day")
            val ms = retryMs(body)
            if (daily || ms > 15 * 60 * 1000L) {
                return Result(Kind.QUOTA_DAY, code = code, retryMs = ms, message = msg)
            }
            return Result(Kind.QUOTA_MIN, code = code, retryMs = if (ms > 0L) ms else 60000L, message = msg)
        }
        if (code == 400) {
            if (low.contains("api_key_invalid") || low.contains("api key not valid") ||
                low.contains("api key expired") || low.contains("key is invalid")
            ) return Result(Kind.AUTH, code = code, message = msg)
            if (low.contains("location is not supported") || low.contains("failed_precondition")) {
                return Result(Kind.REGION, code = code, message = msg)
            }
            return Result(Kind.BAD_CHUNK, code = code, message = msg)
        }
        if (code == 401 || code == 403) return Result(Kind.AUTH, code = code, message = msg)
        if (code == 404) return Result(Kind.MODEL, code = code, message = msg)
        if (code == 408 || code == 409 || code >= 500) return Result(Kind.SERVER, code = code, message = msg)
        return Result(Kind.BAD_CHUNK, code = code, message = msg)
    }

    private fun retryMs(body: String): Long {
        val m1 = Regex("\"retryDelay\"\\s*:\\s*\"([0-9.]+)s\"").find(body)
        val m2 = Regex("retry in ([0-9.]+)\\s*s", RegexOption.IGNORE_CASE).find(body)
        val m = m1 ?: m2
        if (m == null) return 0L
        val v = m.groupValues[1].toDoubleOrNull() ?: return 0L
        return (v * 1000).toLong() + 1500L
    }

    private fun shortMsg(body: String): String {
        try {
            val o = JSONObject(body).optJSONObject("error")
            if (o != null) {
                val m = o.optString("message", "")
                val st = o.optString("status", "")
                val s = (if (st.isNotEmpty()) "[" + st + "] " else "") + m
                if (s.isNotBlank()) return s.take(400)
            }
        } catch (t: Throwable) {
        }
        return body.take(300)
    }

    private fun readErr(c: HttpURLConnection): String {
        val s = c.errorStream ?: return ""
        try {
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                if (total < 100000) {
                    bos.write(buf, 0, n)
                    total += n
                }
            }
            return String(bos.toByteArray(), Charsets.UTF_8)
        } catch (t: Throwable) {
            return ""
        } finally {
            try {
                s.close()
            } catch (t: Throwable) {
            }
        }
    }

    fun describe(t: Throwable): String = t.javaClass.simpleName + ": " + (t.message ?: "")

    /** Kalitni TTS soʻrovi yubormasdan (limit sarflamasdan) tekshiradi: modellar roʻyxatini oladi. */
    fun checkKey(key: String): KeyCheck {
        var c: HttpURLConnection? = null
        try {
            val conn = URL(BASE + "/models?pageSize=1000").openConnection() as HttpURLConnection
            c = conn
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.setRequestProperty("x-goog-api-key", key)
            val code = conn.responseCode
            if (code in 200..299) {
                val txt = conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
                val arr = JSONObject(txt).optJSONArray("models")
                val names = ArrayList<String>()
                val ocrNames = ArrayList<String>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val n = o.optString("name", "")
                        if (n.lowercase(Locale.ROOT).contains("tts")) names.add(n.removePrefix("models/"))
                        if (isVisionModel(n, o.optJSONArray("supportedGenerationMethods"))) ocrNames.add(n.removePrefix("models/"))
                    }
                }
                return KeyCheck(true, code, "", names, ocrNames)
            }
            return KeyCheck(false, code, shortMsg(readErr(conn)), emptyList())
        } catch (t: Throwable) {
            return KeyCheck(false, 0, describe(t), emptyList())
        } finally {
            try {
                c?.disconnect()
            } catch (t: Throwable) {
            }
        }
    }

    /** Model topilmasa (404) — kalit ruxsat bergan TTS modellar ichidan eng mosini tanlaydi. */
    fun findTtsModel(key: String, exclude: Set<String>): String? {
        val chk = checkKey(key)
        if (!chk.ok) return null
        var best: String? = null
        var bestScore = -1
        for (m in chk.ttsModels) {
            if (exclude.contains(m)) continue
            val l = m.lowercase(Locale.ROOT)
            var sc = 10
            if (l.contains("flash") && !l.contains("lite")) {
                sc = 60
            } else if (l.contains("flash")) {
                sc = 50
            }
            if (l.contains("preview")) sc -= 15
            if (l.contains("3.8")) sc += 20
            if (sc > bestScore) {
                bestScore = sc
                best = m
            }
        }
        return best
    }

    // ---------------------------------------------------------------- OCR: rasmli sahifalarni matnga aylantirish (Gemini Vision)

    /** Ba'zi modellar thinkingConfig ni qabul qilmaydi (400). Bunday modellar eslab qolinadi va keyin usiz yuboriladi. */
    private val noThinkCfg: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())

    /** "gemini-3.8-flash" -> 3, "gemini-2.5-flash" -> 2, tanilmasa 0. */
    private fun genMajor(model: String): Int {
        val m = Regex("^gemini-(\\d+)").find(model.lowercase(Locale.ROOT)) ?: return 0
        return m.groupValues[1].toIntOrNull() ?: 0
    }

    /** OCR uchun yaroqli model: gemini-*-flash (rasm qabul qiladi, generateContent), TTS/rasm yaratish/jonli/embedding emas. */
    private fun isVisionModel(name: String, methods: JSONArray?): Boolean {
        val l = name.lowercase(Locale.ROOT)
        if (!l.startsWith("models/gemini") && !l.startsWith("gemini")) return false
        if (!l.contains("flash")) return false
        for (bad in arrayOf("tts", "image", "embedding", "live", "audio", "transcribe", "robotics", "computer-use", "omni", "lyria", "veo", "cyber", "translate")) {
            if (l.contains(bad)) return false
        }
        if (methods == null) return true
        for (i in 0 until methods.length()) {
            if (methods.optString(i, "") == "generateContent") return true
        }
        return false
    }

    private fun buildOcrBody(model: String, pages: List<Int>, jpegs: List<ByteArray>, withThinking: Boolean): ByteArray {
        var est = 8192
        for (j in jpegs) est += (j.size / 3) * 4 + 200
        val sb = StringBuilder(est)
        sb.append("{\"systemInstruction\":{\"parts\":[{\"text\":").append(JSONObject.quote(OcrPrompt.SYSTEM)).append("}]},")
        sb.append("\"contents\":[{\"role\":\"user\",\"parts\":[")
        for (i in jpegs.indices) {
            // Har bir rasm oldidan sahifa belgisi: javobni sahifalarga aniq ajratish uchun.
            sb.append("{\"text\":").append(JSONObject.quote(OcrParse.marker(pages[i]))).append("},")
            sb.append("{\"inline_data\":{\"mime_type\":\"image/jpeg\",\"data\":\"")
            sb.append(Base64.encodeToString(jpegs[i], Base64.NO_WRAP))
            sb.append("\"}},")
        }
        sb.append("{\"text\":").append(JSONObject.quote(OcrPrompt.task(jpegs.size))).append("}]}],")
        sb.append("\"generationConfig\":{\"maxOutputTokens\":16384")
        val major = genMajor(model)
        // Gemini 3+ modellarida temperature standart qiymatida qoldiriladi (Google tavsiyasi); eskilarida past qilinadi.
        if (major in 1..2) sb.append(",\"temperature\":0.2")
        if (withThinking) {
            // OCR uchun "oʻylash" kerak emas: u faqat vaqt va chiqish tokenlarini yeydi.
            if (major >= 3) {
                sb.append(",\"thinkingConfig\":{\"thinkingLevel\":\"low\"}")
            } else if (major == 2 && model.lowercase(Locale.ROOT).contains("flash")) {
                sb.append(",\"thinkingConfig\":{\"thinkingBudget\":0}")
            }
        }
        sb.append("},")
        // Badiiy matnlarda (urush, sevgi sahnalari...) tarkibiy filtr sababsiz toʻsib qoʻymasligi uchun.
        sb.append("\"safetySettings\":[")
        val cats = arrayOf("HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH", "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT")
        for (i in cats.indices) {
            if (i > 0) sb.append(',')
            sb.append("{\"category\":\"").append(cats[i]).append("\",\"threshold\":\"BLOCK_NONE\"}")
        }
        sb.append("]}")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Rasmli sahifalarni (JPEG) matnga aylantiradi.
     * Natija: Kind.OK + text (boʻsh boʻlishi mumkin) + finish (STOP, MAX_TOKENS, SAFETY, RECITATION...) yoki xato turi.
     */
    fun recognize(key: String, model: String, pages: List<Int>, jpegs: List<ByteArray>, cancelled: () -> Boolean): Result {
        val first = ocrOnce(key, model, pages, jpegs, !noThinkCfg.contains(model), cancelled)
        if (first.kind == Kind.BAD_CHUNK && first.code == 400 && !noThinkCfg.contains(model) &&
            first.message.lowercase(Locale.ROOT).contains("think")
        ) {
            noThinkCfg.add(model)
            Journal.w("Gemini", "Model " + model + " thinkingConfig ni qabul qilmadi, usiz qayta yuboriladi: " + first.message)
            return ocrOnce(key, model, pages, jpegs, false, cancelled)
        }
        return first
    }

    private fun ocrOnce(
        key: String,
        model: String,
        pages: List<Int>,
        jpegs: List<ByteArray>,
        withThinking: Boolean,
        cancelled: () -> Boolean
    ): Result {
        var c: HttpURLConnection? = null
        val slow = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            val body = buildOcrBody(model, pages, jpegs, withThinking)
            val conn = URL(BASE + "/models/" + model + ":generateContent").openConnection() as HttpURLConnection
            c = conn
            active = conn
            conn.requestMethod = "POST"
            conn.connectTimeout = 30000
            conn.readTimeout = 180000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.setFixedLengthStreamingMode(body.size)
            // Yuklash cheksiz osilib qolmasligi uchun: OCR_UPLOAD_LIMIT_MS ichida tugamasa, aloqa uziladi (keyin guruh kichraytiriladi).
            val uploading = java.util.concurrent.atomic.AtomicBoolean(true)
            val watchdog = Thread {
                try {
                    Thread.sleep(OCR_UPLOAD_LIMIT_MS)
                    if (uploading.get()) {
                        slow.set(true)
                        conn.disconnect()
                    }
                } catch (e: InterruptedException) {
                }
            }
            watchdog.isDaemon = true
            watchdog.start()
            try {
                conn.outputStream.use { it.write(body) }
            } finally {
                uploading.set(false)
                watchdog.interrupt()
            }
            val code = conn.responseCode
            if (code in 200..299) {
                val txt = conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
                return interpretOcr(txt)
            }
            return classify(code, readErr(conn))
        } catch (e: OutOfMemoryError) {
            return Result(Kind.BAD_CHUNK, message = "Xotira yetmadi (OutOfMemory) — sahifalar guruhi kichraytiriladi")
        } catch (t: Throwable) {
            if (cancelled()) return Result(Kind.CANCELLED)
            if (slow.get()) return Result(Kind.NETWORK, message = "Yuklash juda sekin: " + (OCR_UPLOAD_LIMIT_MS / 1000L) + " s ichida tugamadi")
            return Result(Kind.NETWORK, message = describe(t))
        } finally {
            active = null
            try {
                c?.disconnect()
            } catch (t: Throwable) {
            }
        }
    }

    private fun interpretOcr(txt: String): Result {
        val root: JSONObject = try {
            JSONObject(txt)
        } catch (t: Throwable) {
            return Result(Kind.BAD_CHUNK, message = "Javob JSON emas: " + txt.take(200))
        }
        val block = root.optJSONObject("promptFeedback")?.optString("blockReason", "") ?: ""
        val cands = root.optJSONArray("candidates")
        if (cands == null || cands.length() == 0) {
            if (block.isNotEmpty()) return Result(Kind.BAD_CHUNK, message = "Sahifalar bloklandi (blockReason=" + block + ")")
            return Result(Kind.OK, text = "", finish = "NO_CANDIDATES")
        }
        val cand = cands.optJSONObject(0)
        val finish = cand?.optString("finishReason", "") ?: ""
        val sb = StringBuilder()
        val parts = cand?.optJSONObject("content")?.optJSONArray("parts")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                if (p.optBoolean("thought", false)) continue
                val t = p.optString("text", "")
                if (t.isNotEmpty()) sb.append(t)
            }
        }
        return Result(Kind.OK, text = sb.toString(), finish = finish)
    }

    /** OCR modeli topilmasa (404) — kalit ruxsat bergan Flash modellar ichidan eng yangisini tanlaydi. */
    fun findVisionModel(key: String, exclude: Set<String>): String? {
        val chk = checkKey(key)
        if (!chk.ok) return null
        var best: String? = null
        var bestScore = Int.MIN_VALUE
        for (m in chk.ocrModels) {
            if (exclude.contains(m)) continue
            val l = m.lowercase(Locale.ROOT)
            var sc = if (l.contains("lite")) 40 else 60
            if (l.contains("preview") || l.contains("exp")) sc -= 15
            val ver = Regex("gemini-(\\d+(?:\\.\\d+)?)").find(l)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            sc += (ver * 10).toInt()
            if (sc > bestScore) {
                bestScore = sc
                best = m
            }
        }
        return best
    }

    // ---------------------------------------------------------------- Files API: butun PDF ni bir marta yuklash va katta oraliqlarda o'qish

    private const val UPLOAD_URL = "https://generativelanguage.googleapis.com/upload/v1beta/files"

    /** Yuklash davomida shuncha vaqt bitta bayt ham ketmasa, aloqa uziladi (butun fayl uchun umumiy vaqt chegarasi yo'q). */
    private const val UPLOAD_STALL_MS = 90000L
    private const val MAX_FILE_BYTES = 2000L * 1024L * 1024L

    /** Modelning bir javobdagi chiqish tokenlari chegarasi. Model qabul qilmasa (400) kamaytiriladi va eslab qolinadi. */
    private val outCaps: MutableMap<String, Int> = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun outputCap(model: String): Int = outCaps[model] ?: 65536

    /** Yuqori sahifa aniqligi (mediaResolution) ni qabul qilmagan modellar. */
    private val noResCfg: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Faylni Gemini Files API ga yuklaydi (resumable, oqim bilan: xotirani to'ldirmaydi) va ACTIVE bo'lguncha kutadi.
     * Natija: Kind.OK + text = fayl manzili (uri) + finish = fayl nomi (files/...), yoki xato turi (classify bilan bir xil).
     * progress(foiz) yuklash davomida chaqiriladi.
     */
    fun uploadFile(
        key: String,
        file: java.io.File,
        mime: String,
        displayName: String,
        cancelled: () -> Boolean,
        progress: (Int) -> Unit
    ): Result {
        val size = file.length()
        if (size <= 0L) return Result(Kind.BAD_CHUNK, message = "Fayl bo'sh")
        if (size > MAX_FILE_BYTES) return Result(Kind.BAD_CHUNK, message = "Fayl Gemini chegarasidan (2 GB) katta")
        var c1: HttpURLConnection? = null
        var c2: HttpURLConnection? = null
        val stalled = java.util.concurrent.atomic.AtomicBoolean(false)
        val sending = java.util.concurrent.atomic.AtomicBoolean(false)
        val beat = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        try {
            // 1) yuklash sessiyasini ochish
            val startBody = JSONObject().put("file", JSONObject().put("display_name", displayName.take(120)))
                .toString().toByteArray(Charsets.UTF_8)
            val s1 = URL(UPLOAD_URL).openConnection() as HttpURLConnection
            c1 = s1
            active = s1
            s1.requestMethod = "POST"
            s1.connectTimeout = 30000
            s1.readTimeout = 60000
            s1.doOutput = true
            s1.setRequestProperty("x-goog-api-key", key)
            s1.setRequestProperty("X-Goog-Upload-Protocol", "resumable")
            s1.setRequestProperty("X-Goog-Upload-Command", "start")
            s1.setRequestProperty("X-Goog-Upload-Header-Content-Length", size.toString())
            s1.setRequestProperty("X-Goog-Upload-Header-Content-Type", mime)
            s1.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            s1.setFixedLengthStreamingMode(startBody.size)
            s1.outputStream.use { it.write(startBody) }
            val sc = s1.responseCode
            if (sc !in 200..299) return classify(sc, readErr(s1))
            val upUrl = s1.getHeaderField("x-goog-upload-url")
                ?: return Result(Kind.SERVER, code = sc, message = "Files API yuklash manzilini bermadi")

            // 2) fayl tanasini oqim bilan yuborish va yakunlash
            val u = URL(upUrl).openConnection() as HttpURLConnection
            c2 = u
            active = u
            u.requestMethod = "POST"
            u.connectTimeout = 30000
            u.readTimeout = 300000
            u.doOutput = true
            u.setRequestProperty("X-Goog-Upload-Offset", "0")
            u.setRequestProperty("X-Goog-Upload-Command", "upload, finalize")
            u.setFixedLengthStreamingMode(size)
            sending.set(true)
            beat.set(System.currentTimeMillis())
            val dog = Thread {
                try {
                    var done = false
                    while (!done && sending.get()) {
                        Thread.sleep(5000L)
                        if (sending.get() && System.currentTimeMillis() - beat.get() > UPLOAD_STALL_MS) {
                            stalled.set(true)
                            try {
                                u.disconnect()
                            } catch (t: Throwable) {
                            }
                            done = true
                        }
                    }
                } catch (e: InterruptedException) {
                }
            }
            dog.isDaemon = true
            dog.start()
            try {
                u.outputStream.use { o ->
                    file.inputStream().use { ins ->
                        val buf = ByteArray(256 * 1024)
                        var sent = 0L
                        var lastPct = -1
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            sent += n.toLong()
                            beat.set(System.currentTimeMillis())
                            val pct = (sent * 100L / size).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                progress(pct)
                            }
                            if (cancelled()) throw IOException("bekor qilindi")
                        }
                    }
                }
            } finally {
                sending.set(false)
                dog.interrupt()
            }
            val uc = u.responseCode
            if (uc !in 200..299) return classify(uc, readErr(u))
            val txt = u.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
            val jo: JSONObject = try {
                JSONObject(txt)
            } catch (t: Throwable) {
                return Result(Kind.BAD_CHUNK, message = "Files API javobi JSON emas: " + txt.take(200))
            }
            val fo = jo.optJSONObject("file") ?: jo
            val uri = fo.optString("uri", "")
            val name = fo.optString("name", "")
            var state = fo.optString("state", "")
            if (uri.isEmpty() || name.isEmpty()) return Result(Kind.BAD_CHUNK, message = "Files API fayl manzilini bermadi")

            // 3) ACTIVE bo'lguncha kutish (PDF odatda darhol tayyor bo'ladi)
            var tries = 0
            while (state == "PROCESSING" && tries < 150) {
                if (cancelled()) return Result(Kind.CANCELLED)
                Thread.sleep(2000L)
                tries++
                val g = URL(BASE + "/" + name).openConnection() as HttpURLConnection
                try {
                    g.connectTimeout = 20000
                    g.readTimeout = 30000
                    g.setRequestProperty("x-goog-api-key", key)
                    val gc = g.responseCode
                    if (gc in 200..299) {
                        val gj = JSONObject(g.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
                        state = gj.optString("state", state)
                    } else {
                        return classify(gc, readErr(g))
                    }
                } finally {
                    try {
                        g.disconnect()
                    } catch (t: Throwable) {
                    }
                }
            }
            if (state == "FAILED") return Result(Kind.BAD_CHUNK, message = "Gemini faylni qayta ishlay olmadi (FAILED)")
            if (state == "PROCESSING") return Result(Kind.SERVER, message = "Gemini faylni vaqtida qayta ishlamadi")
            return Result(Kind.OK, text = uri, finish = name)
        } catch (e: OutOfMemoryError) {
            return Result(Kind.BAD_CHUNK, message = "Xotira yetmadi (OutOfMemory)")
        } catch (t: Throwable) {
            if (cancelled()) return Result(Kind.CANCELLED)
            if (stalled.get()) {
                return Result(Kind.NETWORK, message = "Yuklash to'xtab qoldi: " + (UPLOAD_STALL_MS / 1000L) + " s davomida ma'lumot ketmadi")
            }
            return Result(Kind.NETWORK, message = describe(t))
        } finally {
            active = null
            try {
                c1?.disconnect()
            } catch (t: Throwable) {
            }
            try {
                c2?.disconnect()
            } catch (t: Throwable) {
            }
        }
    }

    /** Yuklangan faylni Google'dan o'chiradi. Xato bo'lsa e'tiborsiz: Google baribir 48 soatda o'chiradi. */
    fun deleteFile(key: String, name: String) {
        var c: HttpURLConnection? = null
        try {
            val conn = URL(BASE + "/" + name).openConnection() as HttpURLConnection
            c = conn
            conn.requestMethod = "DELETE"
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("x-goog-api-key", key)
            val rc = conn.responseCode
            Journal.i("OCR", "Gemini fayli o'chirildi: HTTP " + rc)
        } catch (t: Throwable) {
            // e'tiborsiz
        } finally {
            try {
                c?.disconnect()
            } catch (t: Throwable) {
            }
        }
    }

    private fun buildOcrFileBody(model: String, fileUri: String, task: String, maxOut: Int, withThinking: Boolean, hiRes: Boolean): ByteArray {
        val sb = StringBuilder(4096)
        sb.append("{\"systemInstruction\":{\"parts\":[{\"text\":").append(JSONObject.quote(OcrPrompt.SYSTEM)).append("}]},")
        sb.append("\"contents\":[{\"role\":\"user\",\"parts\":[")
        sb.append("{\"file_data\":{\"mime_type\":\"application/pdf\",\"file_uri\":").append(JSONObject.quote(fileUri)).append("}},")
        sb.append("{\"text\":").append(JSONObject.quote(task)).append("}]}],")
        sb.append("\"generationConfig\":{\"maxOutputTokens\":").append(maxOut)
        // Skanerlangan sahifalarda mayda harflar yaxshi o'qilishi uchun sahifa aniqligi yuqori qilinadi.
        if (hiRes) sb.append(",\"mediaResolution\":\"MEDIA_RESOLUTION_HIGH\"")
        val major = genMajor(model)
        if (major in 1..2) sb.append(",\"temperature\":0.2")
        if (withThinking) {
            if (major >= 3) {
                sb.append(",\"thinkingConfig\":{\"thinkingLevel\":\"low\"}")
            } else if (major == 2 && model.lowercase(Locale.ROOT).contains("flash")) {
                sb.append(",\"thinkingConfig\":{\"thinkingBudget\":0}")
            }
        }
        sb.append("},")
        sb.append("\"safetySettings\":[")
        val cats = arrayOf("HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH", "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT")
        for (i in cats.indices) {
            if (i > 0) sb.append(',')
            sb.append("{\"category\":\"").append(cats[i]).append("\",\"threshold\":\"BLOCK_NONE\"}")
        }
        sb.append("]}")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun ocrFileOnce(
        key: String,
        model: String,
        fileUri: String,
        task: String,
        maxOut: Int,
        withThinking: Boolean,
        hiRes: Boolean,
        cancelled: () -> Boolean
    ): Result {
        var c: HttpURLConnection? = null
        try {
            val body = buildOcrFileBody(model, fileUri, task, maxOut, withThinking, hiRes)
            val conn = URL(BASE + "/models/" + model + ":generateContent").openConnection() as HttpURLConnection
            c = conn
            active = conn
            conn.requestMethod = "POST"
            conn.connectTimeout = 30000
            // Katta oraliqda javob daqiqalab yaratilishi mumkin (oqim yo'q): o'qish muddati uzun.
            conn.readTimeout = 600000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code in 200..299) {
                val txt = conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
                return interpretOcr(txt)
            }
            return classify(code, readErr(conn))
        } catch (e: OutOfMemoryError) {
            return Result(Kind.BAD_CHUNK, message = "Xotira yetmadi (OutOfMemory) — oraliq kichraytiriladi")
        } catch (t: Throwable) {
            if (cancelled()) return Result(Kind.CANCELLED)
            return Result(Kind.NETWORK, message = describe(t))
        } finally {
            active = null
            try {
                c?.disconnect()
            } catch (t: Throwable) {
            }
        }
    }

    /**
     * Yuklangan PDF ning first..last sahifalarini o'qiydi (butun fayl rejimi). Model chiqish chegarasini (maxOutputTokens)
     * yoki thinkingConfig ni qabul qilmasa, o'zi kamaytirib/olib tashlab qayta yuboradi va eslab qoladi.
     */
    fun recognizeFile(key: String, model: String, fileUri: String, first: Int, last: Int, total: Int, cancelled: () -> Boolean): Result {
        val task = OcrPrompt.fileTask(first, last, total)
        var cap = outputCap(model)
        var think = !noThinkCfg.contains(model)
        var hi = !noResCfg.contains(model)
        var r = ocrFileOnce(key, model, fileUri, task, cap, think, hi, cancelled)
        var guard = 0
        while (guard < 5 && r.kind == Kind.BAD_CHUNK && r.code == 400) {
            val low = r.message.lowercase(Locale.ROOT)
            if (hi && low.contains("resolution")) {
                noResCfg.add(model)
                hi = false
                Journal.w("Gemini", "Model " + model + " sahifa aniqligi (mediaResolution) ni qabul qilmadi, usiz qayta yuboriladi: " + r.message)
            } else if (think && low.contains("think")) {
                noThinkCfg.add(model)
                think = false
                Journal.w("Gemini", "Model " + model + " thinkingConfig ni qabul qilmadi, usiz qayta yuboriladi: " + r.message)
            } else if (cap > 8192 && (low.contains("max_output_tokens") || low.contains("maxoutputtokens") ||
                    (low.contains("output") && low.contains("token")))
            ) {
                cap /= 2
                if (cap < 8192) cap = 8192
                outCaps[model] = cap
                Journal.w("Gemini", "Model " + model + " chiqish chegarasi " + cap + " tokenga tushirildi: " + r.message)
            } else {
                break
            }
            guard++
            r = ocrFileOnce(key, model, fileUri, task, cap, think, hi, cancelled)
        }
        return r
    }
}
