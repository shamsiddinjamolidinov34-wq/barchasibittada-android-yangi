package uz.barchasibittada

import android.util.Base64
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Answer(val text: String, val sources: List<String> = emptyList())

class KeyCheck(val ok: Boolean, val message: String, val models: List<String> = emptyList())

object Api {
    private const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta"
    private const val GEMINI_UPLOAD = "https://generativelanguage.googleapis.com/upload/v1beta/files"
    private const val OPENAI_BASE = "https://api.openai.com/v1"
    private const val XAI_BASE = "https://api.x.ai/v1"
    private const val ANTHROPIC_URL = "https://api.anthropic.com/v1/messages"
    private const val ANTHROPIC_MODELS = "https://api.anthropic.com/v1/models?limit=1"
    private const val ANTHROPIC_VERSION = "2023-06-01"

    /** Shu hajmdan kichik fayllar so'rov ichida yuboriladi, kattalari Gemini Files API orqali yuklanadi. */
    const val INLINE_LIMIT = 14L * 1024L * 1024L

    private fun geminiHeaders(key: String): Map<String, String> = mapOf("x-goog-api-key" to key)
    private fun bearer(key: String): Map<String, String> = mapOf("Authorization" to "Bearer " + key)
    private fun anthropicHeaders(key: String): Map<String, String> =
        mapOf("x-api-key" to key, "anthropic-version" to ANTHROPIC_VERSION)

    private fun roleOf(m: ChatMsg): String = if (m.role == "assistant") "assistant" else "user"

    private fun b64(data: ByteArray): String = Base64.encodeToString(data, Base64.NO_WRAP)

    private fun needsCompletionTokens(model: String): Boolean {
        val m = model.lowercase()
        return !(m.startsWith("gpt-4") || m.startsWith("gpt-3"))
    }

    private fun addSource(list: MutableList<String>, title: String, url: String) {
        if (url.isBlank()) return
        val t = title.trim()
        val entry = if (t.isNotEmpty() && t != url) t + " — " + url else url
        if (!list.contains(entry)) list.add(entry)
    }

    // ------------------------------------------------------------------
    // Matnli suhbat
    // ------------------------------------------------------------------

    suspend fun ask(
        provider: String,
        key: String,
        model: String,
        system: String,
        msgs: List<ChatMsg>,
        maxTokens: Int,
        search: Boolean
    ): Answer {
        return when (provider) {
            Prov.ANTHROPIC -> askAnthropic(key, model, system, msgs, maxTokens, search)
            Prov.GEMINI -> askGemini(key, model, system, msgs, maxTokens, search)
            Prov.OPENAI -> askOpenAi(key, model, system, msgs, maxTokens, search)
            Prov.XAI -> askGrok(key, model, system, msgs, maxTokens, search)
            else -> throw ProviderError("Noma'lum provayder: " + provider)
        }
    }

