package uz.barchasibittada

import kotlinx.coroutines.CancellationException

class ChatOut(val answer: Answer, val provider: String, val model: String)

/** Qaysi kalit va model ishlatilishini hal qiladi, xatoda keyingisiga o'tadi. */
object Ai {
    fun defaultModel(p: String): String = when (p) {
        Prov.GEMINI -> "gemini-3.6-flash"
        Prov.ANTHROPIC -> "claude-sonnet-5-5"
        Prov.OPENAI -> "gpt-6-luna"
        else -> Models.xaiModelIds().firstOrNull() ?: "grok-4.7"
    }

    fun chooseModel(): ModelInfo? {
        val sel = Models.find(Settings.modelId)
        if (sel != null && Keys.has(sel.provider)) return sel
        for (p in Prov.all) {
            if (Keys.has(p)) {
                val m = Models.find(defaultModel(p))
                if (m != null) return m
                val l = Models.ofProvider(p)
                if (l.isNotEmpty()) return l[0]
            }
        }
        return null
    }

    fun noKey(): ProviderError = ProviderError(T.NO_KEY, 0, false, "kalit_yoq")

    /**
     * Provayderlar tartibini (provayder, kalit) juftlariga aylantiradi: har bir provayderning barcha kalitlari
     * (Keys.MAX tagacha) ketma-ket. Bitta kalit limitga yetsa yoki yaroqsiz bo'lsa, keyingi kalitga o'tiladi.
     */
    private fun keyedProviders(order: List<String>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (p in order) for (k in Keys.all(p)) out.add(Pair(p, k))
        return out
    }

    private fun providersFromSelected(sel: ModelInfo): List<String> {
        val out = ArrayList<String>()
        out.add(sel.provider)
        for (p in Keys.withKeys()) if (p != sel.provider) out.add(p)
        return out
    }

    /** Matnli suhbat: tanlangan model, keyin shu provayderning boshqa modellari, keyin boshqa kalitlar. */
    suspend fun chat(system: String, msgs: List<ChatMsg>, maxTokens: Int, search: Boolean): ChatOut {
        val sel = chooseModel() ?: throw noKey()
        var firstError: ProviderError? = null
        val pairs = keyedProviders(providersFromSelected(sel))
        for ((pi, pk) in pairs.withIndex()) {
            val prov = pk.first
            val key = pk.second
            val moreKeys = pi + 1 < pairs.size && pairs[pi + 1].first == prov
            val first = if (prov == sel.provider) sel.model else defaultModel(prov)
            val models = Models.chain(prov, first)
            var useSearch = search
            var idx = 0
            while (idx < models.size && idx < 6) {
                val base = models[idx]
                val model = if (useSearch) Models.searchModel(prov, base) else base
                try {
                    val a = Api.ask(prov, key, model, system, msgs, maxTokens, useSearch)
                    return ChatOut(a, prov, model)
                } catch (e: ProviderError) {
                    if (firstError == null) firstError = e
                    if (e.keyBad) break
                    if (e.kind == "kunlik" || e.kind == "kredit") break
                    if (moreKeys && (e.kind == "daqiqalik" || e.code == 429)) break
                    if (e.kind == "filtr") throw e
                    if (useSearch && (e.code == 400 || e.code == 403 || e.code == 404)) {
                        // qidiruv vositasi ishlamadi: shu modelning o'zida qidiruvsiz urinib ko'ramiz
                        useSearch = false
                        continue
                    }
                    idx += 1
                }
            }
        }
        throw firstError ?: ProviderError("Javob olinmadi.", 0, true)
    }

