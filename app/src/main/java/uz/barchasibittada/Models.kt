package uz.barchasibittada

import org.json.JSONArray

object Prov {
    const val GEMINI = "gemini"
    const val ANTHROPIC = "anthropic"
    const val OPENAI = "openai"
    const val XAI = "xai"

    val all: List<String> = listOf(GEMINI, ANTHROPIC, OPENAI, XAI)

    fun name(p: String): String = when (p) {
        GEMINI -> "Gemini"
        ANTHROPIC -> "Claude"
        OPENAI -> "ChatGPT"
        XAI -> "Grok"
        else -> p
    }

    fun site(p: String): String = when (p) {
        GEMINI -> "https://aistudio.google.com/apikey"
        ANTHROPIC -> "https://console.anthropic.com/settings/keys"
        OPENAI -> "https://platform.openai.com/api-keys"
        XAI -> "https://console.x.ai/team/default/api-keys"
        else -> ""
    }

    fun siteShort(p: String): String = when (p) {
        GEMINI -> "aistudio.google.com/apikey"
        ANTHROPIC -> "console.anthropic.com"
        OPENAI -> "platform.openai.com/api-keys"
        XAI -> "console.x.ai"
        else -> ""
    }
}

data class ModelInfo(
    val id: String,
    val name: String,
    val provider: String,
    val model: String,
    val note: String
)

object Models {
    private val builtin: List<ModelInfo> = listOf(
        ModelInfo("gemini-3.6-flash", "Gemini 3.6 Flash", Prov.GEMINI, "gemini-3.6-flash", "Eng barqaror tez model. Ovoz matni va kundalik savollar uchun asosiy."),
        ModelInfo("gemini-3.5-flash", "Gemini 3.5 Flash", Prov.GEMINI, "gemini-3.5-flash", "Barqaror zaxira. 3.6 band bo'lsa shu ishlaydi."),
        ModelInfo("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite", Prov.GEMINI, "gemini-3.5-flash-lite", "Eng yengil va tez. Ko'p savol uchun qulay."),
        ModelInfo("gemini-3.1-flash-lite", "Gemini 3.1 Flash-Lite", Prov.GEMINI, "gemini-3.1-flash-lite", "Yengil model. Uzoq matnlar uchun zaxira."),
        ModelInfo("gemini-3.1-flash", "Gemini 3 Flash", Prov.GEMINI, "gemini-3-flash-preview", "Tez va arzon. Uzoq matnlar uchun qulay."),
        ModelInfo("gemini-flash-lite", "Gemini Flash-Lite (oxirgi)", Prov.GEMINI, "gemini-flash-lite-latest", "Google o'zi eng oxirgi Flash-Lite versiyasiga yo'naltiradi."),
        ModelInfo("gemini-3.8-flash", "Gemini 3.8 Flash (sinov)", Prov.GEMINI, "gemini-3.8-flash", "Eng yangi, lekin beqaror: ba'zan 503 yoki matnni to'liq bermaydi. Faqat zaxira."),
        ModelInfo("gemini-3.7-flash", "Gemini 3.7 Flash (sinov)", Prov.GEMINI, "gemini-3.7-flash", "3.8 bilan bir xil beqarorlik kuzatildi. Faqat zaxira."),

        ModelInfo("claude-sonnet-5-5", "Claude Sonnet 5.5 (muvozanat)", Prov.ANTHROPIC, "claude-sonnet-5-5", "Tez va aqlli. Kundalik ishlar uchun eng mos."),
        ModelInfo("claude-opus-5-5", "Claude Opus 5.5 (eng kuchli)", Prov.ANTHROPIC, "claude-opus-5-5", "Eng chuqur fikrlaydigan model. Murakkab savollar uchun."),
        ModelInfo("claude-fable-5-2", "Claude Fable 5.2", Prov.ANTHROPIC, "claude-fable-5-2", "Hikoya va uzun matn uchun. Eng yangi avlod."),
        ModelInfo("claude-haiku-5-5", "Claude Haiku 5.5 (eng tez)", Prov.ANTHROPIC, "claude-haiku-5-5", "Juda tez va arzon. Oddiy savollar uchun."),
        ModelInfo("claude-sonnet", "Claude Sonnet 5", Prov.ANTHROPIC, "claude-sonnet-5", "Oldingi avlod: tez va aqlli."),
        ModelInfo("claude-fable", "Claude Fable 5.1", Prov.ANTHROPIC, "claude-fable-5-1", "Oldingi avlod: hikoya va uzun matn uchun."),
        ModelInfo("claude-haiku", "Claude Haiku 4.5", Prov.ANTHROPIC, "claude-haiku-4-5-20251001", "Oldingi avlod: juda tez va arzon."),

        ModelInfo("gpt-6-astra", "GPT-6 Astra (eng kuchli)", Prov.OPENAI, "gpt-6-astra", "OpenAI ning eng yangi kuchli modeli."),
        ModelInfo("gpt-6-sol", "GPT-6 Sol", Prov.OPENAI, "gpt-6-sol", "Kuchli model. Murakkab savollar uchun."),
        ModelInfo("gpt-6-luna", "GPT-6 Luna", Prov.OPENAI, "gpt-6-luna", "Tez va aqlli. Kundalik ishlar uchun."),
        ModelInfo("gpt-5.6-sol", "GPT-5.6 Sol", Prov.OPENAI, "gpt-5.6-sol", "Oldingi avlod kuchli modeli."),
        ModelInfo("gpt-5.6-terra", "GPT-5.6 Terra", Prov.OPENAI, "gpt-5.6-terra", "Oldingi avlod muvozanat modeli."),
        ModelInfo("gpt-5.6-luna", "GPT-5.6 Luna", Prov.OPENAI, "gpt-5.6-luna", "Oldingi avlod tez modeli."),
        ModelInfo("gpt-5.1", "GPT-5.1", Prov.OPENAI, "gpt-5.1", "Rasm va ovozni ko'radi."),
        ModelInfo("gpt-5", "GPT-5", Prov.OPENAI, "gpt-5", "Kuchli model. Murakkab savollar uchun."),
        ModelInfo("gpt-5-mini", "GPT-5 mini (tez)", Prov.OPENAI, "gpt-5-mini", "Tez va arzon. Kundalik savollar uchun."),
        ModelInfo("gpt-4o", "GPT-4o", Prov.OPENAI, "gpt-4o", "Rasm, ovoz va matnni birga ko'radigan model.")
    )

