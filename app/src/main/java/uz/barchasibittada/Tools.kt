package uz.barchasibittada

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Kitob rejimi: sahifalar matni qurilmada saqlanadi va ilova yopilsa ham yo'qolmaydi. */
object Book {
    private const val SEP = "\n\u0001\u0001PAGE\u0001\u0001\n"
    private val pages = ArrayList<String>()

    private fun file(): File = File(Store.app.filesDir, "kitob.txt")

    @Synchronized
    fun load() {
        pages.clear()
        try {
            val f = file()
            if (f.exists()) {
                val raw = f.readText(Charsets.UTF_8)
                if (raw.isNotEmpty()) {
                    for (p in raw.split(SEP)) if (p.isNotBlank()) pages.add(p)
                }
            }
        } catch (e: Exception) {
            pages.clear()
        }
        Ui.bookPages = pages.size
    }

    /** Sahifalar nusxasi (audiolashtirishda sahifa raqamlari ko'rsatilishi uchun). */
    @Synchronized
    fun pageList(): List<String> = ArrayList(pages)

    @Synchronized
    fun add(text: String) {
        pages.add(text)
        try {
            file().appendText(text + SEP, Charsets.UTF_8)
        } catch (e: Exception) {
            // xotirada qoladi
        }
        Ui.bookPages = pages.size
    }

    @Synchronized
    fun undo() {
        if (pages.isEmpty()) return
        pages.removeAt(pages.size - 1)
        try {
            val sb = StringBuilder()
            for (p in pages) sb.append(p).append(SEP)
            file().writeText(sb.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            // e'tiborsiz
        }
        Ui.bookPages = pages.size
    }

    @Synchronized
    fun clear() {
        pages.clear()
        try {
            file().delete()
        } catch (e: Exception) {
            // e'tiborsiz
        }
        Ui.bookPages = 0
    }

    @Synchronized
    fun text(): String = pages.joinToString("\n\n")

    @Synchronized
    fun count(): Int = pages.size
}

object Tools {
    // ------------------------------------------------------------------
    // O'quv rejimlari: savol, konspekt, sodda, tarjima
    // ------------------------------------------------------------------
    suspend fun studyText(mode: String, text: String, til: String): String {
        val system = when (mode) {
            "tarjima" -> Uz.translatePrompt(til)
            "savol" -> P.QUESTION
            "konspekt" -> P.SUMMARY
            "sodda" -> P.SIMPLE
            else -> T.SYSTEM_PROMPT
        }
        val out = Ai.chat(system, listOf(ChatMsg("user", text)), 8192, false)
        Usage.bump(mode)
        return Uz.plain(out.answer.text)
    }

    fun study(mode: String, text: String, til: String) {
        val t = text.trim()
        if (t.isEmpty()) {
            Ui.toast("Avval matn yoki mavzu kiriting.")
            return
        }
        Engine.startJob(mode, "Tayyorlanmoqda...") {
            val res = studyText(mode, t, til)
            Engine.emitLong(mode, res, mode)
        }
    }

    // ------------------------------------------------------------------
    // Lotin va kirill
    // ------------------------------------------------------------------
    fun yozuv(text: String, toLatin: Boolean) {
        val t = text.trim()
        if (t.isEmpty()) {
            Ui.toast("Avval matn kiriting.")
            return
        }
        val res = if (toLatin) Uz.kirilToLotin(t) else Uz.lotinToKiril(t)
        Engine.emitLong("yozuv", res, if (toLatin) "lotin" else "kiril")
    }