    // ------------------------------------------------------------------
    // Fayl tahlili
    // ------------------------------------------------------------------
    private fun mediaProviders(kind: String): List<String> {
        val out = ArrayList<String>()
        val sel = Models.find(Settings.modelId)
        if (kind == Kinds.IMAGE) {
            if (sel != null && Keys.has(sel.provider)) out.add(sel.provider)
            for (p in listOf(Prov.GEMINI, Prov.ANTHROPIC, Prov.OPENAI, Prov.XAI)) {
                if (Keys.has(p) && !out.contains(p)) out.add(p)
            }
        } else if (kind == Kinds.PDF) {
            for (p in listOf(Prov.GEMINI, Prov.ANTHROPIC)) if (Keys.has(p)) out.add(p)
        } else {
            if (Keys.has(Prov.GEMINI)) out.add(Prov.GEMINI)
        }
        return out
    }

    private fun missingKeyText(kind: String): String = when (kind) {
        Kinds.IMAGE -> "Rasmni ko'rish uchun Gemini, Claude, ChatGPT yoki Grok kaliti kerak. Sozlamalar bo'limida kalit qo'shing."
        Kinds.PDF -> "Matn qatlami yo'q PDF ni o'qish uchun Gemini yoki Claude kaliti kerak (yoki Kitob asbobidan foydalaning)."
        Kinds.VIDEO -> "Videoni tushunish uchun Gemini kaliti kerak. Sozlamalar bo'limida kalit qo'shing."
        else -> "Audioni tushunish uchun Gemini kaliti kerak. Sozlamalar bo'limida kalit qo'shing."
    }

    /** Rasm, PDF, audio yoki videoni so'rov (prompt) bilan tahlil qiladi. */
    suspend fun analyzeFile(p: Picked, prompt: String, maxTokens: Int): String {
        val kind = Kinds.of(p)
        val order = mediaProviders(kind)
        if (order.isEmpty()) throw ProviderError(missingKeyText(kind), 0, false, "kalit_yoq")
        var imageBytes: ByteArray? = null
        if (kind == Kinds.IMAGE) {
            imageBytes = ImageKit.prepare(p.file)
            if (imageBytes == null) throw ProviderError("Rasmni ochib bo'lmadi.", 0, false)
        }
        var firstError: ProviderError? = null
        val pairs = keyedProviders(order)
        for ((pi, pk) in pairs.withIndex()) {
            val prov = pk.first
            val key = pk.second
            val moreKeys = pi + 1 < pairs.size && pairs[pi + 1].first == prov
            for (model in Models.mediaChain(prov)) {
                try {
                    return oneMedia(prov, key, model, p, kind, imageBytes, prompt, maxTokens)
                } catch (e: ProviderError) {
                    if (firstError == null) firstError = e
                    if (e.keyBad || e.kind == "kunlik" || e.kind == "kredit" || e.kind == "katta" || e.code == 413) break
                    if (moreKeys && (e.kind == "daqiqalik" || e.code == 429)) break
                }
            }
        }
        throw firstError ?: ProviderError("Fayl tahlil qilinmadi.", 0, true)
    }

    private suspend fun oneMedia(
        prov: String, key: String, model: String, p: Picked, kind: String,
        image: ByteArray?, prompt: String, maxTokens: Int
    ): String {
        if (prov == Prov.GEMINI) {
            if (kind == Kinds.IMAGE && image != null) {
                return Api.analyzeGeminiBytes(key, model, image, "image/jpeg", prompt, maxTokens)
            }
            val mime = if (p.mime.isBlank() || p.mime == "application/octet-stream") Files.guessMime(p.name) else p.mime
            return Api.analyzeGemini(key, model, p.file, mime, p.name, prompt, maxTokens)
        }
        if (kind == Kinds.IMAGE && image != null) {
            return Api.analyzeBytes(prov, key, model, image, "image/jpeg", prompt, maxTokens)
        }
        if (kind == Kinds.PDF && prov == Prov.ANTHROPIC) {
            if (p.file.length() > 30L * 1024L * 1024L) {
                throw ProviderError("PDF Claude uchun juda katta (30 MB dan oshdi).", 413, false, "katta")
            }
            return Api.analyzeBytes(prov, key, model, p.file.readBytes(), "application/pdf", prompt, maxTokens)
        }
        throw ProviderError("Bu provayder ushbu fayl turini ko'ra olmaydi.", 0, false)
    }

