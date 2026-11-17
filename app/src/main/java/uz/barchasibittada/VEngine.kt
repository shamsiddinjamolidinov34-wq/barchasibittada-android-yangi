package uz.barchasibittada

/**
 * Audiolashtirish ovoz dvigatellari: Gemini, Microsoft, ChatGPT (OpenAI), Grok (xAI) va qurilma ovozi.
 * Hammasi bitta audiolashtirish yadrosi (VoiceCore) orqali ishlaydi: fon xizmati, to'xtagan joydan davom etish,
 * progress, MP3 fayl, imlo tuzatish, skanerlangan kitoblarni o'qish — dvigatelga bog'liq emas.
 */
object VEngine {
    const val GEMINI = "gemini"
    const val MICROSOFT = "microsoft"
    const val OPENAI = "openai"
    const val XAI = "xai"
    const val DEVICE = "device"

    val ALL: List<String> = listOf(GEMINI, MICROSOFT, OPENAI, XAI, DEVICE)

    class VoiceOpt(val id: String, val label: String)

    /** Microsoft (Edge) ovozlari. Birinchi ikkitasi — o'zbekcha; qolganlari boshqa tildagi matnlar uchun. */
    val MS_VOICES: List<VoiceOpt> = listOf(
        VoiceOpt("uz-UZ-SardorNeural", "Sardor (erkak, o'zbekcha)"),
        VoiceOpt("uz-UZ-MadinaNeural", "Madina (ayol, o'zbekcha)"),
        VoiceOpt("ru-RU-DmitryNeural", "Dmitry (erkak, ruscha)"),
        VoiceOpt("ru-RU-SvetlanaNeural", "Svetlana (ayol, ruscha)"),
        VoiceOpt("en-US-GuyNeural", "Guy (erkak, inglizcha)"),
        VoiceOpt("en-US-AriaNeural", "Aria (ayol, inglizcha)"),
        VoiceOpt("tr-TR-AhmetNeural", "Ahmet (erkak, turkcha)"),
        VoiceOpt("tr-TR-EmelNeural", "Emel (ayol, turkcha)"),
        VoiceOpt("kk-KZ-DauletNeural", "Daulet (erkak, qozoqcha)"),
        VoiceOpt("kk-KZ-AigulNeural", "Aigul (ayol, qozoqcha)"),
        VoiceOpt("az-AZ-BabekNeural", "Babek (erkak, ozarbayjoncha)"),
        VoiceOpt("az-AZ-BanuNeural", "Banu (ayol, ozarbayjoncha)"),
        VoiceOpt("ar-SA-HamedNeural", "Hamed (erkak, arabcha)"),
        VoiceOpt("ar-SA-ZariyahNeural", "Zariyah (ayol, arabcha)"),
        VoiceOpt("fa-IR-FaridNeural", "Farid (erkak, forscha)"),
        VoiceOpt("fa-IR-DilaraNeural", "Dilara (ayol, forscha)")
    )