    // Grok modellari: kalit qo'shilganda xAI ro'yxatidan yangilanadi. Bu — zaxira ro'yxat.
    private val xaiDefault: List<String> = listOf("grok-4.7", "grok-4.6", "grok-4.5", "grok-4.3")

    fun xaiModelIds(): List<String> {
        val saved = Settings.xaiModels
        val out = ArrayList<String>()
        if (saved.isNotBlank()) {
            try {
                val arr = JSONArray(saved)
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i)
                    if (s.isNotBlank() && !out.contains(s)) out.add(s)
                }
            } catch (e: Exception) {
                out.clear()
            }
        }
        for (d in xaiDefault) if (!out.contains(d)) out.add(d)
        // Yangi versiyalar oldinda turadi
        out.sortWith(Comparator<String> { a, b -> compareVersions(b, a) })
        return out
    }

    private fun compareVersions(a: String, b: String): Int {
        val na = versionNumbers(a)
        val nb = versionNumbers(b)
        val n = maxOf(na.size, nb.size)
        for (i in 0 until n) {
            val x = if (i < na.size) na[i] else 0
            val y = if (i < nb.size) nb[i] else 0
            if (x != y) return x.compareTo(y)
        }
        return a.compareTo(b)
    }

    private fun versionNumbers(s: String): List<Int> {
        val out = ArrayList<Int>()
        val m = Regex("\\d+").findAll(s)
        for (r in m) {
            val v = r.value.toIntOrNull()
            if (v != null) out.add(v)
        }
        // "grok-4.20-..." kabi eski nomlar "4.7" dan yangi hisoblanmasligi uchun
        if (out.size >= 2 && out[1] >= 10) out[1] = 0
        return out
    }

    private fun xaiModels(): List<ModelInfo> {
        val out = ArrayList<ModelInfo>()
        for (id in xaiModelIds()) {
            val nice = "Grok " + id.removePrefix("grok-")
            val note = if (id == "grok-4.7") "Grok ning eng yangi asosiy modeli. Matn va rasm, internet va X qidiruvi." else "xAI modeli."
            out.add(ModelInfo(id, nice, Prov.XAI, id, note))
        }
        return out
    }

    fun all(): List<ModelInfo> = builtin + xaiModels()

    fun find(id: String): ModelInfo? {
        if (id.isBlank()) return null
        for (m in all()) if (m.id == id) return m
        return null
    }

    fun ofProvider(provider: String): List<ModelInfo> = all().filter { it.provider == provider }

    /** Tanlangan model birinchi, so'ng shu provayderning qolgan modellari — zaxira zanjiri. */
    fun chain(provider: String, firstModel: String): List<String> {
        val out = ArrayList<String>()
        if (firstModel.isNotBlank()) out.add(firstModel)
        for (m in ofProvider(provider)) if (!out.contains(m.model)) out.add(m.model)
        return out
    }

    /** Rasm, hujjat, ovoz uchun model zanjiri (config.example.json dagi media_model ro'yxatlari). */
    fun mediaChain(provider: String): List<String> = when (provider) {
        Prov.GEMINI -> listOf("gemini-3.6-flash", "gemini-3.5-flash", "gemini-3.5-flash-lite", "gemini-3.8-flash")
        Prov.ANTHROPIC -> listOf("claude-sonnet-5-5", "claude-sonnet-5", "claude-opus-5-5")
        Prov.OPENAI -> listOf("gpt-5", "gpt-4.1", "gpt-4o")
        Prov.XAI -> xaiModelIds().take(3)
        else -> emptyList()
    }

    val geminiImageModels: List<String> = listOf("gemini-3.1-flash-image", "gemini-3-pro-image-preview", "gemini-2.5-flash-image")
    const val OPENAI_STT_MODEL = "gpt-4o-transcribe"
    val xaiImageModels: List<String> = listOf("grok-imagine-image", "grok-imagine-image-2.0", "grok-imagine-image-pro")

    /** "gemini-3.6-flash" → (3, 6). Topilmasa (0, 0). */
    fun level(model: String): Pair<Int, Int> {
        val m = Regex("(\\d+)\\.(\\d+)").find(model)
        if (m == null) return Pair(0, 0)
        val a = m.groupValues[1].toIntOrNull() ?: 0
        val b = m.groupValues[2].toIntOrNull() ?: 0
        return Pair(a, b)
    }

    private fun levelValue(model: String): Int {
        val p = level(model)
        return p.first * 1000 + p.second
    }

    /** Gemini da qidiruv vositasi 3.5 va undan yuqori modellarda ishlaydi. */
    fun searchReady(provider: String, model: String): Boolean {
        if (provider != Prov.GEMINI) return true
        return levelValue(model) >= 3005
    }

    /** Qidiruv yoqilgan, lekin model eski bo'lsa — shu provayderning qidiruvni ko'taradigan modeli. */
    fun searchModel(provider: String, model: String): String {
        if (searchReady(provider, model)) return model
        var best = ""
        for (m in ofProvider(provider)) {
            if (levelValue(m.model) >= 3005 && (best.isEmpty() || levelValue(m.model) < levelValue(best))) {
                // barqaror (eng past qidiruvga yaroqli) model afzal: 3.5 yoki 3.6
                best = m.model
            }
        }
        return if (best.isEmpty()) model else best
    }
}