    /** Kitob sahifasi (JPEG) dan matn o'qiydi. */
    suspend fun ocrPage(jpeg: ByteArray): String {
        val order = ArrayList<String>()
        val sel = Models.find(Settings.modelId)
        if (Keys.has(Prov.GEMINI)) order.add(Prov.GEMINI)
        if (sel != null && Keys.has(sel.provider) && !order.contains(sel.provider)) order.add(sel.provider)
        for (p in listOf(Prov.ANTHROPIC, Prov.OPENAI, Prov.XAI)) if (Keys.has(p) && !order.contains(p)) order.add(p)
        if (order.isEmpty()) throw ProviderError(missingKeyText(Kinds.IMAGE), 0, false, "kalit_yoq")
        var firstError: ProviderError? = null
        val pairs = keyedProviders(order)
        for ((pi, pk) in pairs.withIndex()) {
            val prov = pk.first
            val key = pk.second
            val moreKeys = pi + 1 < pairs.size && pairs[pi + 1].first == prov
            for (model in Models.mediaChain(prov)) {
                try {
                    return if (prov == Prov.GEMINI) {
                        Api.analyzeGeminiBytes(key, model, jpeg, "image/jpeg", P.PAGE, 8192)
                    } else {
                        Api.analyzeBytes(prov, key, model, jpeg, "image/jpeg", P.PAGE, 8192)
                    }
                } catch (e: ProviderError) {
                    if (firstError == null) firstError = e
                    if (e.keyBad || e.kind == "kunlik" || e.kind == "kredit") break
                    if (moreKeys && (e.kind == "daqiqalik" || e.code == 429)) break
                }
            }
        }
        throw firstError ?: ProviderError("Sahifa o'qilmadi.", 0, true)
    }