    val OPENAI_VOICES: List<String> = listOf("alloy", "ash", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer")

    /** «Avtomatik» — avval gpt-4o-mini-tts, u ishlamasa tts-1. */
    val OPENAI_MODELS: List<String> = listOf("gpt-4o-mini-tts", "tts-1")

    val XAI_VOICES: List<String> = listOf("eve", "ara", "rex", "sal", "leo", "luna", "lumen", "cosmo", "orion", "iris", "ursa", "aurora")

    /** O'qish tezligi (foiz): 100 — oddiy. */
    val RATES: List<Int> = listOf(70, 80, 90, 100, 110, 120, 130, 150)

    const val DEFAULT_MS_VOICE = "uz-UZ-SardorNeural"
    const val DEFAULT_OPENAI_VOICE = "alloy"
    const val DEFAULT_XAI_VOICE = "eve"

    fun name(e: String): String = when (e) {
        GEMINI -> "Gemini"
        MICROSOFT -> "Microsoft"
        OPENAI -> "ChatGPT"
        XAI -> "Grok"
        else -> "Qurilma ovozi"
    }

    /** Dvigatel kalit talab qiladigan provayder; kalitsiz dvigatellar uchun bo'sh matn. */
    fun provOf(e: String): String = when (e) {
        GEMINI -> Prov.GEMINI
        OPENAI -> Prov.OPENAI
        XAI -> Prov.XAI
        else -> ""
    }

    fun keyless(e: String): Boolean = e == MICROSOFT || e == DEVICE

    /** Sozlamalarda tanlangan dvigatel (noma'lum qiymat bo'lsa — Gemini). */
    fun configured(): String {
        val v = Settings.vEngine.trim()
        return if (ALL.contains(v)) v else GEMINI
    }

    fun keyCount(e: String): Int {
        val p = provOf(e)
        return if (p.isEmpty()) 0 else Keys.count(p)
    }

    fun ready(e: String): Boolean = keyless(e) || keyCount(e) > 0

    /** Haqiqatda ishlatiladigan dvigatel: tanlangan dvigatelning kaliti bo'lmasa — kalitsiz Microsoft ovozi. */
    fun effective(): String {
        val c = configured()
        return if (ready(c)) c else MICROSOFT
    }

    /** Sozlamalardagi hozirgi ovoz (dvigatelga qarab). */
    fun currentVoice(e: String): String = when (e) {
        GEMINI -> VPrefs.voice(Store.app)
        MICROSOFT -> {
            val v = Settings.vMsVoice.trim()
            if (MS_VOICES.any { it.id == v }) v else DEFAULT_MS_VOICE
        }
        OPENAI -> {
            val v = Settings.vOaiVoice.trim()
            if (OPENAI_VOICES.contains(v)) v else DEFAULT_OPENAI_VOICE
        }
        XAI -> {
            val v = Settings.vXaiVoice.trim()
            if (XAI_VOICES.contains(v)) v else DEFAULT_XAI_VOICE
        }
        else -> ""
    }

    /** Ish yaratilganda saqlangan ovoz bo'sh bo'lsa (eski ishlar) — hozirgi sozlama. */
    fun voiceFor(e: String, pinned: String): String = if (pinned.isNotBlank()) pinned else currentVoice(e)

    fun voiceLabel(e: String, id: String): String = when (e) {
        GEMINI -> VPrefs.voiceLabel(id)
        MICROSOFT -> MS_VOICES.firstOrNull { it.id == id }?.label ?: id
        else -> id
    }

    /** Bir so'rovga yuboriladigan belgilar soni: foydalanuvchi tanlovi, lekin dvigatelning o'z chegarasidan oshmaydi. */
    fun chunkFor(e: String, user: Int): Int = when (e) {
        MICROSOFT -> minOf(user, 1800)
        OPENAI -> minOf(user, 3500)
        XAI -> minOf(user, 3500)
        DEVICE -> minOf(user, 3000)
        else -> user
    }

    /** ChatGPT ovoz modellari tartibi: tanlangan model; «avtomatik» bo'lsa avval gpt-4o-mini-tts, keyin tts-1. */
    fun openaiModelList(): List<String> {
        val m = Settings.vOaiModel.trim()
        return if (m.isNotEmpty()) listOf(m) else OPENAI_MODELS
    }

    fun rate(): Int {
        val r = Settings.vRate
        return if (r in 50..200) r else 100
    }

    /** Foydalanuvchiga ko'rsatiladigan qisqa tavsif: kalit kerakmi va nima uchun mos. */
    fun about(e: String): String = when (e) {
        GEMINI -> "Eng tabiiy ovoz, 30 ta ovoz va o'qish uslubi. Gemini kaliti kerak (kunlik limit bor)."
        MICROSOFT -> "Kalitsiz va limitsiz. O'zbekcha Sardor va Madina ovozlari. Microsoft xizmati o'zgarsa, ishlamay qolishi mumkin."
        OPENAI -> "ChatGPT (OpenAI) kaliti kerak. To'lov OpenAI tarifiga ko'ra olinadi."
        XAI -> "Grok (xAI) kaliti kerak. To'lov xAI tarifiga ko'ra olinadi."
        else -> "Kalitsiz va internetsiz: telefondagi matnni o'qish dvigateli. O'zbek tili o'rnatilmagan bo'lsa, sifat past bo'lishi mumkin."
    }

    fun needKeyText(e: String): String {
        val p = provOf(e)
        if (p.isEmpty()) return ""
        return name(e) + " ovozi uchun " + Prov.name(p) + " API kaliti kerak. Sozlamalar bo'limida kalit qo'shing " +
            "(har bir xizmat uchun kamida " + Keys.MIN_SLOTS + " ta, eng ko'pi " + Keys.MAX + " ta kalit qo'shish mumkin) " +
            "yoki Ovoz sozlamalarida kalitsiz Microsoft ovozini tanlang."
    }

    /** Ketayotgan hamma ovoz so'rovlarini uzadi (foydalanuvchi «Pauza» bosganda yoki xizmat to'xtaganda). */
    fun cancelAll() {
        Gemini.cancelActive()
        EdgeTts.cancel()
        CloudTts.cancel()
        DeviceTts.cancel()
    }
}