    // ------------------------------------------------------------------
    // Imlo tuzatish (tahrir)
    // ------------------------------------------------------------------
    fun tahrir(text: String, dest: String) {
        val t = text.trim()
        if (t.isEmpty()) {
            Ui.toast("Avval matn kiriting.")
            return
        }
        if (!Keys.any()) {
            Engine.emit(dest, T.NO_KEY)
            return
        }
        val parts = Uz.tahrirBolaklari(t, 3000)
        Engine.startJob(dest, "Imlo tuzatilmoqda: 0/" + parts.size) {
            val sb = StringBuilder()
            var i = 0
            for (part in parts) {
                i += 1
                Engine.progress("Imlo tuzatilmoqda: " + i + "/" + parts.size)
                try {
                    val out = Ai.chat(P.TAHRIR, listOf(ChatMsg("user", part)), 4096, false)
                    if (sb.isNotEmpty()) sb.append("\n")
                    sb.append(Uz.plain(out.answer.text))
                } catch (e: ProviderError) {
                    if (sb.isEmpty()) throw e
                    Engine.emitLong(dest, sb.toString() + "\n\n[To'xtadi: " + i + "/" + parts.size + " bo'lakdan keyin. Sabab: " + e.msg + "]", "tahrir-qism")
                    return@startJob
                }
            }
            Usage.bump("tahrir")
            Engine.emitLong(dest, sb.toString(), "tahrir")
        }
    }

    // ------------------------------------------------------------------
    // Maqola
    // ------------------------------------------------------------------
    fun maqola(raw: String, dest: String) {
        var sahifa = Uz.maqolaSahifaSoni(raw)
        val mavzu = Uz.maqolaMavzusi(raw, sahifa)
        if (mavzu.length < 3) {
            Engine.emit(dest, "Maqola mavzusini yozing. Masalan: Ona tili haqida 10 sahifali maqola yoz.")
            return
        }
        if (mavzu.length > 200) {
            Engine.emit(dest, "Mavzu juda uzun (" + mavzu.length + " belgi). Uni 200 belgidan qisqaroq yozing.")
            return
        }
        if (!Keys.any()) {
            Engine.emit(dest, T.NO_KEY)
            return
        }
        if (sahifa <= 0) sahifa = 5
        if (sahifa > 200) sahifa = 200
        val bolimSoni = maxOf(3, Math.round(sahifa / 1.6f))
        val jami = sahifa * 2200
        Engine.startJob(dest, "Maqola rejasi tuzilmoqda...") {
            val rejaSorov = "«" + mavzu + "» mavzusida " + bolimSoni + " bo'limdan iborat maqola rejasini tuz. " +
                "Faqat ro'yxatni yoz: har bir qatorda raqam va bo'lim nomi, boshqa hech narsa yozma. " +
                "Bo'lim nomlari qisqa bo'lsin: kirish, asosiy qismlar va xulosa ketma-ketligida."
            val rejaOut = Ai.chat(P.MAQOLA_REJA, listOf(ChatMsg("user", rejaSorov)), 4096, false)
            var reja = Uz.maqolaBolaklari(rejaOut.answer.text, bolimSoni)
            if (reja.isEmpty()) reja = listOf(mavzu)
            val n = reja.size
            val belgiBolim = maxOf(600, jami / n)
            val yozilgan = ArrayList<String>()
            var raqam = 0
            for (bolim in reja) {
                raqam += 1
                Engine.progress("Maqola: " + raqam + "/" + n + " bo'lim yozilmoqda")
                try {
                    val q = "Maqola mavzusi: " + mavzu + "\n" +
                        "Umumiy reja: " + reja.joinToString(", ") + "\n" +
                        "Yozilishi kerak bo'lgan bo'lim: " + raqam + ". " + bolim + "\n\n" +
                        "Shu bo'limni taxminan " + belgiBolim + " belgi hajmida to'liq yozib ber. " +
                        "Faqat shu bo'lim matnini yoz — boshqa bo'limlarga o'tma, umumiy xulosa yozma. " +
                        "Bo'lim sarlavhasini birinchi qatorga yoz."
                    val out = Ai.chat(P.MAQOLA, listOf(ChatMsg("user", q)), 8192, false)
                    yozilgan.add(Uz.plain(out.answer.text).trim())
                } catch (e: ProviderError) {
                    if (yozilgan.isEmpty()) throw e
                    val partial = "# " + mavzu + " (to'liq emas)\n\n" + yozilgan.joinToString("\n\n")
                    Engine.emitLong(dest, partial + "\n\n[Maqola " + raqam + "-bo'limda to'xtadi: " + e.msg + "]", "maqola-qism")
                    return@startJob
                }
            }
            val natija = "# " + mavzu + "\n\n" + yozilgan.filter { it.isNotBlank() }.joinToString("\n\n")
            Usage.bump("maqola")
            val haqiqiy = maxOf(1, Math.round(natija.length / 2200f))
            Engine.emitLong(dest, natija + "\n\n[Maqola tayyor: " + n + " bo'lim, taxminan " + haqiqiy + " sahifa]", "maqola")
        }
    }