    // ------------------------------------------------------------------
    // Ovozni matnga aylantirish
    // ------------------------------------------------------------------
    /** mode: "matn" (so'zma-so'z), "tarjima" (o'zbekchaga), "savol" (ovozli savol — to'liq qoida). */
    suspend fun transcribe(p: Picked, mode: String): String {
        val order = ArrayList<String>()
        for (x in listOf(Prov.GEMINI, Prov.OPENAI, Prov.XAI)) if (Keys.has(x)) order.add(x)
        if (order.isEmpty()) throw ProviderError(missingKeyText(Kinds.AUDIO), 0, false, "kalit_yoq")
        val mime = if (p.mime.isBlank() || p.mime == "application/octet-stream") Files.guessMime(p.name) else p.mime
        var firstError: ProviderError? = null
        for (pk in keyedProviders(order)) {
            val prov = pk.first
            val key = pk.second
            try {
                if (prov == Prov.GEMINI) {
                    val prompt = when (mode) {
                        "matn" -> P.TRANSCRIBE + P.TRANSCRIBE_TOOLIQ
                        else -> P.TRANSCRIBE + P.TRANSCRIBE_TIL + P.TRANSCRIBE_TOOLIQ
                    }
                    for (model in Models.mediaChain(Prov.GEMINI)) {
                        try {
                            return Api.analyzeGemini(key, model, p.file, mime, p.name, prompt, 32000)
                        } catch (e: ProviderError) {
                            if (firstError == null) firstError = e
                            if (e.keyBad || e.kind == "kunlik" || e.kind == "kredit") throw e
                        }
                    }
                } else {
                    val raw = if (prov == Prov.OPENAI) Api.sttOpenAi(key, p.file, mime, p.name) else Api.sttXai(key, p.file, mime, p.name)
                    if (mode == "matn") return raw
                    if (raw.isBlank()) return raw
                    // tarjima yoki ovozli savol: matn boshqa tilda bo'lsa o'zbekchaga o'giriladi
                    val tr = try {
                        chat(Uz.translatePrompt("uz") + " Matn allaqachon o'zbek tilida bo'lsa, uni o'zgartirmasdan qaytar.", listOf(ChatMsg("user", raw)), 4096, false)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    return tr?.answer?.text ?: raw
                }
            } catch (e: ProviderError) {
                if (firstError == null) firstError = e
            }
        }
        throw firstError ?: ProviderError("Ovoz matnga aylantirilmadi.", 0, true)
    }

    // ------------------------------------------------------------------
    // Rasm yaratish
    // ------------------------------------------------------------------
    class ImageOut(val bytes: ByteArray, val mime: String, val provider: String)

    suspend fun createImage(prompt: String): ImageOut {
        val order = ArrayList<String>()
        val sel = Models.find(Settings.modelId)
        if (sel != null && sel.provider == Prov.XAI && Keys.has(Prov.XAI)) order.add(Prov.XAI)
        if (Keys.has(Prov.GEMINI) && !order.contains(Prov.GEMINI)) order.add(Prov.GEMINI)
        if (Keys.has(Prov.XAI) && !order.contains(Prov.XAI)) order.add(Prov.XAI)
        if (order.isEmpty()) {
            throw ProviderError("Rasm yaratish uchun Gemini yoki Grok kaliti kerak. Sozlamalar bo'limida kalit qo'shing.", 0, false, "kalit_yoq")
        }
        var firstError: ProviderError? = null
        for (pk in keyedProviders(order)) {
            val prov = pk.first
            val key = pk.second
            try {
                val r = if (prov == Prov.GEMINI) Api.imageGemini(key, prompt) else Api.imageXai(key, prompt)
                return ImageOut(r.first, r.second, prov)
            } catch (e: ProviderError) {
                if (firstError == null) firstError = e
            }
        }
        throw firstError ?: ProviderError("Rasm yaratilmadi.", 0, true)
    }

    // ------------------------------------------------------------------
    // Imlo tuzatish (audiolashtirishdan oldin)
    // ------------------------------------------------------------------
    /**
     * Matnni faqat aniq imlo/OCR xatolaridan tozalatadi (kitob mazmuniga tegmasdan: ko'rsatmalar VoiceCore da).
     * Tez model ishlatiladi (avval Gemini); kalit yoki limit tugasa keyingi kalit/provayderga o'tiladi.
     * Natija xavfsizlik tekshiruvidan VoiceCore da o'tadi.
     */
    suspend fun proofread(system: String, text: String, maxTokens: Int): String {
        val order = ArrayList<String>()
        if (Keys.has(Prov.GEMINI)) order.add(Prov.GEMINI)
        for (p in listOf(Prov.OPENAI, Prov.XAI, Prov.ANTHROPIC)) if (Keys.has(p)) order.add(p)
        if (order.isEmpty()) throw noKey()
        var firstError: ProviderError? = null
        val pairs = keyedProviders(order)
        for ((pi, pk) in pairs.withIndex()) {
            val prov = pk.first
            val key = pk.second
            val moreKeys = pi + 1 < pairs.size && pairs[pi + 1].first == prov
            val models = Models.chain(prov, defaultModel(prov))
            var idx = 0
            while (idx < models.size && idx < 3) {
                try {
                    val a = Api.ask(prov, key, models[idx], system, listOf(ChatMsg("user", text)), maxTokens, false)
                    return a.text
                } catch (e: ProviderError) {
                    if (firstError == null) firstError = e
                    if (e.keyBad) break
                    if (e.kind == "kunlik" || e.kind == "kredit") break
                    if (moreKeys && (e.kind == "daqiqalik" || e.code == 429)) break
                    if (e.kind == "filtr") throw e
                    idx += 1
                }
            }
        }
        throw firstError ?: ProviderError("Imlo tuzatilmadi.", 0, true)
    }
}
