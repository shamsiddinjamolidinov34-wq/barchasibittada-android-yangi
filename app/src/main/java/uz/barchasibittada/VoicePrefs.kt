package uz.barchasibittada

import android.content.Context

/**
 * Audiolashtirish sozlamalari (Audiokitob'dagi Prefs). Kalitlar — Sozlamalar bo'limidagi Gemini kalitlari
 * (kamida 5 ta, eng ko'pi Keys.MAX ta): 1-kalit limitga yetsa 2-kalit, keyin 3-, 4-, 5-... kalit ishlatiladi.
 * Ovoz dvigateli (Gemini, Microsoft, ChatGPT, Grok, qurilma) — VEngine. Qolgan sozlamalar `Settings` ichida (ekran ularni kuzatib turadi).
 */
object VPrefs {
    const val DEFAULT_MODEL = "gemini-3.8-flash-tts"
    const val DEFAULT_BACKUP = "gemini-3.8-flash-lite-tts"
    const val DEFAULT_VOICE = "Charon"
    const val DEFAULT_CHUNK = 3000

    /** Skanerlangan PDF ni oʻqish (OCR) uchun Gemini Vision modellari va guruh hajmi. */
    const val DEFAULT_OCR_MODEL = "gemini-3.8-flash"
    const val DEFAULT_OCR_BACKUP = "gemini-3.5-flash-lite"
    const val DEFAULT_OCR_PAGES = 8

    /** Butun PDF ni bir so'rovda o'qishda sahifalar soni: avtomatik tanlanadigan oraliq (eng ko'pi). */
    const val OCR_WINDOW_MAX = 120

    /** Avtomatik rejimda bir so'rovdagi sahifalarning eng ko'pi: juda uzun javoblar aniqlikni pasaytiradi. */
    const val OCR_AUTO_MAX = 40

    /** Imlo tuzatish rejimi: 0 — o'chiq, 1 — faqat skanerlangan (OCR) kitoblarda, 2 — hamma matnda. */
    const val SPELL_OFF = 0
    const val SPELL_SCANNED = 1
    const val SPELL_ALL = 2

    class KeyInfo(val slot: Int, val key: String)

    /** Gemini TTS ning 30 ta tayyor ovozi. Qisqa ta'riflari shu tartibda VOICE_TRAITS da. */
    val VOICE_NAMES: List<String> = listOf(
        "Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Aoede", "Callirrhoe", "Autonoe",
        "Enceladus", "Iapetus", "Umbriel", "Algieba", "Despina", "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Achernar",
        "Alnilam", "Schedar", "Gacrux", "Pulcherrima", "Achird", "Zubenelgenubi", "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat"
    )

    val VOICE_TRAITS: List<String> = listOf(
        "yorqin", "quvnoq", "ma'lumotli", "qat'iy", "hayajonli", "yosh", "qat'iy", "yengil", "xotirjam", "yorqin",
        "nafasli", "ravshan", "xotirjam", "silliq", "silliq", "ravshan", "xirillagan", "ma'lumotli", "quvnoq", "yumshoq",
        "qat'iy", "bir tekis", "yetuk", "dadil", "do'stona", "oddiy", "muloyim", "jonli", "bilimdon", "iliq"
    )

    /** Ro'yxatdagi ko'rinish: «Charon — qat'iy». */
    fun voiceLabel(name: String): String {
        val i = VOICE_NAMES.indexOf(name)
        return if (i >= 0 && i < VOICE_TRAITS.size) name + " — " + VOICE_TRAITS[i] else name
    }

    /** Gemini kalitlari: o'rin raqami (1..Keys.MAX) bilan, bo'sh o'rinlar tashlab ketiladi. */
    @Suppress("UNUSED_PARAMETER")
    fun keys(ctx: Context): List<KeyInfo> {
        val l = ArrayList<KeyInfo>()
        for (i in 1..Keys.MAX) {
            val k = Keys.getSlot(Prov.GEMINI, i)
            if (k.isNotEmpty()) l.add(KeyInfo(i, k))
        }
        Journal.registerSecrets(l.map { it.key })
        return l
    }

