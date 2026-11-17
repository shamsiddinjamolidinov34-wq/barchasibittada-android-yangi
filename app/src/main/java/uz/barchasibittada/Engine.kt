package uz.barchasibittada

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class OutFile(val name: String, val path: String, val mime: String)

class Msg(
    val id: Long,
    val role: String,
    val text: String,
    val files: List<OutFile> = emptyList(),
    val image: String? = null
)

class QuizState(val questions: List<QuizQ>) {
    var index by mutableIntStateOf(0)
    var correct by mutableIntStateOf(0)
    var picked by mutableStateOf("")
    var finished by mutableStateOf(false)
}

/** Ekranlar kuzatadigan umumiy holat. */
object Ui {
    val messages = mutableStateListOf<Msg>()
    var chatStatus by mutableStateOf("")
    var jobStatus by mutableStateOf("")
    var toastText by mutableStateOf("")
    var tab by mutableIntStateOf(0)
    var sub by mutableStateOf("")
    var pending by mutableStateOf<List<Picked>>(emptyList())
    var input by mutableStateOf("")
    var recording by mutableStateOf(false)
    val toolResults = mutableStateMapOf<String, Msg>()
    val keyStatus = mutableStateMapOf<String, String>()
    var quiz by mutableStateOf<QuizState?>(null)
    var bookPages by mutableIntStateOf(0)
    private var counter = 0L

    fun nextId(): Long {
        counter += 1L
        return counter
    }

    fun toast(s: String) {
        toastText = s
    }
}

object Engine {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var chatJob: Job? = null
    var bgJob: Job? = null

    fun init() {
        loadChat()
        Book.load()
        // Ilova ochilganda: eskirgan "ishlayapti" holatlarini tuzatish va limiti qayta ochilgan audiolashtirish ishlarini davom ettirish.
        scope.launch {
            kotlinx.coroutines.delay(2500L)
            try {
                JobStore.fixStale()
                VoiceJobs.resumeDue(Store.app)
            } catch (e: Throwable) {
                logError("Ishga tushirish", e)
            }
        }
    }

    // ------------------------------------------------------------------
    // Xabarlar
    // ------------------------------------------------------------------
    fun outFile(f: File): OutFile {
        val ext = Kinds.ext(f.name)
        val mime = when (ext) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "wav" -> "audio/wav"
            "txt" -> "text/plain"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
        return OutFile(f.name, f.absolutePath, mime)
    }

    fun say(role: String, text: String, files: List<OutFile> = emptyList(), image: String? = null) {
        Ui.messages.add(Msg(Ui.nextId(), role, text, files, image))
        saveChat()
    }

    fun emit(dest: String, text: String, files: List<OutFile> = emptyList(), image: String? = null) {
        if (dest == "chat") {
            say("assistant", text, files, image)
        } else {
            Ui.toolResults[dest] = Msg(Ui.nextId(), "assistant", text, files, image)
        }
    }

    fun toolResultsClear(dest: String) {
        Ui.toolResults.remove(dest)
    }

    fun clearChat() {
        Ui.messages.clear()
        History.clear()
        saveChat()
    }

    private fun chatFile(): File = File(Store.app.filesDir, "chat.json")

