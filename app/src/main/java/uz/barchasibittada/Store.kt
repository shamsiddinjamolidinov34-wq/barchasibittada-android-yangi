package uz.barchasibittada

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.reflect.KProperty

object Store {
    lateinit var app: Context
        private set
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        app = ctx.applicationContext
        sp = app.getSharedPreferences("barchasibittada", Context.MODE_PRIVATE)
    }

    fun getString(k: String, d: String): String = sp.getString(k, d) ?: d
    fun putString(k: String, v: String) {
        sp.edit().putString(k, v).apply()
    }

    fun getBool(k: String, d: Boolean): Boolean = sp.getBoolean(k, d)
    fun putBool(k: String, v: Boolean) {
        sp.edit().putBoolean(k, v).apply()
    }

    fun getInt(k: String, d: Int): Int = sp.getInt(k, d)
    fun putInt(k: String, v: Int) {
        sp.edit().putInt(k, v).apply()
    }

    fun remove(k: String) {
        sp.edit().remove(k).apply()
    }

    fun outDir(): File {
        val d = File(app.filesDir, "out")
        d.mkdirs()
        return d
    }

    fun audioDir(): File {
        val d = File(app.filesDir, "audio")
        d.mkdirs()
        return d
    }

    fun inDir(): File {
        val d = File(app.cacheDir, "in")
        d.mkdirs()
        return d
    }

    fun clearGenerated() {
        for (dir in listOf(outDir(), audioDir(), inDir())) {
            val list = dir.listFiles() ?: continue
            for (f in list) {
                try {
                    f.delete()
                } catch (e: Exception) {
                    // e'tiborsiz
                }
            }
        }
    }
}