    // ------------------------------------------------------------------
    // Test (interaktiv viktorina)
    // ------------------------------------------------------------------
    fun quizFrom(source: String) {
        val t = source.trim()
        if (t.isEmpty()) {
            Ui.toast("Avval mavzu yoki matn kiriting.")
            return
        }
        if (!Keys.any()) {
            Engine.emit("test", T.NO_KEY)
            return
        }
        Ui.quiz = null
        Engine.toolResultsClear("test")
        Engine.startJob("test", "Test tuzilmoqda...") {
            val n = 10
            val prompt = P.QUIZ.replace("{soni}", n.toString())
            var qs = emptyList<QuizQ>()
            for (attempt in 0 until 2) {
                val out = Ai.chat(prompt, listOf(ChatMsg("user", t)), 8192, false)
                qs = Uz.parseQuiz(out.answer.text, n)
                if (qs.size >= 2) break
            }
            if (qs.isEmpty()) {
                Engine.emit("test", "Test tuzib bo'lmadi. Mavzuni yoki matnni boshqacha yozib ko'ring.")
                return@startJob
            }
            Usage.bump("test")
            Ui.quiz = QuizState(qs)
        }
    }

    fun quizPick(letter: String) {
        val q = Ui.quiz ?: return
        if (q.picked.isNotEmpty() || q.finished) return
        q.picked = letter
        if (letter == q.questions[q.index].javob) q.correct += 1
    }

    fun quizNext() {
        val q = Ui.quiz ?: return
        if (q.index + 1 >= q.questions.size) {
            q.finished = true
        } else {
            q.index += 1
            q.picked = ""
        }
    }

    fun quizReset() {
        Ui.quiz = null
        Engine.toolResultsClear("test")
    }

    // ------------------------------------------------------------------
    // Rasm yaratish
    // ------------------------------------------------------------------
    fun rasm(prompt: String) {
        val t = prompt.trim()
        if (t.isEmpty()) {
            Ui.toast("Rasm tavsifini yozing.")
            return
        }
        Engine.startJob("rasm", "Rasm yaratilmoqda...") {
            val r = Ai.createImage(t)
            val ext = if (r.mime.contains("png")) "png" else if (r.mime.contains("webp")) "webp" else "jpg"
            val f = File(Store.outDir(), "rasm-" + System.currentTimeMillis() + "." + ext)
            withContext(Dispatchers.IO) { f.writeBytes(r.bytes) }
            Usage.bump("rasm")
            Engine.emit("rasm", "Rasm tayyor (" + Prov.name(r.provider) + "): " + t, listOf(Engine.outFile(f)), f.absolutePath)
        }
    }

    // ------------------------------------------------------------------
    // Matnni audiokitobga aylantirish
    // ------------------------------------------------------------------
    /** Matndan audiolashtirish ishi yaratadi (Gemini ovozi). Ish fonda bajariladi, jarayon «Ovoz» bo'limida ko'rinadi. */
    fun audioJob(text: String, name: String, dest: String) {
        val t = text.trim()
        if (t.isEmpty()) {
            Ui.toast("Avval matn kiriting yoki fayl tanlang.")
            return
        }
        Engine.scope.launch {
            val st = withContext(Dispatchers.IO) { VoiceJobs.createFromText(name, t) }
            Usage.bump("ovoz")
            Engine.emit(dest, st.message)
        }
    }

    /** Fayldan (PDF — skanerlangan ham, Word, EPUB, FB2, TXT) audiolashtirish ishi yaratadi. */
    fun audioFile(p: Picked, dest: String) {
        Engine.scope.launch {
            val st = withContext(Dispatchers.IO) { VoiceJobs.createFromPicked(p) }
            Usage.bump("ovoz")
            Engine.emit(dest, st.message)
        }
    }