    @Suppress("UNUSED_PARAMETER")
    fun voice(ctx: Context): String {
        val v = Settings.vVoice.trim()
        return if (v.isEmpty()) DEFAULT_VOICE else v
    }

    @Suppress("UNUSED_PARAMETER")
    fun model(ctx: Context): String {
        val v = Settings.vModel.trim()
        return if (v.isEmpty()) DEFAULT_MODEL else v
    }

    @Suppress("UNUSED_PARAMETER")
    fun setModel(ctx: Context, v: String) {
        Settings.vModel = v.trim()
    }

    /** Zaxira model (bo'sh = o'chiq). */
    @Suppress("UNUSED_PARAMETER")
    fun backup(ctx: Context): String = Settings.vBackup.trim()

    @Suppress("UNUSED_PARAMETER")
    fun setBackup(ctx: Context, v: String) {
        Settings.vBackup = v.trim()
    }

    @Suppress("UNUSED_PARAMETER")
    fun style(ctx: Context): String = Settings.vStyle.trim()

    @Suppress("UNUSED_PARAMETER")
    fun chunk(ctx: Context): Int = Settings.vChunk.coerceIn(800, 6000)

    // ---------------------------------------------------------------- OCR (skanerlangan PDF)

    @Suppress("UNUSED_PARAMETER")
    fun ocrEnabled(ctx: Context): Boolean = Settings.vOcr

    @Suppress("UNUSED_PARAMETER")
    fun ocrModel(ctx: Context): String {
        val v = Settings.vOcrModel.trim()
        return if (v.isEmpty()) DEFAULT_OCR_MODEL else v
    }

    @Suppress("UNUSED_PARAMETER")
    fun setOcrModel(ctx: Context, v: String) {
        Settings.vOcrModel = v.trim()
    }

    @Suppress("UNUSED_PARAMETER")
    fun ocrBackup(ctx: Context): String = Settings.vOcrBackup.trim()

    @Suppress("UNUSED_PARAMETER")
    fun setOcrBackup(ctx: Context, v: String) {
        Settings.vOcrBackup = v.trim()
    }

    /** Sahifalarni rasm qilib yuborishda bir OCR soʻrovidagi sahifalar soni (1–20). */
    @Suppress("UNUSED_PARAMETER")
    fun ocrPages(ctx: Context): Int = Settings.vOcrPages.coerceIn(1, 20)

    /** Skanerlangan PDF butun fayl sifatida (Gemini Files API) o'qiladimi. */
    @Suppress("UNUSED_PARAMETER")
    fun ocrWhole(ctx: Context): Boolean = Settings.vOcrWhole

    /** Butun PDF rejimida bir so'rovdagi sahifalarning eng ko'pi: 0 — avtomatik (modelning chiqish hajmi ruxsat bergancha). */
    @Suppress("UNUSED_PARAMETER")
    fun ocrWindow(ctx: Context): Int {
        val w = Settings.vOcrWindow
        return if (w <= 0) 0 else w.coerceIn(1, OCR_WINDOW_MAX)
    }

    /** O'qish tezligi, foiz (100 — oddiy). */
    @Suppress("UNUSED_PARAMETER")
    fun rate(ctx: Context): Int = VEngine.rate()

    /** OCR uchun ishlatiladigan modellar tartibi: asosiy, keyin zaxira. */
    fun ocrModels(ctx: Context): List<String> {
        val l = ArrayList<String>()
        for (m in listOf(ocrModel(ctx), ocrBackup(ctx))) {
            if (m.isNotEmpty() && !l.contains(m)) l.add(m)
        }
        return l
    }

    /** Imlo tuzatish rejimi (SPELL_*). */
    @Suppress("UNUSED_PARAMETER")
    fun spellMode(ctx: Context): Int = Settings.vSpell.coerceIn(SPELL_OFF, SPELL_ALL)

    /** Ishlatiladigan modellar tartibi: asosiy, keyin zaxira. */
    fun models(ctx: Context): List<String> {
        val l = ArrayList<String>()
        for (m in listOf(model(ctx), backup(ctx))) {
            if (m.isNotEmpty() && !l.contains(m)) l.add(m)
        }
        return l
    }
}