/** API kalitlarini Android Keystore (AES-GCM) bilan shifrlab saqlaydi. */
object Secure {
    private const val ALIAS = "barchasibittada_kalit"

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val existing = ks.getKey(ALIAS, null)
        if (existing is SecretKey) return existing
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        gen.init(spec)
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val enc = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            return "e:" + Base64.encodeToString(iv + enc, Base64.NO_WRAP)
        } catch (e: Exception) {
            // Keystore ishlamasa — kalit shu qurilmaning yopiq xotirasida oddiy ko'rinishda qoladi.
            return "p:" + Base64.encodeToString(plain.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }
    }

    fun decrypt(stored: String): String {
        try {
            if (stored.startsWith("p:")) {
                return String(Base64.decode(stored.substring(2), Base64.NO_WRAP), Charsets.UTF_8)
            }
            if (!stored.startsWith("e:")) return ""
            val raw = Base64.decode(stored.substring(2), Base64.NO_WRAP)
            if (raw.size < 13) return ""
            val iv = raw.copyOfRange(0, 12)
            val data = raw.copyOfRange(12, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            return String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (e: Exception) {
            return ""
        }
    }
}

/**
 * Provayder kalitlari: har bir xizmat (Gemini, Claude, ChatGPT, Grok) uchun kamida 5 ta, eng ko'pi 10 ta kalit.
 * 1-kalit eski joyda (key_<provayder>) saqlanadi, 2–10-kalitlar key_<provayder>_<raqam> nomi bilan: oldin saqlangan
 * kalitlar yangilashdan keyin ham o'z joyida qoladi. O'zgarganda ekran yangilanishi uchun `version` kuzatiladi.
 */
object Keys {
    /** Har bir xizmat uchun kamida shuncha kalit qo'shish kafolatlanadi. */
    const val MIN_SLOTS = 5

    /** Har bir xizmat uchun qo'shiladigan kalitlarning eng ko'pi (MIN_SLOTS dan kam bo'lmasligi kerak). */
    const val MAX = 10
    private val version = mutableIntStateOf(0)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun pref(p: String, slot: Int): String = if (slot <= 1) "key_" + p else "key_" + p + "_" + slot

    /** Berilgan o'rindagi (1..MAX) kalit; bo'sh bo'lishi mumkin. */
    fun getSlot(p: String, slot: Int): String {
        val v = version.intValue
        if (v < 0) return ""
        val name = pref(p, slot)
        val c = cache[name]
        if (c != null) return c
        val stored = Store.getString(name, "")
        val plain = if (stored.isEmpty()) "" else Secure.decrypt(stored)
        if (plain.isNotEmpty()) cache[name] = plain
        return plain
    }

    /** Provayderning birinchi mavjud kaliti. */
    fun get(p: String): String {
        for (i in 1..MAX) {
            val k = getSlot(p, i)
            if (k.isNotEmpty()) return k
        }
        return ""
    }

    /** Provayderning barcha kalitlari (bo'shlari tashlab ketiladi), o'rin tartibida. */
    fun all(p: String): List<String> {
        val out = ArrayList<String>()
        for (i in 1..MAX) {
            val k = getSlot(p, i)
            if (k.isNotEmpty()) out.add(k)
        }
        return out
    }

    fun count(p: String): Int = all(p).size

    fun has(p: String): Boolean = count(p) > 0

    /** Kalitni berilgan o'ringa yozadi (bo'sh bo'lsa — o'sha o'rin tozalanadi). */
    fun setSlot(p: String, slot: Int, key: String) {
        val clean = cleanKey(key)
        if (clean.isEmpty()) {
            removeSlot(p, slot)
            return
        }
        val name = pref(p, slot)
        Store.putString(name, Secure.encrypt(clean))
        cache[name] = clean
        version.intValue = version.intValue + 1
    }

    fun removeSlot(p: String, slot: Int) {
        val name = pref(p, slot)
        Store.remove(name)
        cache.remove(name)
        version.intValue = version.intValue + 1
    }

    /** Birinchi bo'sh o'rin (1..MAX) yoki hammasi band bo'lsa 0. */
    fun freeSlot(p: String): Int {
        for (i in 1..MAX) if (getSlot(p, i).isEmpty()) return i
        return 0
    }

    /** Buferdan joylashda ortib qolgan bo'shliq, qo'shtirnoq va «key=» ni olib tashlaydi. */
    fun cleanKey(k: String): String {
        val sb = StringBuilder()
        for (ch in k) {
            if (ch.code in 33..126 && ch != '"' && ch != '\'' && ch != '`') sb.append(ch)
        }
        var s = sb.toString()
        if (s.startsWith("key=", ignoreCase = true)) s = s.substring(4)
        return s
    }

    /** Ko'rsatish uchun yashirilgan ko'rinish: faqat oxirgi 4 belgi. */
    fun hint(p: String, slot: Int): String {
        val k = getSlot(p, slot)
        if (k.isEmpty()) return ""
        return if (k.length <= 8) "••••" else "••••" + k.takeLast(4)
    }

    fun withKeys(): List<String> = Prov.all.filter { has(it) }

    fun any(): Boolean = withKeys().isNotEmpty()
}

class StrPref(private val name: String, private val def: String) {
    private val st = mutableStateOf(Store.getString(name, def))
    operator fun getValue(thisRef: Any?, property: KProperty<*>): String = st.value
    operator fun setValue(thisRef: Any?, property: KProperty<*>, v: String) {
        st.value = v
        Store.putString(name, v)
    }
}

class BoolPref(private val name: String, private val def: Boolean) {
    private val st = mutableStateOf(Store.getBool(name, def))
    operator fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = st.value
    operator fun setValue(thisRef: Any?, property: KProperty<*>, v: Boolean) {
        st.value = v
        Store.putBool(name, v)
    }
}

class IntPref(private val name: String, private val def: Int) {
    private val st = mutableIntStateOf(Store.getInt(name, def))
    operator fun getValue(thisRef: Any?, property: KProperty<*>): Int = st.intValue
    operator fun setValue(thisRef: Any?, property: KProperty<*>, v: Int) {
        st.intValue = v
        Store.putInt(name, v)
    }
}

object Settings {
    /** "" — avtomatik (kaliti bor birinchi model). */
    var modelId by StrPref("model", "")
    var searchOn by BoolPref("search", false)
    var detailOn by BoolPref("detail", true)
    var historyTurns by IntPref("history_turns", 6)

    /** Audiolashtirish (Gemini ovozi): ovoz, asosiy va zaxira model, o'qish uslubi, bo'lak hajmi. */
    var vVoice by StrPref("v_voice", "Charon")
    var vModel by StrPref("v_model", "gemini-3.8-flash-tts")
    var vBackup by StrPref("v_backup", "gemini-3.8-flash-lite-tts")
    var vStyle by StrPref("v_style", "")
    var vChunk by IntPref("v_chunk", 3000)

    /**
     * Ovoz dvigateli: gemini, microsoft, openai (ChatGPT), xai (Grok) yoki device (qurilma). Tanlangan dvigatelning kaliti
     * bo'lmasa, kalitsiz Microsoft ovozi ishlatiladi. Har bir ish yaratilganda dvigatel va ovoz ishga yoziladi.
     */
    var vEngine by StrPref("v_engine", "gemini")
    var vMsVoice by StrPref("v_ms_voice", "uz-UZ-SardorNeural")
    var vOaiVoice by StrPref("v_oai_voice", "alloy")

    /** "" — avtomatik (avval gpt-4o-mini-tts, keyin tts-1). */
    var vOaiModel by StrPref("v_oai_model", "")
    var vXaiVoice by StrPref("v_xai_voice", "eve")

    /** O'qish tezligi, foiz (100 — oddiy): Microsoft, ChatGPT (tts-1) va qurilma ovozi uchun. */
    var vRate by IntPref("v_rate", 100)

    /** Skanerlangan PDF ni AI (Gemini Vision) bilan o'qish: yoqilgan, modellar, bir so'rovdagi sahifalar soni. */
    var vOcr by BoolPref("v_ocr", true)
    var vOcrModel by StrPref("v_ocr_model", "gemini-3.8-flash")
    var vOcrBackup by StrPref("v_ocr_backup", "gemini-3.5-flash-lite")
    var vOcrPages by IntPref("v_ocr_pages", 8)

    /**
     * Skanerlangan PDF ni sahifa-sahifa emas, butun fayl sifatida o'qish: PDF Gemini Files API ga bir marta yuklanadi va
     * har bir so'rovda modelning chiqish hajmi ruxsat bergancha ko'p sahifa o'qiladi (0 — avtomatik; boshqa son — eng ko'pi shuncha sahifa).
     */
    var vOcrWhole by BoolPref("v_ocr_whole", true)
    var vOcrWindow by IntPref("v_ocr_window", 0)

    /** Imlo tuzatish: 0 o'chiq, 1 faqat skanerlangan kitoblarda, 2 hamma matnda. */
    var vSpell by IntPref("v_spell", 1)

    var ttsAuto by BoolPref("tts_auto", false)
    var audioMode by BoolPref("audio_mode", false)

    /** Botdagi 18+ va diniy so'zlar filtri. Ilovada sukut bo'yicha o'chiq. */
    var taqiqOn by BoolPref("taqiq_on", false)
    var xaiModels by StrPref("xai_models", "")
    var geminiImageOk by StrPref("gemini_image_ok", "")
    var welcomeSeen by BoolPref("welcome_seen", false)
}

/** Bugungi foydalanish sanoqlari (faqat ma'lumot uchun, hech qanday chegara qo'yilmaydi). */
object Usage {
    private fun day(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun bump(kind: String) {
        val k = "usage_" + day() + "_" + kind
        Store.putInt(k, Store.getInt(k, 0) + 1)
    }

    fun today(kind: String): Int = Store.getInt("usage_" + day() + "_" + kind, 0)
}

data class ChatMsg(val role: String, val content: String)

/** Suhbat tarixi (modelga kontekst sifatida beriladi). */
object History {
    private val items = ArrayList<ChatMsg>()
    private var loaded = false

    private fun file(): File = File(Store.app.filesDir, "history.json")

    @Synchronized
    private fun ensure() {
        if (loaded) return
        loaded = true
        try {
            val f = file()
            if (f.exists()) {
                val arr = JSONArray(f.readText(Charsets.UTF_8))
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val r = o.optString("r")
                    val c = o.optString("c")
                    if (c.isNotEmpty()) items.add(ChatMsg(r, c))
                }
            }
        } catch (e: Exception) {
            items.clear()
        }
    }

    @Synchronized
    fun add(role: String, content: String) {
        ensure()
        items.add(ChatMsg(role, content))
        while (items.size > 120) items.removeAt(0)
        save()
    }

    @Synchronized
    fun last(turns: Int): List<ChatMsg> {
        ensure()
        if (turns <= 0) return emptyList()
        var n = turns * 2
        if (n > items.size) n = items.size
        var out: List<ChatMsg> = ArrayList(items.subList(items.size - n, items.size))
        // Birinchi xabar har doim foydalanuvchiniki bo'lsin
        while (out.isNotEmpty() && out[0].role != "user") out = out.drop(1)
        return out
    }

    @Synchronized
    fun lastAssistant(): String {
        ensure()
        for (i in items.indices.reversed()) {
            if (items[i].role == "assistant") return items[i].content
        }
        return ""
    }

    @Synchronized
    fun count(): Int {
        ensure()
        return items.size
    }

    @Synchronized
    fun clear() {
        ensure()
        items.clear()
        save()
    }

    private fun save() {
        try {
            val arr = JSONArray()
            for (m in items) arr.put(JSONObject().put("r", m.role).put("c", m.content))
            file().writeText(arr.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            // saqlab bo'lmasa — xotirada qoladi
        }
    }
}