    // ------------------------------------------------------------------
    // Kitob: sahifalarni rasmdan yoki skanerlangan PDF dan o'qish
    // ------------------------------------------------------------------
    fun bookAddImages(files: List<Picked>) {
        if (!Keys.any()) {
            Engine.emit("kitob", T.NO_KEY)
            Ui.tab = 1
            Ui.sub = "t_kitob"
            return
        }
        Ui.tab = 1
        Ui.sub = "t_kitob"
        Engine.startJob("kitob", "Sahifa o'qilmoqda: 1/" + files.size) {
            var added = 0
            var skipped = 0
            var idx = 0
            for (p in files) {
                idx += 1
                Engine.progress("Sahifa o'qilmoqda: " + idx + "/" + files.size)
                val jpeg = withContext(Dispatchers.IO) { ImageKit.prepare(p.file) }
                if (jpeg == null) {
                    skipped += 1
                    continue
                }
                val t = Ai.ocrPage(jpeg).trim()
                if (t.isNotBlank() && !t.contains(P.GAP_YOQ)) {
                    Book.add(t)
                    added += 1
                } else {
                    skipped += 1
                }
            }
            val extra = if (skipped > 0) " Matn topilmagan sahifalar: " + skipped + "." else ""
            Engine.emit("kitob", "Qo'shildi: " + added + " sahifa. Kitobda jami " + Book.count() + " sahifa." + extra)
        }
    }

    fun bookAddPdf(p: Picked) {
        Ui.tab = 1
        Ui.sub = "t_kitob"
        if (!Keys.any()) {
            Engine.emit("kitob", T.NO_KEY)
            return
        }
        Engine.startJob("kitob", "PDF tayyorlanmoqda...") {
            val total = withContext(Dispatchers.IO) { PdfKit.pageCount(p.file) }
            if (total <= 0) {
                Engine.emit("kitob", "PDF ni ochib bo'lmadi.")
                return@startJob
            }
            var added = 0
            var fails = 0
            for (i in 0 until total) {
                Engine.progress("PDF: " + (i + 1) + "/" + total + " sahifa o'qilmoqda")
                val jpeg = withContext(Dispatchers.IO) { PdfKit.renderPage(p.file, i) }
                if (jpeg == null) continue
                try {
                    val t = Ai.ocrPage(jpeg).trim()
                    fails = 0
                    if (t.isNotBlank() && !t.contains(P.GAP_YOQ)) {
                        Book.add(t)
                        added += 1
                    }
                } catch (e: ProviderError) {
                    fails += 1
                    if (fails >= 3 || e.keyBad || e.kind == "kunlik" || e.kind == "kredit" || e.kind == "kalit_yoq") {
                        Engine.emit("kitob", "PDF o'qish to'xtadi (" + (i + 1) + "/" + total + "): " + e.msg + " Hozirgacha qo'shildi: " + added + " sahifa.")
                        return@startJob
                    }
                }
            }
            Engine.emit("kitob", "PDF o'qildi: " + added + "/" + total + " sahifa kitobga qo'shildi. Kitobda jami " + Book.count() + " sahifa.")
        }
    }

    fun bookText() {
        val t = Book.text()
        if (t.isBlank()) {
            Ui.toast("Kitobda hali sahifa yo'q.")
            return
        }
        val f = Files.saveText("kitob", t)
        Engine.emit("kitob", "Kitob matni tayyor: " + Book.count() + " sahifa, " + t.length + " belgi.", listOf(Engine.outFile(f)))
    }

    fun bookAudio() {
        val pages = Book.pageList()
        if (pages.isEmpty() || Book.text().isBlank()) {
            Ui.toast("Kitobda hali sahifa yo'q.")
            return
        }
        Engine.scope.launch {
            val st = withContext(Dispatchers.IO) { VoiceJobs.createFromPages("kitob", pages) }
            Usage.bump("ovoz")
            Engine.emit("kitob", st.message)
        }
    }

    fun bookClean() {
        val t = Book.text()
        if (t.isBlank()) {
            Ui.toast("Kitobda hali sahifa yo'q.")
            return
        }
        tahrir(t, "kitob")
    }
}