    private fun loadChat() {
        try {
            val f = chatFile()
            if (!f.exists()) return
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val t = o.optString("t")
                if (t.isNotEmpty()) Ui.messages.add(Msg(Ui.nextId(), o.optString("r", "assistant"), t))
            }
        } catch (e: Exception) {
            Ui.messages.clear()
        }
    }

    private fun saveChat() {
        try {
            val arr = JSONArray()
            val list = Ui.messages.toList()
            val from = if (list.size > 60) list.size - 60 else 0
            for (i in from until list.size) {
                val m = list[i]
                if (m.role == "system") continue
                arr.put(JSONObject().put("r", m.role).put("t", m.text))
            }
            chatFile().writeText(arr.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            // saqlanmasa — xotirada qoladi
        }
    }

    fun friendly(e: Throwable): String {
        if (e is ProviderError) return e.msg
        if (e is DocError) return e.message ?: "Faylni o'qib bo'lmadi."
        if (e is ScannedPdfException) {
            return "Bu PDF skanerlangan ko'rinadi (matn qatlami yo'q). Kitob asbobida «PDF dan o'qish» ni tanlang: sahifalar rasm sifatida o'qiladi."
        }
        if (e is OutOfMemoryError) return "Xotira yetmadi. Faylni kichikroq qilib ko'ring."
        return "Kutilmagan xatolik: " + Safe.mask(e.message ?: e.javaClass.simpleName).take(160)
    }

    // ------------------------------------------------------------------
    // Ishlarni boshqarish
    // ------------------------------------------------------------------
    fun chatBusy(): Boolean = chatJob?.isActive == true

    fun launchChat(status: String, block: suspend () -> Unit) {
        if (chatBusy()) {
            Ui.toast("Oldingi so'rov hali bajarilmoqda. Kuting yoki bekor qiling.")
            return
        }
        Ui.chatStatus = status
        chatJob = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                say("system", "Bekor qilindi.")
            } catch (e: Throwable) {
                logError("Suhbat", e)
                say("assistant", friendly(e))
            } finally {
                Ui.chatStatus = ""
            }
        }
    }

    fun cancelChat() {
        chatJob?.cancel()
    }

    /** Xato jurnaliga yozadi (bekor qilish xato emas). Provayder xatolari — HTTP kodi va turi bilan. */
    fun logError(where: String, e: Throwable) {
        if (e is CancellationException) return
        if (e is ProviderError) {
            Journal.w(where, "Provayder xatosi (HTTP " + e.code + ", " + e.kind + (if (e.keyBad) ", kalit yaroqsiz" else "") + "): " + e.msg)
        } else {
            Journal.e(where, "Xato: " + e.javaClass.simpleName + ": " + (e.message ?: ""), e)
        }
    }

    fun jobBusy(): Boolean = bgJob?.isActive == true

    /** Uzoq davom etadigan ish (maqola, kitob, audiokitob). dest — natija ko'rsatiladigan joy. */
    fun startJob(dest: String, title: String, block: suspend () -> Unit): Boolean {
        if (jobBusy()) {
            Ui.toast("Hozir boshqa ish bajarilmoqda. Tugashini kuting yoki bekor qiling.")
            return false
        }
        Ui.jobStatus = title
        KeepAliveService.start(title)
        bgJob = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                Ui.toast("Ish bekor qilindi.")
            } catch (e: Throwable) {
                logError("Ish", e)
                emit(dest, friendly(e))
            } finally {
                Ui.jobStatus = ""
                KeepAliveService.stop()
            }
        }
        return true
    }

    fun progress(text: String) {
        Ui.jobStatus = text
        KeepAliveService.update(text)
    }

    fun cancelJob() {
        bgJob?.cancel()
    }

    // ------------------------------------------------------------------
    // Matnli suhbat
    // ------------------------------------------------------------------
    fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        if (chatBusy()) {
            Ui.toast("Oldingi so'rov hali bajarilmoqda. Kuting yoki bekor qiling.")
            return
        }
        Ui.input = ""
        say("user", text)
        launchChat("Javob tayyorlanmoqda...") { handleText(text) }
    }

    private suspend fun handleText(text: String) {
        if (Settings.taqiqOn) {
            val t = Uz.taqiqTuri(text)
            if (t == "18") {
                say("assistant", T.TAQIQ_18)
                return
            }
            if (t == "din") {
                say("assistant", T.TAQIQ_DIN)
                return
            }
        }
        if (Settings.audioMode) {
            readAloud(text, "matn")
            return
        }
        val intent = Uz.niyatAniqla(text)
        if (intent.first.isNotEmpty() && intent.second.isNotBlank()) {
            runIntent(intent.first, intent.second, text)
            return
        }
        answerQuestion(text)
    }

    fun systemFor(extra: String): String {
        val sb = StringBuilder(T.SYSTEM_PROMPT)
        if (Settings.detailOn) sb.append("\n\n").append(T.DETAIL)
        if (Settings.searchOn) sb.append("\n\n").append(T.SEARCH_HINT)
        if (extra.isNotBlank()) sb.append("\n\n").append(extra)
        return sb.toString()
    }

    private fun answerTokens(): Int = if (Settings.detailOn) 4000 else 2500

    fun formatAnswer(a: Answer): String {
        var t = Uz.plain(a.text)
        if (a.sources.isNotEmpty()) {
            val sb = StringBuilder(t)
            sb.append("\n\nManbalar:")
            var n = 1
            for (s in a.sources) {
                sb.append("\n").append(n).append(". ").append(s)
                n += 1
            }
            t = sb.toString()
        }
        return t
    }

    private suspend fun answerQuestion(text: String) {
        Usage.bump("savol")
        val msgs = ArrayList<ChatMsg>(History.last(Settings.historyTurns))
        msgs.add(ChatMsg("user", text))
        val out = Ai.chat(systemFor(""), msgs, answerTokens(), Settings.searchOn)
        History.add("user", text)
        History.add("assistant", out.answer.text)
        say("assistant", formatAnswer(out.answer))
        if (Settings.ttsAuto) autoSpeak(out.answer.text)
    }

    private fun autoSpeak(text: String) {
        scope.launch {
            try {
                if (!VoiceJobs.hasKeys()) return@launch
                val r = VoiceJobs.speak(text, "javob") { _ -> }
                Player.play(r.file.absolutePath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logError("Ovoz", e)
                Ui.toast("Ovoz qo'shilmadi: " + friendly(e).take(100))
            }
        }
    }

    /** Matnni ovozga aylantirib (tanlangan ovoz dvigateli bilan), suhbatda audio fayl sifatida ko'rsatadi va ijro etadi. */
    suspend fun readAloud(text: String, name: String) {
        if (text.isBlank()) {
            say("assistant", "O'qib berish uchun matn topilmadi.")
            return
        }
        if (!VoiceJobs.hasKeys()) {
            say("assistant", VoiceJobs.NEED_KEY)
            return
        }
        if (text.length > 12000) {
            // Uzun matn: fon ishi sifatida (ilova o'chsa ham davom etadi, «Ovoz» bo'limida kuzatiladi).
            val st = withContext(Dispatchers.IO) { VoiceJobs.createFromText(name, text) }
            say("assistant", st.message)
            return
        }
        Ui.chatStatus = "Ovoz tayyorlanmoqda..."
        val r = VoiceJobs.speak(text, name) { pct -> scope.launch { Ui.chatStatus = "Ovoz tayyorlanmoqda: " + pct + "%" } }
        val note = if (r.note.isNotEmpty()) " " + r.note else ""
        say("assistant", "Ovoz tayyor (" + AudioInfo.clock(r.seconds) + ")." + note, listOf(outFile(r.file)))
        Player.play(r.file.absolutePath)
        Usage.bump("ovoz")
    }

    /** Xabar tagidagi «O'qib berish» tugmasi uchun. */
    fun readMessage(text: String) {
        val clean = text.substringBefore("\n\nManbalar:")
        launchChat("Ovoz tayyorlanmoqda...") { readAloud(clean, "javob") }
    }

    private suspend fun runIntent(niyat: String, matn: String, original: String) {
        when (niyat) {
            "ovoz" -> readAloud(Uz.buyruqSoziniOlibTashla(matn), "ovoz")
            "kiril" -> say("assistant", Uz.lotinToKiril(matn))
            "lotin" -> say("assistant", Uz.kirilToLotin(matn))
            "tahrir" -> Tools.tahrir(matn, "chat")
            "maqola" -> Tools.maqola(original, "chat")
            "tarjima" -> {
                val til = Uz.tilAjrat(original)
                val body = Uz.tilniOlibTashla(matn)
                say("assistant", Tools.studyText("tarjima", if (body.isBlank()) matn else body, til))
            }
            else -> answerQuestion(original)
        }
    }

    /** Xabar tagidagi «Amallar» menyusi: tanlangan xabar matni ustida ish bajaradi. */
    fun messageAction(action: String, text: String) {
        val clean = text.substringBefore("\n\nManbalar:")
        when (action) {
            "lotin" -> say("assistant", Uz.kirilToLotin(clean))
            "kiril" -> say("assistant", Uz.lotinToKiril(clean))
            "tahrir" -> Tools.tahrir(clean, "chat")
            "tarjima" -> launchChat("Tarjima qilinmoqda...") { say("assistant", Tools.studyText("tarjima", clean, "")) }
            "ovoz" -> readMessage(clean)
            "konspekt", "sodda", "savol" -> launchChat("Tayyorlanmoqda...") { say("assistant", Tools.studyText(action, clean, "")) }
        }
    }

    // ------------------------------------------------------------------
    // Ovozli savol (mikrofon)
    // ------------------------------------------------------------------
    fun startRecording(): Boolean {
        if (chatBusy()) {
            Ui.toast("Oldingi so'rov hali bajarilmoqda.")
            return false
        }
        Player.stop()
        if (Recorder.start(Store.app)) {
            Ui.recording = true
            return true
        }
        Ui.toast("Mikrofonni ishga tushirib bo'lmadi.")
        return false
    }

    fun stopRecording() {
        Ui.recording = false
        val f = Recorder.stop()
        if (f == null) {
            Ui.toast("Yozuv juda qisqa. Qayta urinib ko'ring.")
            return
        }
        val p = Picked("ovozli-xabar.m4a", "audio/mp4", f)
        val dur = AudioInfo.durationSec(f.absolutePath)
        launchChat("Ovoz matnga aylantirilmoqda (taxminan " + estimate(dur) + " soniya)...") {
            val text = Ai.transcribe(p, "savol").trim()
            if (text.isBlank() || text.contains(P.GAP_YOQ)) {
                say("assistant", "Ovozda so'z eshitilmadi. Qayta urinib ko'ring.")
                return@launchChat
            }
            say("user", text)
            Ui.chatStatus = "Javob tayyorlanmoqda..."
            handleText(text)
        }
    }

    fun estimate(durationSec: Int): Int = 3 + (durationSec * 0.12).toInt()

    // ------------------------------------------------------------------
    // Fayllar
    // ------------------------------------------------------------------
    fun onFiles(files: List<Picked>) {
        if (files.isEmpty()) return
        val caption = Ui.input.trim()
        if (caption.isNotEmpty()) {
            Ui.input = ""
            for (p in files) fileWithCaption(p, caption)
        } else {
            Ui.pending = files
            Ui.tab = 0
            Ui.sub = ""
        }
    }

    /** Fayl izoh bilan yuborilganda: izohdagi buyruq yoki savolga qarab amal tanlanadi. */
    private fun fileWithCaption(p: Picked, caption: String) {
        val kind = Kinds.of(p)
        val intent = Uz.niyatAniqla(caption)
        val study = Uz.studyFromCaption(caption)
        val textual = kind == Kinds.DOC || kind == Kinds.PDF || kind == Kinds.TEXT
        var action = ""
        if (textual) {
            if (intent.first == "ovoz") action = "audiokitob"
            else if (intent.first == "lotin") action = "lotin"
            else if (intent.first == "kiril") action = "kiril"
            else if (intent.first == "tahrir") action = "tahrir"
            else if (intent.first == "tarjima") action = "tarjima"
            else if (study.isNotEmpty()) action = if (study == "savol") "savol_tuz" else study
        }
        if (action.isEmpty()) {
            action = when (kind) {
                Kinds.IMAGE, Kinds.VIDEO, Kinds.PDF, Kinds.DOC, Kinds.TEXT -> "savol"
                Kinds.AUDIO -> if (intent.first == "tarjima") "stt_tarjima" else "stt_matn"
                else -> "savol"
            }
        }
        fileAction(p, action, caption)
    }

    fun actionLabel(a: String): String = when (a) {
        "tasvir" -> "tasvirlab berish"
        "matn" -> "matnni ajratib olish"
        "rasm_tarjima" -> "rasmdagi matnni tarjima qilish"
        "kitob" -> "kitobga qo'shish"
        "stt_matn" -> "matnga o'girish"
        "stt_tarjima" -> "tarjima (o'zbekchaga)"
        "audio_tushuntir" -> "mazmunini tushuntirish"
        "video_qisqa" -> "qisqa tavsif"
        "video_toliq" -> "to'liq tavsif"
        "video_matn" -> "gapni matnga o'girish"
        "video_tarjima" -> "tarjima (o'zbekchaga)"
        "audiokitob" -> "ovozga aylantirish"
        "tushuntir" -> "mazmunini tushuntirish"
        "konspekt" -> "konspekt"
        "savol_tuz" -> "savollar tuzish"
        "sodda" -> "sodda tushuntirish"
        "test" -> "test o'tkazish"
        "tarjima" -> "tarjima"
        "tahrir" -> "imlosini tuzatish"
        "lotin" -> "lotin yozuviga o'girish"
        "kiril" -> "kirill yozuviga o'girish"
        "matn_fayl" -> "matnini fayl qilib olish"
        "kitob_pdf" -> "kitob sifatida o'qish (skaner)"
        else -> "savol"
    }

    /** Dialogda ko'rsatiladigan amallar ro'yxati (fayl turiga qarab). */
    fun actionsFor(p: Picked): List<String> {
        return when (Kinds.of(p)) {
            Kinds.IMAGE -> listOf("tasvir", "matn", "rasm_tarjima", "kitob")
            Kinds.AUDIO -> listOf("stt_matn", "stt_tarjima", "audio_tushuntir")
            Kinds.VIDEO -> listOf("video_qisqa", "video_toliq", "video_matn", "video_tarjima")
            Kinds.PDF -> listOf("audiokitob", "tushuntir", "konspekt", "savol_tuz", "sodda", "test", "tarjima", "tahrir", "lotin", "kiril", "matn_fayl", "kitob_pdf")
            Kinds.DOC, Kinds.TEXT -> listOf("audiokitob", "tushuntir", "konspekt", "savol_tuz", "sodda", "test", "tarjima", "tahrir", "lotin", "kiril", "matn_fayl")
            else -> listOf("tushuntir", "tarjima", "matn_fayl")
        }
    }

    private suspend fun docText(p: Picked): String = withContext(Dispatchers.IO) { Docs.extract(p) }

    private fun limitText(t: String): String {
        return if (t.length > 600000) t.substring(0, 600000) + "\n[Hujjat uzun bo'lgani uchun faqat boshi ishlatildi]" else t
    }

    /** Tanlangan amalni bajaradi. Natija suhbatda ko'rsatiladi. */
    fun fileAction(p: Picked, action: String, caption: String) {
        Ui.pending = emptyList()
        say("user", "[" + p.name + "] " + (if (caption.isNotBlank()) caption else actionLabel(action)))

        if (action == "kitob") {
            Tools.bookAddImages(listOf(p))
            return
        }
        if (action == "kitob_pdf") {
            Tools.bookAddPdf(p)
            Ui.sub = "t_kitob"
            Ui.tab = 1
            return
        }
        if (action == "audiokitob" || action == "tahrir" || action == "test" || action == "matn_fayl" ||
            action == "konspekt" || action == "savol_tuz" || action == "sodda" || action == "tarjima" ||
            action == "lotin" || action == "kiril"
        ) {
            documentAction(p, action, caption)
            return
        }
        launchChat("Fayl tahlil qilinmoqda...") { mediaAction(p, action, caption) }
    }

    private suspend fun mediaAction(p: Picked, action: String, caption: String) {
        Usage.bump("fayl")
        val kind = Kinds.of(p)
        when (action) {
            "tasvir" -> say("assistant", Uz.plain(Ai.analyzeFile(p, P.VISION, 4096)))
            "matn" -> {
                val jpeg = withContext(Dispatchers.IO) { ImageKit.prepare(p.file) }
                if (jpeg == null) {
                    say("assistant", "Rasmni ochib bo'lmadi.")
                } else {
                    val t = Ai.ocrPage(jpeg).trim()
                    if (t.isBlank() || t.contains(P.GAP_YOQ)) say("assistant", "Rasmda matn topilmadi.") else say("assistant", t)
                }
            }
            "stt_matn", "stt_tarjima" -> {
                val dur = AudioInfo.durationSec(p.file.absolutePath)
                Ui.chatStatus = "Ovoz matnga aylantirilmoqda (taxminan " + estimate(dur) + " soniya)..."
                val t = Ai.transcribe(p, if (action == "stt_tarjima") "tarjima" else "matn").trim()
                if (t.isBlank() || t.contains(P.GAP_YOQ)) say("assistant", "Ovozda so'z eshitilmadi.") else emitLong("chat", t, "audio-matn")
            }
            "rasm_tarjima" -> say("assistant", Uz.plain(Ai.analyzeFile(p, P.IMAGE_TARJIMA, 4096)))
            "audio_tushuntir" -> say("assistant", Uz.plain(Ai.analyzeFile(p, P.AUDIO, 4096)))
            "video_qisqa" -> say("assistant", Uz.plain(Ai.analyzeFile(p, P.VIDEO_QISQA, 4096)))
            "video_toliq" -> say("assistant", Uz.plain(Ai.analyzeFile(p, P.VIDEO_TOLIQ, 8192)))
            "video_matn", "video_tarjima" -> {
                val prompt = if (action == "video_tarjima") P.VIDEO_TARJIMA else P.VIDEO_TRANSCRIPT
                val t = Ai.analyzeFile(p, prompt, 32000).trim()
                if (t.isBlank() || t.contains(P.GAP_YOQ)) say("assistant", "Videoda so'z eshitilmadi.") else emitLong("chat", t, "video-matn")
            }
            "tushuntir", "savol" -> {
                if (kind == Kinds.IMAGE) {
                    val q = if (caption.isNotBlank()) caption else P.VISION
                    say("assistant", Uz.plain(Ai.analyzeFile(p, q, 4096)))
                } else if (kind == Kinds.VIDEO) {
                    val q = if (caption.isNotBlank()) caption else P.VIDEO_QISQA
                    say("assistant", Uz.plain(Ai.analyzeFile(p, q, 8192)))
                } else if (kind == Kinds.AUDIO) {
                    val q = if (caption.isNotBlank()) caption else P.AUDIO
                    say("assistant", Uz.plain(Ai.analyzeFile(p, q, 8192)))
                } else {
                    explainDocument(p, caption)
                }
            }
            else -> explainDocument(p, caption)
        }
    }

    /** Hujjat bo'yicha savol yoki tushuntirish. */
    private suspend fun explainDocument(p: Picked, caption: String) {
        val text = try {
            docText(p)
        } catch (e: ScannedPdfException) {
            // matn qatlami yo'q — PDF ni modelning o'ziga beramiz (Gemini yoki Claude)
            val q = if (caption.isNotBlank()) caption + "\nJavobni faqat shu hujjatga tayanib ber." else P.DOC
            say("assistant", Uz.plain(Ai.analyzeFile(p, q, 8192)))
            return
        }
        val body = limitText(text)
        val msg = if (caption.isNotBlank()) {
            "HUJJAT MATNI:\n" + body + "\n\nSAVOL: " + caption + "\nJavobni faqat shu hujjatga tayanib ber."
        } else {
            P.DOC + "\n\nHUJJAT MATNI:\n" + body
        }
        val out = Ai.chat(systemFor(""), listOf(ChatMsg("user", msg)), answerTokens() * 2, false)
        say("assistant", formatAnswer(out.answer))
    }

    /** Matnli hujjat ustidagi amallar (ovozga aylantirish, konspekt, test ...). */
    private fun documentAction(p: Picked, action: String, caption: String) {
        launchChat("Fayl o'qilmoqda...") {
            if (action == "audiokitob") {
                // Audiolashtirish: matn olish (skanerlangan PDF ham, AI bilan o'qiladi) va imlo tuzatish fon xizmatida bajariladi.
                Ui.pending = emptyList()
                val st = withContext(Dispatchers.IO) { VoiceJobs.createFromPicked(p) }
                say("assistant", st.message)
                return@launchChat
            }
            val text = try {
                docText(p)
            } catch (e: ScannedPdfException) {
                say(
                    "assistant",
                    "Bu PDF skanerlangan ko'rinadi (matn qatlami yo'q), shuning uchun u kalitsiz o'qilmaydi. " +
                        "Kitob asbobida «PDF dan o'qish» ni tanlang yoki «audiokitob» amalini tanlang — sahifalar AI bilan o'qiladi."
                )
                return@launchChat
            }
            val name = p.name.substringBeforeLast('.')
            when (action) {
                "tahrir" -> Tools.tahrir(text, "chat")
                "matn_fayl" -> {
                    val f = Files.saveText(name, text)
                    say("assistant", "Matn tayyor (" + text.length + " belgi).", listOf(outFile(f)))
                }
                "lotin" -> emitLong("chat", Uz.kirilToLotin(text), name + "-lotin")
                "kiril" -> emitLong("chat", Uz.lotinToKiril(text), name + "-kiril")
                "test" -> {
                    Tools.quizFrom(limitText(text))
                    Ui.tab = 1
                    Ui.sub = "t_test"
                }
                else -> {
                    val mode = if (action == "savol_tuz") "savol" else action
                    val til = if (mode == "tarjima") Uz.tilAjrat(caption) else ""
                    Ui.chatStatus = "Tayyorlanmoqda..."
                    emitLong("chat", Tools.studyText(mode, limitText(text), til), name + "-" + mode)
                }
            }
        }
    }

    /** Uzun natija: matn suhbatda ko'rinadi, ortidan .txt fayl ham beriladi. */
    fun emitLong(dest: String, text: String, name: String) {
        val files = ArrayList<OutFile>()
        if (text.length >= 1500) {
            try {
                files.add(outFile(Files.saveText(name, text)))
            } catch (e: Exception) {
                // fayl yozilmasa — faqat matn
            }
        }
        emit(dest, text, files)
    }

    // ------------------------------------------------------------------
    // Kalitlar
    // ------------------------------------------------------------------
    /** Kalit boshlanishi mos kelmasa — qaysi ko'rinishda bo'lishi kerakligini aytadi. */
    private fun keyFormatHint(provider: String, key: String): String = when (provider) {
        Prov.GEMINI -> if (key.startsWith("AIza") || key.startsWith("AQ.")) "" else
            " Gemini kaliti odatda «AIza» yoki «AQ.» bilan boshlanadi (aistudio.google.com/apikey)."
        Prov.ANTHROPIC -> if (key.startsWith("sk-ant-")) "" else
            " Claude kaliti «sk-ant-» bilan boshlanadi (console.anthropic.com)."
        Prov.OPENAI -> if (key.startsWith("sk-")) "" else
            " ChatGPT (OpenAI) kaliti «sk-» bilan boshlanadi (platform.openai.com/api-keys)."
        else -> if (key.startsWith("xai-")) "" else
            " Grok (xAI) API kaliti «xai-» bilan boshlanadi (console.x.ai, API Keys bo'limi). Kiritilgan matn bunday ko'rinishda emas."
    }

    /** Kalitni berilgan o'ringa (1..Keys.MAX) tekshirib saqlaydi. Har bir xizmat uchun kamida 5 ta (eng ko'pi Keys.MAX ta) kalit qo'shish mumkin. */
    fun saveKey(provider: String, slot: Int, rawKey: String) {
        val clean = Keys.cleanKey(rawKey)
        if (clean.isEmpty()) {
            Ui.keyStatus[provider] = "Kalit bo'sh."
            return
        }
        Ui.keyStatus[provider] = "Tekshirilmoqda..."
        scope.launch {
            val r = Api.checkKey(provider, clean)
            val net = r.message.startsWith("Internetga") || r.message.startsWith("Tarmoq") || r.message.startsWith("Javob vaqtida")
            if (r.ok || net) {
                Keys.setSlot(provider, slot, clean)
                if (provider == Prov.XAI && r.models.isNotEmpty()) {
                    Settings.xaiModels = JSONArray(r.models).toString()
                }
                Journal.i("Kalit", Prov.name(provider) + " kaliti saqlandi (o'rin " + slot + ")" + (if (net) ", tarmoq yo'qligi sababli tekshirilmadi" else ""))
                val extra = if (net) " Internet yo'qligi sababli kalit tekshirilmay saqlandi." else ""
                Ui.keyStatus[provider] = "Kalit " + slot + " saqlandi." + extra + " " + unlockedText(provider)
                // Limit yoki kalit tugagani uchun to'xtab qolgan audiolashtirish ishlari yangi kalit bilan o'zi davom etadi.
                if (provider == Prov.GEMINI) VoiceJobs.resumeAfterKeyAdded(Store.app)
            } else {
                Journal.w("Kalit", Prov.name(provider) + " kaliti qabul qilinmadi: " + r.message)
                Ui.keyStatus[provider] = "Kalit saqlanmadi: " + r.message + keyFormatHint(provider, clean)
            }
        }
    }

    fun removeKey(provider: String, slot: Int) {
        Keys.removeSlot(provider, slot)
        Ui.keyStatus[provider] = "Kalit " + slot + " o'chirildi."
        if (Models.find(Settings.modelId)?.provider == provider && !Keys.has(provider)) Settings.modelId = ""
    }

    fun unlockedText(p: String): String = when (p) {
        Prov.GEMINI -> "Ochildi: savol-javob, internet qidiruvi, rasm, PDF, audio va video tahlili, ovozni matnga aylantirish, rasm yaratish, audiolashtirish (Gemini ovozi) va skanerlangan kitoblarni o'qish."
        Prov.ANTHROPIC -> "Ochildi: Claude bilan savol-javob, internet qidiruvi, rasm va PDF tahlili."
        Prov.OPENAI -> "Ochildi: ChatGPT bilan savol-javob, internet qidiruvi, rasm tahlili va ovozni matnga aylantirish."
        else -> "Ochildi (Grok): savol-javob (Grok 4.7 va boshqa modellar), internet va X qidiruvi, rasm tahlili, rasm yaratish va ovozni matnga aylantirish."
    }

    fun testVoice() {
        launchChat("Ovoz sinab ko'rilmoqda...") {
            readAloud("Assalomu alaykum! Bu barchasibittada ilovasining ovozi. Eshitilishi yaxshimi?", "sinov")
        }
    }
}