    private suspend fun askAnthropic(
        key: String, model: String, system: String, msgs: List<ChatMsg>, maxTokens: Int, search: Boolean
    ): Answer {
        val arr = JSONArray()
        for (m in msgs) arr.put(JSONObject().put("role", roleOf(m)).put("content", m.content))
        val body = JSONObject().put("model", model).put("max_tokens", maxTokens).put("messages", arr)
        if (system.isNotBlank()) body.put("system", system)
        if (search) {
            body.put(
                "tools",
                JSONArray().put(
                    JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 5)
                )
            )
        }
        val data = Http.postJson(ANTHROPIC_URL, anthropicHeaders(key), body, if (search) 300 else 180)
        return parseAnthropic(data)
    }

    private fun parseAnthropic(data: JSONObject): Answer {
        val content = data.optJSONArray("content") ?: JSONArray()
        val parts = ArrayList<String>()
        val sources = ArrayList<String>()
        for (i in 0 until content.length()) {
            val b = content.optJSONObject(i) ?: continue
            val type = b.optString("type")
            if (type == "text") {
                val t = b.optString("text")
                if (t.isNotBlank()) parts.add(t)
            } else if (type == "web_search_tool_result") {
                val res = b.optJSONArray("content") ?: continue
                for (j in 0 until res.length()) {
                    val r = res.optJSONObject(j) ?: continue
                    if (r.optString("type") != "web_search_result") continue
                    addSource(sources, r.optString("title"), r.optString("url"))
                }
            }
        }
        val text = parts.joinToString("\n").trim()
        if (text.isEmpty()) {
            val sr = data.optString("stop_reason")
            if (sr == "max_tokens") throw ProviderError("Javob juda uzun bo'lib ketdi va to'liq chiqmadi.", 0, false, "uzun")
            throw ProviderError("Javob bo'sh keldi.", 0, true)
        }
        return Answer(text, sources.take(8))
    }

    private suspend fun askGemini(
        key: String, model: String, system: String, msgs: List<ChatMsg>, maxTokens: Int, search: Boolean
    ): Answer {
        val contents = JSONArray()
        for (m in msgs) {
            val role = if (m.role == "assistant") "model" else "user"
            contents.put(
                JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", m.content)))
            )
        }
        val limit = maxOf(maxTokens, 2048) * 2
        val body = JSONObject()
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("maxOutputTokens", limit))
        if (system.isNotBlank()) {
            body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        }
        if (search) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        val data = Http.postJson(
            GEMINI_BASE + "/models/" + model + ":generateContent",
            geminiHeaders(key), body, if (search) 300 else 180
        )
        return parseGemini(data)
    }

    private fun parseGemini(data: JSONObject): Answer {
        val cands = data.optJSONArray("candidates")
        if (cands == null || cands.length() == 0) {
            val reason = data.optJSONObject("promptFeedback")?.optString("blockReason") ?: ""
            if (reason.isNotEmpty()) {
                throw ProviderError(
                    "So'rov xavfsizlik filtri tomonidan to'xtatildi (" + reason + "). Savolni boshqacha yozib ko'ring.",
                    400, false, "filtr"
                )
            }
            throw ProviderError("Javob bo'sh keldi.", 0, true)
        }
        val cand = cands.optJSONObject(0) ?: throw ProviderError("Javob bo'sh keldi.", 0, true)
        val parts = cand.optJSONObject("content")?.optJSONArray("parts")
        val sb = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                if (p.optBoolean("thought", false)) continue
                val t = p.optString("text")
                if (t.isNotEmpty()) sb.append(t)
            }
        }
        val text = sb.toString().trim()
        if (text.isEmpty()) {
            val fr = cand.optString("finishReason")
            if (fr == "MAX_TOKENS") {
                throw ProviderError(
                    "Javob juda uzun bo'lib ketdi va to'liq chiqmadi. Savolni qisqaroq yoki bo'laklab bering.",
                    0, false, "uzun"
                )
            }
            if (fr == "SAFETY" || fr == "PROHIBITED_CONTENT" || fr == "BLOCKLIST" || fr == "RECITATION") {
                throw ProviderError("Javob xavfsizlik filtri tomonidan to'xtatildi (" + fr + ").", 400, false, "filtr")
            }
            throw ProviderError("Javob bo'sh keldi (" + fr + ").", 0, true)
        }
        val sources = ArrayList<String>()
        val chunks = cand.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")
        if (chunks != null) {
            for (i in 0 until chunks.length()) {
                val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
                addSource(sources, web.optString("title"), web.optString("uri"))
            }
        }
        return Answer(text, sources.take(8))
    }

    private suspend fun askOpenAi(
        key: String, model: String, system: String, msgs: List<ChatMsg>, maxTokens: Int, search: Boolean
    ): Answer {
        if (search) {
            return askResponses(OPENAI_BASE, key, model, system, msgs, maxOf(maxTokens * 2, 4096), listOf("web_search"), false)
        }
        val arr = JSONArray()
        if (system.isNotBlank()) arr.put(JSONObject().put("role", "system").put("content", system))
        for (m in msgs) arr.put(JSONObject().put("role", roleOf(m)).put("content", m.content))
        val data = chatCompletions(OPENAI_BASE, key, model, arr, maxTokens, true)
        return parseChatCompletions(data)
    }

    private suspend fun askGrok(
        key: String, model: String, system: String, msgs: List<ChatMsg>, maxTokens: Int, search: Boolean
    ): Answer {
        if (search) {
            // Grok ning internet va X qidiruvi Responses API orqali ishlaydi.
            return askResponses(XAI_BASE, key, model, system, msgs, maxOf(maxTokens, 4000) * 2, listOf("web_search", "x_search"), true)
        }
        val arr = JSONArray()
        if (system.isNotBlank()) arr.put(JSONObject().put("role", "system").put("content", system))
        for (m in msgs) arr.put(JSONObject().put("role", roleOf(m)).put("content", m.content))
        val data = chatCompletions(XAI_BASE, key, model, arr, maxTokens, false)
        return parseChatCompletions(data)
    }

    private suspend fun chatCompletions(
        base: String, key: String, model: String, messages: JSONArray, maxTokens: Int, openAi: Boolean
    ): JSONObject {
        var newStyle = needsCompletionTokens(model)
        for (attempt in 0 until 2) {
            val body = JSONObject().put("model", model).put("messages", messages)
            if (openAi) {
                if (newStyle) body.put("max_completion_tokens", maxOf(maxTokens * 2, 4096)) else body.put("max_tokens", maxTokens)
            }
            try {
                return Http.postJson(base + "/chat/completions", bearer(key), body, 300)
            } catch (e: ProviderError) {
                val m = e.msg.lowercase()
                if (openAi && attempt == 0 && e.code == 400 && (m.contains("max_tokens") || m.contains("max_completion_tokens"))) {
                    newStyle = !newStyle
                    continue
                }
                throw e
            }
        }
        throw ProviderError("Javob olinmadi.", 0, true)
    }

    private fun parseChatCompletions(data: JSONObject): Answer {
        val choices = data.optJSONArray("choices")
        val first = choices?.optJSONObject(0)
        val msg = first?.optJSONObject("message")
        val c = msg?.opt("content")
        var text = ""
        if (c is String) {
            text = c
        } else if (c is JSONArray) {
            val sb = StringBuilder()
            for (i in 0 until c.length()) {
                val part = c.optJSONObject(i) ?: continue
                sb.append(part.optString("text"))
            }
            text = sb.toString()
        }
        text = text.trim()
        if (text.isEmpty()) {
            val fr = first?.optString("finish_reason") ?: ""
            if (fr == "length") throw ProviderError("Javob juda uzun bo'lib ketdi va to'liq chiqmadi.", 0, false, "uzun")
            if (fr == "content_filter") throw ProviderError("Javob xavfsizlik filtri tomonidan to'xtatildi.", 400, false, "filtr")
            throw ProviderError("Javob bo'sh keldi.", 0, true)
        }
        val sources = ArrayList<String>()
        val ann = msg?.optJSONArray("annotations")
        if (ann != null) {
            for (i in 0 until ann.length()) {
                val a = ann.optJSONObject(i) ?: continue
                val u = a.optJSONObject("url_citation") ?: a
                addSource(sources, u.optString("title"), u.optString("url"))
            }
        }
        return Answer(text, sources.take(8))
    }

    /** OpenAI va xAI Responses API: internet qidiruvi vositalari bilan. */
    private suspend fun askResponses(
        base: String, key: String, model: String, system: String, msgs: List<ChatMsg>,
        maxOut: Int, tools: List<String>, noInline: Boolean
    ): Answer {
        val input = JSONArray()
        if (system.isNotBlank()) input.put(JSONObject().put("role", "system").put("content", system))
        for (m in msgs) input.put(JSONObject().put("role", roleOf(m)).put("content", m.content))
        val toolArr = JSONArray()
        for (t in tools) toolArr.put(JSONObject().put("type", t))
        var useInclude = noInline
        for (attempt in 0 until 2) {
            val body = JSONObject()
                .put("model", model)
                .put("input", input)
                .put("tools", toolArr)
                .put("max_output_tokens", maxOut)
                .put("store", false)
            if (useInclude) body.put("include", JSONArray().put("no_inline_citations"))
            try {
                val data = Http.postJson(base + "/responses", bearer(key), body, 400)
                return parseResponses(data)
            } catch (e: ProviderError) {
                if (attempt == 0 && useInclude && e.code == 400) {
                    useInclude = false
                    continue
                }
                throw e
            }
        }
        throw ProviderError("Javob olinmadi.", 0, true)
    }

    private fun parseResponses(data: JSONObject): Answer {
        val sb = StringBuilder()
        val sources = ArrayList<String>()
        val out = data.optJSONArray("output")
        if (out != null) {
            for (i in 0 until out.length()) {
                val item = out.optJSONObject(i) ?: continue
                if (item.optString("type") != "message") continue
                val content = item.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    val c = content.optJSONObject(j) ?: continue
                    if (c.optString("type") != "output_text") continue
                    val t = c.optString("text")
                    if (t.isNotEmpty()) {
                        if (sb.isNotEmpty()) sb.append("\n")
                        sb.append(t)
                    }
                    val ann = c.optJSONArray("annotations")
                    if (ann != null) {
                        for (k in 0 until ann.length()) {
                            val a = ann.optJSONObject(k) ?: continue
                            addSource(sources, a.optString("title"), a.optString("url"))
                        }
                    }
                }
            }
        }
        if (sb.isEmpty()) {
            val ot = data.optString("output_text")
            if (ot.isNotEmpty()) sb.append(ot)
        }
        val cites = data.optJSONArray("citations")
        if (cites != null) {
            for (i in 0 until cites.length()) {
                val u = cites.optString(i)
                if (u.startsWith("http")) addSource(sources, "", u)
            }
        }
        // Matn ichidagi [[1]](https://...) ko'rinishidagi havolalarni olib tashlaymiz
        var text = sb.toString()
        text = Regex("\\[\\[\\d+\\]\\]\\([^)]*\\)").replace(text, "").trim()
        if (text.isEmpty()) {
            val st = data.optString("status")
            if (st == "incomplete") throw ProviderError("Javob juda uzun bo'lib ketdi va to'liq chiqmadi.", 0, false, "uzun")
            throw ProviderError("Javob bo'sh keldi.", 0, true)
        }
        return Answer(text, sources.take(8))
    }

    // ------------------------------------------------------------------
    // Rasm / hujjat / audio / video tahlili
    // ------------------------------------------------------------------

    /** Gemini uchun fayl qismi: kichik bo'lsa so'rov ichida, katta bo'lsa Files API orqali. */
    suspend fun geminiPart(key: String, file: File, mime: String, displayName: String): JSONObject {
        if (file.length() <= INLINE_LIMIT) {
            val data = file.readBytes()
            return JSONObject().put("inline_data", JSONObject().put("mime_type", mime).put("data", b64(data)))
        }
        val uri = geminiUpload(key, file, mime, displayName)
        return JSONObject().put("file_data", JSONObject().put("mime_type", mime).put("file_uri", uri))
    }

    private suspend fun geminiUpload(key: String, file: File, mime: String, displayName: String): String {
        val startBody = JSONObject().put("file", JSONObject().put("display_name", displayName.take(120)))
        val startReq = Request.Builder().url(GEMINI_UPLOAD)
            .header("x-goog-api-key", key)
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", file.length().toString())
            .header("X-Goog-Upload-Header-Content-Type", mime)
            .post(startBody.toString().toRequestBody(Http.JSON))
            .build()
        val startRes = Http.exec(startReq, 120)
        if (startRes.code !in 200..299) throw Http.errorFrom(startRes)
        val uploadUrl = startRes.headers["x-goog-upload-url"]
            ?: throw ProviderError("Fayl yuklash boshlanmadi (Gemini Files).", 0, true)

        val upReq = Request.Builder().url(uploadUrl)
            .header("X-Goog-Upload-Offset", "0")
            .header("X-Goog-Upload-Command", "upload, finalize")
            .post(file.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        val upRes = Http.exec(upReq, 1800)
        if (upRes.code !in 200..299) throw Http.errorFrom(upRes)
        val j = try {
            JSONObject(upRes.text())
        } catch (e: Exception) {
            throw ProviderError("Fayl yuklandi, lekin javob noto'g'ri formatda.", 0, true)
        }
        val f = j.optJSONObject("file") ?: j
        val uri = f.optString("uri")
        val name = f.optString("name")
        if (uri.isEmpty()) throw ProviderError("Fayl yuklandi, lekin manzil olinmadi.", 0, true)
        var state = f.optString("state")
        var tries = 0
        while (state == "PROCESSING" && tries < 150) {
            delay(2000)
            tries += 1
            val g = Http.getJson(GEMINI_BASE + "/" + name, geminiHeaders(key), 60)
            state = g.optString("state")
        }
        if (state == "FAILED") throw ProviderError("Gemini faylni qayta ishlay olmadi.", 0, false)
        return uri
    }

    private suspend fun geminiMedia(key: String, model: String, parts: JSONArray, maxTokens: Int): String {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("maxOutputTokens", maxTokens))
        val data = Http.postJson(
            GEMINI_BASE + "/models/" + model + ":generateContent", geminiHeaders(key), body, 900
        )
        return parseGemini(data).text
    }

    /** Fayl (rasm, PDF, audio, video) + so'rov matni → Gemini javobi. */
    suspend fun analyzeGemini(key: String, model: String, file: File, mime: String, name: String, prompt: String, maxTokens: Int): String {
        val parts = JSONArray().put(JSONObject().put("text", prompt)).put(geminiPart(key, file, mime, name))
        return geminiMedia(key, model, parts, maxTokens)
    }

    /** Gemini uchun rasm baytlari bilan (kichik fayllar). */
    suspend fun analyzeGeminiBytes(key: String, model: String, data: ByteArray, mime: String, prompt: String, maxTokens: Int): String {
        val part = JSONObject().put("inline_data", JSONObject().put("mime_type", mime).put("data", b64(data)))
        val parts = JSONArray().put(JSONObject().put("text", prompt)).put(part)
        return geminiMedia(key, model, parts, maxTokens)
    }

    /** Rasm yoki PDF baytlari bilan (Claude, ChatGPT, Grok). */
    suspend fun analyzeBytes(provider: String, key: String, model: String, data: ByteArray, mime: String, prompt: String, maxTokens: Int): String {
        if (provider == Prov.ANTHROPIC) {
            val source = JSONObject().put("type", "base64").put("media_type", mime).put("data", b64(data))
            val type = if (mime == "application/pdf") "document" else "image"
            val block = JSONObject().put("type", type).put("source", source)
            val content = JSONArray().put(block).put(JSONObject().put("type", "text").put("text", prompt))
            val body = JSONObject().put("model", model).put("max_tokens", maxTokens)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            val d = Http.postJson(ANTHROPIC_URL, anthropicHeaders(key), body, 400)
            return parseAnthropic(d).text
        }
        if (provider == Prov.OPENAI || provider == Prov.XAI) {
            val url = "data:" + mime + ";base64," + b64(data)
            val content = JSONArray()
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url).put("detail", "high")))
                .put(JSONObject().put("type", "text").put("text", prompt))
            val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", content))
            val base = if (provider == Prov.OPENAI) OPENAI_BASE else XAI_BASE
            val d = chatCompletions(base, key, model, msgs, maxTokens, provider == Prov.OPENAI)
            return parseChatCompletions(d).text
        }
        throw ProviderError("Bu provayder faylni ko'ra olmaydi: " + provider)
    }

    // ------------------------------------------------------------------
    // Ovozni matnga aylantirish
    // ------------------------------------------------------------------

    suspend fun sttOpenAi(key: String, file: File, mime: String, name: String): String {
        if (file.length() > 25L * 1024L * 1024L) {
            throw ProviderError("Fayl OpenAI chegarasidan (25 MB) katta.", 413, false, "katta")
        }
        val res = Http.postMultipart(
            OPENAI_BASE + "/audio/transcriptions", bearer(key),
            mapOf("model" to Models.OPENAI_STT_MODEL), "file", name, mime, file, 900
        )
        return try {
            JSONObject(res.text()).optString("text").trim()
        } catch (e: Exception) {
            res.text().trim()
        }
    }

    suspend fun sttXai(key: String, file: File, mime: String, name: String): String {
        val res = Http.postMultipart(XAI_BASE + "/stt", bearer(key), emptyMap(), "file", name, mime, file, 900)
        return try {
            JSONObject(res.text()).optString("text").trim()
        } catch (e: Exception) {
            res.text().trim()
        }
    }

    // ------------------------------------------------------------------
    // Rasm yaratish
    // ------------------------------------------------------------------

    suspend fun imageGemini(key: String, prompt: String): Pair<ByteArray, String> {
        val models = ArrayList<String>()
        val ok = Settings.geminiImageOk
        if (ok.isNotBlank()) models.add(ok)
        for (m in Models.geminiImageModels) if (!models.contains(m)) models.add(m)
        var last: ProviderError? = null
        for (model in models) {
            try {
                val r = imageGeminiOne(key, model, prompt)
                Settings.geminiImageOk = model
                return r
            } catch (e: ProviderError) {
                if (e.keyBad) throw e
                last = e
            }
        }
        throw last ?: ProviderError("Rasm yaratilmadi.", 0, true)
    }

    private suspend fun imageGeminiOne(key: String, model: String, prompt: String): Pair<ByteArray, String> {
        // 1) Imagen uslubi
        try {
            val pb = JSONObject()
                .put("instances", JSONArray().put(JSONObject().put("prompt", prompt)))
                .put("parameters", JSONObject().put("sampleCount", 1))
            val d = Http.postJson(GEMINI_BASE + "/models/" + model + ":predict", geminiHeaders(key), pb, 180)
            val preds = d.optJSONArray("predictions")
            if (preds != null) {
                for (i in 0 until preds.length()) {
                    val item = preds.optJSONObject(i) ?: continue
                    val blob = item.optString("bytesBase64Encoded")
                    if (blob.isNotEmpty()) {
                        var mime = item.optString("mimeType")
                        if (mime.isEmpty()) mime = "image/png"
                        return Pair(Base64.decode(blob, Base64.DEFAULT), mime)
                    }
                }
            }
        } catch (e: ProviderError) {
            if (e.code != 400 && e.code != 404) throw e
        }
        // 2) Gemini rasm modeli
        val gb = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE")))
        val data = Http.postJson(GEMINI_BASE + "/models/" + model + ":generateContent", geminiHeaders(key), gb, 180)
        val cands = data.optJSONArray("candidates")
        if (cands == null || cands.length() == 0) {
            val blocked = data.optJSONObject("promptFeedback")?.optString("blockReason") ?: ""
            throw ProviderError("Rasm yaratilmadi" + (if (blocked.isNotEmpty()) " (" + blocked + ")" else "") + ".", 400, false, "filtr")
        }
        val parts = cands.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                val inl = p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data") ?: continue
                val blob = inl.optString("data")
                if (blob.isNotEmpty()) {
                    var mime = inl.optString("mimeType")
                    if (mime.isEmpty()) mime = inl.optString("mime_type")
                    if (mime.isEmpty()) mime = "image/png"
                    return Pair(Base64.decode(blob, Base64.DEFAULT), mime)
                }
            }
        }
        throw ProviderError("Javobda rasm topilmadi — model bu so'rovni qo'llab-quvvatlamaydi.", 400, false)
    }

    suspend fun imageXai(key: String, prompt: String): Pair<ByteArray, String> {
        var last: ProviderError? = null
        for (model in Models.xaiImageModels) {
            try {
                val body = JSONObject().put("model", model).put("prompt", prompt).put("n", 1).put("response_format", "b64_json")
                val d = Http.postJson(XAI_BASE + "/images/generations", bearer(key), body, 300)
                val arr = d.optJSONArray("data")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val blob = item.optString("b64_json")
                        if (blob.isNotEmpty()) {
                            return Pair(Base64.decode(blob, Base64.DEFAULT), "image/jpeg")
                        }
                        val url = item.optString("url")
                        if (url.startsWith("http")) {
                            val res = Http.exec(Request.Builder().url(url).get().build(), 120)
                            if (res.code in 200..299 && res.body.isNotEmpty()) {
                                val ct = res.headers["Content-Type"] ?: "image/jpeg"
                                return Pair(res.body, ct.substringBefore(";"))
                            }
                        }
                    }
                }
                last = ProviderError("Javobda rasm topilmadi.", 0, true)
            } catch (e: ProviderError) {
                if (e.keyBad) throw e
                last = e
            }
        }
        throw last ?: ProviderError("Rasm yaratilmadi.", 0, true)
    }

    // ------------------------------------------------------------------
    // Kalitni tekshirish (token sarflamaydigan ro'yxat so'rovlari)
    // ------------------------------------------------------------------

    suspend fun checkKey(provider: String, key: String): KeyCheck {
        try {
            when (provider) {
                Prov.GEMINI -> {
                    Http.getJson(GEMINI_BASE + "/models?pageSize=1", geminiHeaders(key), 45)
                    return KeyCheck(true, "Kalit ishlayapti.")
                }
                Prov.ANTHROPIC -> {
                    Http.getJson(ANTHROPIC_MODELS, anthropicHeaders(key), 45)
                    return KeyCheck(true, "Kalit ishlayapti.")
                }
                Prov.OPENAI -> {
                    Http.getJson(OPENAI_BASE + "/models", bearer(key), 45)
                    return KeyCheck(true, "Kalit ishlayapti.")
                }
                Prov.XAI -> {
                    val d = Http.getJson(XAI_BASE + "/models", bearer(key), 45)
                    val ids = ArrayList<String>()
                    val arr = d.optJSONArray("data")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val id = arr.optJSONObject(i)?.optString("id") ?: continue
                            if (isGrokChatModel(id) && !ids.contains(id)) ids.add(id)
                        }
                    }
                    return KeyCheck(true, "Kalit ishlayapti.", ids)
                }
                else -> return KeyCheck(false, "Noma'lum provayder.")
            }
        } catch (e: ProviderError) {
            // 429 — kalit to'g'ri, faqat limit; qolgan holatlarda xabarni qaytaramiz
            if (e.code == 429 && !e.keyBad) return KeyCheck(true, "Kalit to'g'ri, lekin hozir limitga yetilgan.")
            return KeyCheck(false, e.msg)
        }
    }

    private fun isGrokChatModel(id: String): Boolean {
        val low = id.lowercase()
        if (!low.startsWith("grok")) return false
        val bad = listOf("imagine", "image", "video", "voice", "stt", "tts", "transcribe", "embed")
        for (b in bad) if (low.contains(b)) return false
        return true
    }
}
