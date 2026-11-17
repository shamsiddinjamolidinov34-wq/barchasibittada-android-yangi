package uz.barchasibittada

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.Locale
import kotlinx.coroutines.runBlocking

/**
 * Audiolashtirish qaysi muhitda ishlayotganini bildiradi: fon xizmati (uzoq ishlar) yoki
 * qisqa o'qish (suhbatdagi javob). Yadro (VoiceCore) ikkalasida bir xil ishlaydi.
 */
interface VoiceHost {
    /** Foydalanuvchi to'xtatishni so'ragan bo'lsa true. */
    fun stopRequested(): Boolean

    /** Joriy holat (bildirishnoma uchun). Matn oddiy yoki xabar belgisi bo'lishi mumkin. */
    fun progress(title: String, text: String)

    /** Ish tugadi/to'xtadi: natija bildirishnomasi. */
    fun result(title: String, text: String, jobId: String)

    /** Uzoq ishda qurilma uyquga ketmasligi uchun. */
    fun refreshWake()

    /** Limit tugadi: ish resumeAt (ms, epoch) vaqtida o'zi davom etishi uchun rejalashtiriladi. */
    fun limitReached(jobId: String, resumeAt: Long)
}

/**
 * Audiolashtirish yadrosi (Audiokitob'dagi TtsService ichki qismi): matnni bo'laklarga bo'lib Gemini TTS ga yuboradi,
 * kalit va modellarni almashtiradi, qayta uriniladi, audioni MP3 fayliga ketma-ket yozadi va holatni diskka saqlaydi.
 * Hamma muhim hodisalar va xatolar xato jurnaliga yoziladi.
 */
class VoiceCore(private val ctx: Context, private val host: VoiceHost) {

    companion object {
        private const val MIN_CHUNK = 300
        private const val OCR_GAP_MS = 3500L

        /** Imlo tuzatish ko'rsatmasi (inglizcha: modellar shunga eng aniq amal qiladi). %TITLE% — kitob nomi. */
        private val SPELL_SYSTEM: String = listOf(
            "You are a very careful proofreader of scanned (OCR-produced) Uzbek book text. The book is: \"%TITLE%\". " +
                "Use the book's own content, era, style and vocabulary as the only authority.",
            "Your task: fix ONLY clear spelling and OCR mistakes in the passage you are given, and return the whole passage.",
            "Rules:",
            "1. Keep the original script (Latin, Cyrillic or Arabic), the author's orthography, vocabulary, archaic and dialect words, " +
                "names, titles, quotations and lines of verse. Never modernize, translate, transliterate, paraphrase, summarize, shorten, " +
                "expand or reorder anything.",
            "2. Fix only: wrongly recognized letters (for example l/I/1, O/0, rn/m, broken or substituted apostrophes in o' and g', " +
                "Latin and Cyrillic look-alike letters mixed inside one word), words wrongly split or glued together, stray symbols, " +
                "letters repeated because of scanning, and obvious typos that make a word meaningless in this context.",
            "3. If you are not sure that something is a mistake, leave it exactly as it is. Names, foreign words, terms and rare words " +
                "stay unless they are clearly corrupted.",
            "4. Keep paragraph breaks and the line breaks of poetry. Do not add headings, numbering, comments, notes or explanations.",
            "5. A context passage may be given before the passage; use it only to understand the content and never repeat it.",
            "6. Output only the corrected passage text, nothing else."
        ).joinToString("\n")

        /** Foydalanuvchi "Pauza" bosganini bildiruvchi xabar belgisi (har doim bir xil qoladi). */
        val USER_PAUSE: String by lazy { VMsg.make(R.string.msg_stopped) }
    }

    private class Cfg(val engine: String, val voice: String, val style: String, val chunk: Int, val rate: Int)

    private sealed class Out {
        /** pcm — 16-bit mono PCM (MP3 ga kodlanadi); mp3 — tayyor MP3 kadrlari (qayta kodlanmaydi). Ikkalasidan bittasi bo'ladi. */
        class Audio(val pcm: ByteArray?, val mp3: ByteArray?, val rate: Int, val ms: Long, val who: String) : Out()
        class Bad(val why: String) : Out()
        class Stop(val state: JobState, val msg: String) : Out()
    }

    private sealed class OcrOut {
        class Text(val text: String, val finish: String) : OcrOut()
        class Bad(val why: String) : OcrOut()

        /** Soʻrov katta (koʻp sahifa) boʻlgani uchun tarmoq koʻtarmadi: guruhni kichraytirib qayta yuborish kerak. */
        class Smaller(val why: String) : OcrOut()
        class Stop(val state: JobState, val msg: String) : OcrOut()
    }

    private class SpellOut(val text: String, val changed: Boolean, val kept: Boolean)

    /** Tilga bogʻliq xabar belgisi (diskka yoziladi, koʻrsatilganda matnga aylanadi). */
    private fun tok(id: Int, vararg a: Any?): String = VMsg.make(id, *a)

    /** Belgini matnga aylantiradi (bildirishnoma va jurnal uchun). Oddiy matn oʻzgarmaydi. */
    private fun txt(stored: String): String = VMsg.text(ctx, stored)

    private fun prepareAudioFile(id: String, job: VoiceJob) {
        val f = JobStore.audio(job)
        try {
            if (f.exists()) {
                if (f.length() > job.audioBytes) {
                    RandomAccessFile(f, "rw").use { it.setLength(job.audioBytes) }
                    Journal.i("Audio", "Yozilib ulgurmagan oxirgi qism kesib tashlandi (" + f.length() + " → " + job.audioBytes + ")")
                } else if (f.length() < job.audioBytes) {
                    val len = f.length()
                    JobStore.update(id) { it.audioBytes = len }
                    Journal.w("Audio", "Audio fayl kutilganidan qisqa: " + len + " < " + job.audioBytes)
                }
            } else if (job.audioBytes > 0L) {
                Journal.w("Audio", "Oldingi audio fayl topilmadi; yangi qism alohida yoziladi")
                JobStore.update(id) {
                    it.audioBytes = 0L
                    it.message = tok(R.string.msg_audio_prev_missing)
                }
            }
        } catch (t: Throwable) {
            Journal.e("Audio", "Audio faylni tayyorlab boʻlmadi", t)
        }
    }

    /**
     * Bitta ishni boshidan (yoki to'xtagan joyidan) oxirigacha bajaradi: kerak bo'lsa avval matn tayyorlanadi
     * (fayldan olinadi, skanerlangan sahifalar AI bilan o'qiladi), so'ng audiolashtiriladi. Fon oqimida chaqiriladi.
     */
    fun process(id: String) {
        val job0 = JobStore.get(id) ?: return
        if (!JobStore.source(id).exists()) {
            if (!prepare(id, job0)) return
        }
        val text = try {
            JobStore.source(id).readText(Charsets.UTF_8)
        } catch (t: Throwable) {
            fail(id, tok(R.string.msg_text_read_failed, t.message ?: ""), t)
            return
        }
        var job = JobStore.get(id) ?: return
        if (job.offset >= text.length) {
            finishDone(id)
            return
        }
        val marks = TextTools.loadMarks(JobStore.marks(id))
        // Ish yaratilganda tanlangan dvigatel va ovoz ishda saqlanadi (bo'sh — eski ish: Gemini).
        val eng = if (job.engine.isEmpty()) VEngine.GEMINI else job.engine
        val cfg = Cfg(eng, VEngine.voiceFor(eng, job.voice), VPrefs.style(ctx), VEngine.chunkFor(eng, VPrefs.chunk(ctx)), VPrefs.rate(ctx))
        val pool = KeyPool(ctx)
        pool.reload()
        if (eng == VEngine.GEMINI && pool.keyCount() == 0) {
            fail(id, tok(R.string.msg_no_keys), null)
            return
        }
        prepareAudioFile(id, job)
        job = JobStore.get(id) ?: return

        // Imlo tuzatish: sozlama yoqilgan bo'lsa (skaner qilingan kitoblar yoki hamma matn); qisqa o'qishda emas.
        val mode = VPrefs.spellMode(ctx)
        val corrOn = !job.quick && (mode == VPrefs.SPELL_ALL || (mode == VPrefs.SPELL_SCANNED && job.scanned))
        var spellOn = corrOn
        if (corrOn) prepareCorrected(id, job)
        job = JobStore.get(id) ?: return
        var spellFails = 0
        var fixedCount = job.spellFixed
        var keptCount = job.spellKept
        var prevTail = VUtil.tail(text.substring(0, minOf(job.offset, text.length)), 300)

        Journal.i(
            "Job",
            "Boshlandi/davom: '" + job.title + "' belgi " + job.offset + "/" + text.length + ", dvigatel " + cfg.engine +
                ", ovoz " + cfg.voice + ", boʻlak " + cfg.chunk + ", kalitlar " + pool.keyCount() +
                ", imlo tuzatish " + (if (corrOn) "yoqilgan" else "oʻchiq")
        )
        JobStore.update(id) {
            it.state = JobState.RUNNING
            it.message = tok(R.string.msg_starting)
            it.resumeAt = 0L
        }

        val sink = SinkHolder(id)
        var curMax = cfg.chunk
        var skipStreak = 0
        var offset = job.offset
        try {
            while (true) {
                if (host.stopRequested()) {
                    finishStop(id, JobState.PAUSED, USER_PAUSE, true)
                    break
                }
                if (JobStore.get(id) == null) break
                host.refreshWake()
                val gapStart = offset
                while (offset < text.length && text[offset].isWhitespace()) offset++
                if (offset >= text.length) {
                    finishDone(id)
                    break
                }
                val end = TextTools.nextEnd(text, offset, curMax)
                val raw = text.substring(offset, end)

                // Imlo tuzatish: xavfsizlik tekshiruvidan o'tmasa asl matn qoladi (kitob mazmuni o'zgarmaydi).
                var voiced = raw
                var fixedNow = false
                var keptNow = false
                if (spellOn && raw.trim().length >= 40 && TextTools.hasSpeakable(raw)) {
                    try {
                        val fx = fixSpelling(job.title, prevTail, raw)
                        spellFails = 0
                        voiced = fx.text
                        fixedNow = fx.changed
                        keptNow = fx.kept
                    } catch (t: Throwable) {
                        spellFails++
                        Journal.w("Imlo", "Tuzatib boʻlmadi (" + spellFails + "/3): " + VUtil.snippet(Gemini.describe(t), 160))
                        if (spellFails >= 3) {
                            spellOn = false
                            Journal.w("Imlo", "Ketma-ket 3 marta muvaffaqiyatsiz: shu ish davomida imlo tuzatish toʻxtatildi, asl matn audiolashtiriladi")
                        }
                    }
                    if (host.stopRequested()) continue
                }

                val speech = TextTools.forSpeech(voiced)
                if (!TextTools.hasSpeakable(speech)) {
                    offset = end
                    JobStore.update(id) { it.offset = end }
                    continue
                }

                val first = if (cfg.engine == VEngine.GEMINI) synth(id, pool, cfg, speech) else synthOther(id, cfg, speech)
                var res: Out = first
                if (first is Out.Audio && speech.length > 400) {
                    val secs0 = first.ms / 1000.0
                    if (implausible(speech, secs0)) {
                        Journal.w(
                            "Audio",
                            "Shubhali davomiylik: " + speech.length + " belgi → " + VUtil.fmt1(secs0) + " s; boʻlaklanadi"
                        )
                        res = Out.Bad("audio davomiyligi matnga mos emas (" + VUtil.fmt1(secs0) + " s)")
                    }
                }
                val rr = res
                when (rr) {
                    is Out.Audio -> {
                        val err = sink.append(rr)
                        if (err != null) {
                            finishStop(id, JobState.FAILED, err, false)
                            Journal.e("Audio", txt(err))
                            break
                        }
                        val ms = rr.ms
                        val cur = JobStore.get(id)
                        if (cur == null) break
                        val bytes = JobStore.audio(cur).length()
                        val label = TextTools.labelAt(marks, end - 1)
                        var corrLen = cur.corrBytes
                        if (corrOn) corrLen = appendCorrected(id, voiced, text.substring(gapStart, offset), cur.corrBytes)
                        if (fixedNow) fixedCount++
                        if (keptNow) keptCount++
                        offset = end
                        JobStore.update(id) {
                            it.offset = end
                            it.audioMs += ms
                            it.audioBytes = bytes
                            it.chunksDone++
                            it.lastSnippet = VUtil.tail(raw, 140)
                            it.posLabel = label
                            it.state = JobState.RUNNING
                            it.message = ""
                            it.corrBytes = corrLen
                            it.spellFixed = fixedCount
                            it.spellKept = keptCount
                        }
                        prevTail = VUtil.tail(voiced, 300)
                        curMax = cfg.chunk
                        skipStreak = 0
                        val secs = rr.ms / 1000.0
                        Journal.i(
                            "Chunk",
                            "OK " + offset + "/" + text.length + ": " + speech.length + " belgi → " + VUtil.fmt1(secs) +
                                " s; " + rr.who + (if (fixedNow) "; imlo tuzatildi" else "")
                        )
                        val pct = (offset.toLong() * 100L / text.length).toInt()
                        host.progress(cur.title, ctx.getString(R.string.notif_progress, pct, VUtil.fmtDuration(ctx, cur.audioMs + ms)))
                    }
                    is Out.Bad -> {
                        val size = end - offset
                        if (size > MIN_CHUNK) {
                            curMax = maxOf(MIN_CHUNK, size / 2)
                            Journal.w("Job", "Qism boʻlaklanmoqda: " + size + " → " + curMax + " belgi. Sabab: " + rr.why)
                        } else {
                            recordSkipped(id, raw, marks, offset, rr.why)
                            offset = end
                            JobStore.update(id) {
                                it.offset = end
                                it.skipped++
                                it.message = tok(R.string.msg_chunk_skipped)
                            }
                            curMax = cfg.chunk
                            skipStreak++
                            if (skipStreak >= 3) {
                                finishStop(id, JobState.PAUSED, tok(R.string.msg_3_skipped), true)
                                break
                            }
                        }
                    }
                    is Out.Stop -> {
                        finishStop(id, rr.state, rr.msg, true)
                        break
                    }
                }
            }
        } finally {
            sink.close()
        }
    }

    private fun prepare(id: String, job: VoiceJob): Boolean {
        JobStore.update(id) {
            it.state = JobState.PREPARING
            it.message = tok(R.string.msg_preparing)
        }
        host.progress(job.title, ctx.getString(R.string.msg_preparing))
        val orig = JobStore.original(id)
        if (!orig.exists()) {
            fail(id, tok(R.string.msg_orig_missing), null)
            return false
        }
        // Skanerlangan PDF: oʻqilgan sahifalar shu keshda saqlanadi (limit tugasa yoki ilova oʻchsa ham qayta oʻqilmaydi).
        val cache = OcrCache(JobStore.ocrDir(id))
        var ocrCalled = false
        val ocr: OcrSupport? = if (VPrefs.ocrEnabled(ctx)) {
            OcrSupport(cache) { need, total ->
                ocrCalled = true
                ocrRun(id, orig, cache, need, total)
            }
        } else {
            null
        }
        val ex = try {
            Extractors.extract(ctx.applicationContext, orig, job.srcName, ocr) { p ->
                JobStore.update(id) { it.message = p }
                host.progress(job.title, txt(p))
            }
        } catch (e: OcrInterrupted) {
            savePartial(id, e.partial)
            val m = if (e.msg == USER_PAUSE) e.msg else tok(R.string.msg_ocr_interrupted, txt(e.msg), e.done, e.total)
            Journal.w("OCR", "Toʻxtadi (" + e.state.name + "): " + txt(m))
            finishStop(id, e.state, m, true, true)
            return false
        } catch (e: ExtractException) {
            fail(id, e.token(), e)
            return false
        } catch (t: Throwable) {
            fail(id, tok(R.string.msg_read_failed_x, t.javaClass.simpleName + ": " + (t.message ?: "")), t)
            return false
        }
        try {
            JobStore.source(id).writeText(ex.text, Charsets.UTF_8)
            TextTools.saveMarks(JobStore.marks(id), ex.marks)
        } catch (t: Throwable) {
            fail(id, tok(R.string.msg_save_text_failed, t.message ?: ""), t)
            return false
        }
        JobStore.update(id) {
            it.total = ex.text.length
            it.preview = VUtil.snippet(ex.text, 160)
            it.message = ex.warning ?: ""
            it.scanned = ocrCalled
        }
        val warn = ex.warning
        if (warn != null) Journal.w("Job", txt(warn))
        if (ex.unreadable.isNotEmpty()) recordUnreadable(id, ex)
        try {
            orig.delete()
        } catch (t: Throwable) {
        }
        RemotePdf.cleanup(ctx, JobStore.ocrDir(id))
        cache.clear()
        try {
            JobStore.partial(id).delete()
        } catch (t: Throwable) {
        }
        Journal.i("Job", "Matn tayyor: " + ex.text.length + " belgi, joy belgilari: " + ex.marks.size)
        return true
    }

    // ---------------------------------------------------------------- skanerlangan PDF: sahifalarni AI (Gemini Vision) bilan oʻqish

    /** OCR toʻxtab qolganda shu paytgacha oʻqilgan matn (raqamli + OCR) alohida faylga yoziladi: uni TXT sifatida olish mumkin. */
    private fun savePartial(id: String, partial: Extracted?) {
        if (partial == null || partial.text.isBlank()) return
        try {
            JobStore.partial(id).writeText(partial.text, Charsets.UTF_8)
        } catch (t: Throwable) {
            Journal.w("OCR", "Qisman matnni saqlab boʻlmadi", t)
        }
    }

    /** OCR bilan ham oʻqib boʻlmagan sahifalar oʻtkazib yuborilganlar roʻyxatiga yoziladi: hech narsa jim yoʻqolmaydi. */
    private fun recordUnreadable(id: String, ex: Extracted) {
        val n = ex.unreadable.size
        try {
            val sb = StringBuilder()
            for (u in ex.unreadable) {
                sb.append(ctx.getString(R.string.skip_ocr_line, u.first, u.second)).append("\n\n")
            }
            JobStore.skipped(id).appendText(sb.toString(), Charsets.UTF_8)
            JobStore.update(id) { it.skipped += n }
            Journal.w("OCR", "Oʻqib boʻlmagan sahifalar: " + n)
        } catch (t: Throwable) {
            Journal.w("OCR", "Oʻqib boʻlmagan sahifalarni yozib boʻlmadi", t)
        }
    }

    private fun setPreparing(id: String, msg: String) {
        val j = JobStore.get(id) ?: return
        if (j.state == JobState.PREPARING && j.message == msg) return
        JobStore.update(id) {
            it.state = JobState.PREPARING
            it.message = msg
        }
        host.progress(j.title, msg)
    }

    /** Sahifani JPEG ga aylantiradi. Xotira yetmasa rasm kichraytiriladi; boʻlmasa null. */
    private fun renderPage(r: PageRenderer, page: Int, maxSide: Int): ByteArray? {
        var side = maxSide
        var tries = 0
        while (tries < 3) {
            try {
                return r.jpeg(page, side)
            } catch (e: OutOfMemoryError) {
                Journal.w("OCR", "Sahifa " + page + ": xotira yetmadi, rasm kichraytirilmoqda (" + side + " px)")
                side = side * 2 / 3
            } catch (t: Throwable) {
                Journal.e("OCR", "Sahifa " + page + " rasmga aylanmadi", t)
                return null
            }
            tries++
        }
        return null
    }

    /**
     * Skanerlangan (matnsiz, buzilgan yoki juda qisqa) sahifalarni Gemini Vision orqali oʻqib, keshga yozadi.
     * Ketma-ket sahifalar guruhlanadi (odatda 4 tadan; Sozlamalarda oʻzgartiriladi). Raqamli matni yetarli sahifalar bu yerga kelmaydi.
     * Toʻxtab qolsa (limit, pauza, tarmoq, xato) OcrInterrupted tashlanadi; oʻqilgan sahifalar keshda qoladi va
     * "Davom ettirish" bosilganda shu joydan davom etadi.
     */
    private fun ocrRun(id: String, pdf: File, cache: OcrCache, need: List<Int>, totalPages: Int) {
        cache.clearBad()
        val doneBefore = cache.donePages(false)
        val todo = ArrayList<Int>()
        for (p in need) if (!doneBefore.contains(p)) todo.add(p)
        Journal.i(
            "OCR",
            "Boshlandi: skaner sahifalar " + need.size + ", oldin oʻqilgan " + (need.size - todo.size) + ", qolgan " + todo.size
        )
        if (todo.isEmpty()) return

        val needSet = HashSet<Int>(need)
        fun doneCount(): Int {
            var c = 0
            for (p in cache.donePages(false)) if (needSet.contains(p)) c++
            return c
        }

        fun stopWith(state: JobState, msg: String): OcrInterrupted = OcrInterrupted(state, msg, null, doneCount(), need.size)

        if (VPrefs.ocrWhole(ctx)) {
            // Avval butun fayl usuli: PDF bir marta yuklanadi, har so'rovda ko'p sahifa o'qiladi.
            if (ocrRunWhole(id, pdf, cache, need, totalPages)) return
            // Usul ishlamadi: qolgan sahifalar sahifa-rasm usulida o'qiladi.
            val done2 = cache.donePages(false)
            todo.clear()
            for (p in need) if (!done2.contains(p)) todo.add(p)
            if (todo.isEmpty()) return
        }

        val pool = KeyPool(ctx, true)
        pool.reload()
        if (pool.keyCount() == 0) throw stopWith(JobState.FAILED, tok(R.string.msg_no_keys))

        var batchMax = VPrefs.ocrPages(ctx)
        var size = batchMax
        // Tarmoq katta soʻrovni koʻtarmasa, guruh vaqtincha kichrayadi; har bir muvaffaqiyatdan keyin ikki baravar tiklanadi.
        var netCap = batchMax
        val renderer: PageRenderer = try {
            PageRenderer.open(pdf)
        } catch (t: Throwable) {
            Journal.e("OCR", "PDF ni rasmga aylantirish uchun ochib boʻlmadi", t)
            val m = if (t is SecurityException) tok(R.string.err_pdf_password) else tok(R.string.msg_ocr_render_failed, Gemini.describe(t))
            throw stopWith(JobState.FAILED, m)
        }

        val lastCall = HashMap<String, Long>()
        val jpegCache = HashMap<Int, ByteArray>()
        val forceSingle = HashSet<Int>()
        var badStreak = 0
        var markerFails = 0
        var idx = 0

        fun markBad(page: Int, why: String) {
            cache.putBad(page, why)
            jpegCache.remove(page)
            badStreak++
            Journal.e("OCR", "Sahifa " + page + " oʻqilmadi: " + why)
        }

        try {
            while (idx < todo.size) {
                if (host.stopRequested() || JobStore.get(id) == null) throw stopWith(JobState.PAUSED, USER_PAUSE)
                host.refreshWake()

                // Navbatdagi ketma-ket sahifalar guruhi (raqamlari ketma-ket boʻlishi shart: tartib buzilmasligi uchun).
                val firstPage = todo[idx]
                var n = 1
                if (!forceSingle.contains(firstPage)) {
                    while (n < size && idx + n < todo.size) {
                        val nextPage = todo[idx + n]
                        if (nextPage != todo[idx + n - 1] + 1 || forceSingle.contains(nextPage)) break
                        n++
                    }
                }

                // Sahifalarni rasmga aylantirish
                val jpegs = ArrayList<ByteArray>()
                var k = 0
                while (k < n) {
                    val pg = todo[idx + k]
                    var b: ByteArray? = jpegCache[pg]
                    if (b == null) {
                        b = renderPage(renderer, pg, PageRenderer.MAX_SIDE)
                        if (b != null) jpegCache[pg] = b
                    }
                    if (b == null) break
                    jpegs.add(b)
                    k++
                }
                if (k == 0) {
                    // Birinchi sahifaning oʻzi rasmga aylanmadi: oʻtkazib yuboriladi.
                    markBad(firstPage, "rasmga aylantirib boʻlmadi")
                    idx++
                    if (badStreak >= 3) throw stopWith(JobState.PAUSED, tok(R.string.msg_ocr_3_bad))
                    continue
                }
                n = k

                val batch = ArrayList<Int>()
                for (j in 0 until n) batch.add(todo[idx + j])
                val lastPage = batch[n - 1]
                val status = if (n > 1) {
                    tok(R.string.msg_ocr_progress, firstPage, lastPage, totalPages)
                } else {
                    tok(R.string.msg_ocr_progress_one, firstPage, totalPages)
                }

                val reply = ocrCall(id, pool, batch, lastCall, status) { slot ->
                    Gemini.recognize(slot.key, slot.model, batch, jpegs) { host.stopRequested() }
                }
                when (reply) {
                    is OcrOut.Stop -> throw stopWith(reply.state, reply.msg)
                    is OcrOut.Smaller -> {
                        netCap = maxOf(1, n / 2)
                        size = minOf(batchMax, netCap)
                        Journal.w(
                            "OCR",
                            "Sahifalar " + firstPage + "–" + lastPage + ": tarmoq katta soʻrovni koʻtarmadi (" + reply.why + "); guruh " + size + " sahifagacha kichraytiriladi"
                        )
                    }
                    is OcrOut.Bad -> {
                        if (n > 1) {
                            size = maxOf(1, n / 2)
                            Journal.w(
                                "OCR",
                                "Sahifalar " + firstPage + "–" + lastPage + " oʻqilmadi (" + reply.why + "); guruh " + size + " sahifagacha kichraytiriladi"
                            )
                        } else {
                            markBad(firstPage, reply.why)
                            idx++
                            size = minOf(batchMax, netCap)
                            if (badStreak >= 3) throw stopWith(JobState.PAUSED, tok(R.string.msg_ocr_3_bad))
                        }
                    }
                    is OcrOut.Text -> {
                        val fin = reply.finish
                        val complete = fin.isEmpty() || fin == "STOP"
                        var accepted = false
                        if (n > 1) {
                            val parts = if (complete) OcrParse.split(reply.text, batch) else null
                            if (parts != null) {
                                val cleaned = LinkedHashMap<Int, String>()
                                var withText = 0
                                for (pg in batch) {
                                    val t = TextTools.cleanOcrPage(parts[pg] ?: "")
                                    cleaned[pg] = t
                                    if (t.isNotBlank()) withText++
                                }
                                // Boshqa sahifalarda matn bor-u, bir sahifa boʻsh chiqsa — u alohida qayta tekshiriladi
                                // (model sahifani jim tashlab ketgan boʻlishi mumkin; kitobdan sahifa yoʻqolmasligi kerak).
                                val empties = ArrayList<Int>()
                                for (pg in batch) {
                                    val t = cleaned[pg] ?: ""
                                    if (t.isBlank() && withText > 0) {
                                        empties.add(pg)
                                    } else {
                                        cache.putText(pg, pg, t)
                                    }
                                }
                                idx += n
                                if (empties.isNotEmpty()) {
                                    for (e in empties) forceSingle.add(e)
                                    todo.addAll(idx, empties)
                                    Journal.i("OCR", "Boʻsh chiqqan sahifalar alohida tekshiriladi: " + empties.joinToString(", "))
                                }
                                accepted = true
                            } else {
                                markerFails++
                                if (markerFails >= 3 && batchMax > 1) {
                                    batchMax = 1
                                    Journal.w("OCR", "Model sahifa belgilariga ketma-ket amal qilmadi — endi har bir sahifa alohida yuboriladi")
                                }
                                size = minOf(batchMax, maxOf(1, n / 2))
                                Journal.w(
                                    "OCR",
                                    "Sahifalar " + firstPage + "–" + lastPage + ": javob mos kelmadi (finishReason=" + fin + "); guruh " + size + " sahifagacha kichraytiriladi"
                                )
                            }
                        } else {
                            val t = TextTools.cleanOcrPage(reply.text)
                            if (t.isBlank() && !complete) {
                                markBad(firstPage, "finishReason=" + fin)
                                idx++
                                size = minOf(batchMax, netCap)
                                if (badStreak >= 3) throw stopWith(JobState.PAUSED, tok(R.string.msg_ocr_3_bad))
                            } else {
                                if (!complete) Journal.w("OCR", "Sahifa " + firstPage + " toʻliq emas boʻlishi mumkin (finishReason=" + fin + ")")
                                cache.putText(firstPage, firstPage, t)
                                idx++
                                accepted = true
                            }
                        }
                        if (accepted) {
                            for (pg in batch) jpegCache.remove(pg)
                            netCap = minOf(batchMax, netCap * 2)
                            size = minOf(batchMax, netCap)
                            badStreak = 0
                        }
                    }
                }
            }
        } finally {
            renderer.close()
        }
        Journal.i("OCR", "Tugadi: oʻqilgan sahifalar " + doneCount() + " / " + need.size)
        // Birorta sahifa oʻqilmagan-u, sabab xato boʻlsa — bu "matn yoʻq" degani emas: ish pauzaga qoʻyiladi, "Davom ettirish" qayta urinadi.
        if (doneCount() == 0 && cache.bads().isNotEmpty()) {
            throw stopWith(JobState.PAUSED, tok(R.string.msg_ocr_none_read))
        }
    }

    // ---------------------------------------------------------------- skanerlangan PDF: sahifa-sahifa emas, butun fayl sifatida (Gemini Files API)

    /**
     * Skanerlangan PDF ni sahifa-sahifa emas, butun fayl sifatida o'qiydi: PDF Gemini Files API ga bir marta yuklanadi (har bir kalit uchun),
     * so'ng har bir so'rovda modelning chiqish hajmi ruxsat bergancha ko'p ketma-ket sahifa o'qiladi (matn zichligiga qarab o'zi moslanadi).
     * Natija: true — hamma sahifa ishlandi; false — Files API yoki model bu fayl bilan ishlamadi, qolgan sahifalarni sahifa-rasm usulida o'qish kerak.
     * Limit, pauza va tarmoq xatosida OcrInterrupted tashlanadi (o'qilgan sahifalar keshda qoladi, davom ettirilganda qayta o'qilmaydi).
     */
    private fun ocrRunWhole(id: String, pdf: File, cache: OcrCache, need: List<Int>, totalPages: Int): Boolean {
        val needSet = HashSet<Int>(need)

        fun remaining(): List<Int> {
            val seen = cache.donePages(true)
            val l = ArrayList<Int>()
            for (p in need) if (!seen.contains(p)) l.add(p)
            return l
        }

        fun doneCount(): Int {
            var c = 0
            for (p in cache.donePages(false)) if (needSet.contains(p)) c++
            return c
        }

        fun stopWith(state: JobState, msg: String): OcrInterrupted = OcrInterrupted(state, msg, null, doneCount(), need.size)

        val pool = KeyPool(ctx, true)
        pool.reload()
        if (pool.keyCount() == 0) throw stopWith(JobState.FAILED, tok(R.string.msg_no_keys))

        val remote = RemotePdf(JobStore.ocrDir(id), pdf)
        val userMax = VPrefs.ocrWindow(ctx)
        val hardMax = if (userMax > 0) userMax else VPrefs.OCR_WINDOW_MAX
        val firstModel = VPrefs.ocrModels(ctx).firstOrNull() ?: VPrefs.DEFAULT_OCR_MODEL
        var charsPerPage = 3000.0
        var netCap = hardMax
        val single = HashSet<Int>()
        val lastCall = HashMap<String, Long>()
        var badStreak = 0
        var markerFails = 0
        var uploadBad = false

        Journal.i(
            "OCR",
            "Butun fayl usuli: sahifalar " + need.size + ", PDF " + (pdf.length() / 1024L) + " KB, bir so'rovda eng ko'pi " +
                hardMax + " sahifa" + (if (userMax > 0) "" else " (avtomatik)")
        )

        // Bir so'rovdagi sahifalar soni: modelning chiqish hajmi (tokenlar) va o'qilgan sahifalardagi o'rtacha matn hajmidan kelib chiqadi.
        fun target(): Int {
            if (userMax > 0) return userMax
            val tokPerPage = charsPerPage / 2.3
            val w = (Gemini.outputCap(firstModel) * 0.55 / tokPerPage).toInt()
            return w.coerceIn(1, minOf(VPrefs.OCR_AUTO_MAX, hardMax))
        }

        fun upload(slot: KeyPool.Slot): Gemini.Result {
            var lastShown = -10
            var lastTime = 0L
            val r = Gemini.uploadFile(slot.key, pdf, "application/pdf", "kitob-" + id, { host.stopRequested() }) { pct ->
                val now = System.currentTimeMillis()
                if (pct >= lastShown + 5 || now - lastTime > 4000L) {
                    lastShown = pct
                    lastTime = now
                    setPreparing(id, "PDF Gemini'ga yuklanmoqda (kalit " + slot.keySlot + "): " + pct + "%")
                }
            }
            if (r.kind == Gemini.Kind.OK) {
                val uri = r.text ?: ""
                if (uri.isEmpty()) return Gemini.Result(Gemini.Kind.BAD_CHUNK, message = "Files API fayl manzilini bermadi")
                remote.put(slot.key, RemotePdf.Entry(uri, r.finish ?: "", System.currentTimeMillis()))
                Journal.i("OCR", "PDF yuklandi (kalit " + slot.keySlot + "): " + (r.finish ?: ""))
            } else if (r.kind == Gemini.Kind.BAD_CHUNK || r.kind == Gemini.Kind.MODEL || r.kind == Gemini.Kind.NO_AUDIO) {
                uploadBad = true
            }
            return r
        }

        // Oldin yuklangan fayl muddati tugagan yoki boshqa loyihaga tegishli bo'lsa, Gemini «file ... not exist / permission» deydi.
        fun fileGone(r: Gemini.Result): Boolean {
            if (r.kind != Gemini.Kind.AUTH && r.kind != Gemini.Kind.BAD_CHUNK && r.kind != Gemini.Kind.MODEL) return false
            val m = r.message.lowercase(Locale.ROOT)
            return m.contains("file") && (m.contains("not exist") || m.contains("not found") || m.contains("permission") ||
                m.contains("active") || m.contains("expired") || m.contains("invalid"))
        }

        fun fileCall(slot: KeyPool.Slot, first: Int, last: Int): Gemini.Result {
            var ent = remote.get(slot.key)
            var fresh = false
            if (ent == null) {
                val up = upload(slot)
                if (up.kind != Gemini.Kind.OK) return up
                ent = remote.get(slot.key)
                fresh = true
            }
            if (ent == null) return Gemini.Result(Gemini.Kind.BAD_CHUNK, message = "Files API fayli topilmadi")
            var r = Gemini.recognizeFile(slot.key, slot.model, ent.uri, first, last, totalPages) { host.stopRequested() }
            if (!fresh && fileGone(r)) {
                Journal.w("OCR", "Oldin yuklangan fayl yaroqsiz (muddati tugagan bo'lishi mumkin), qayta yuklanadi: " + r.message)
                remote.drop(slot.key)
                val up = upload(slot)
                if (up.kind != Gemini.Kind.OK) return up
                val e2 = remote.get(slot.key) ?: return Gemini.Result(Gemini.Kind.BAD_CHUNK, message = "Files API fayli topilmadi")
                r = Gemini.recognizeFile(slot.key, slot.model, e2.uri, first, last, totalPages) { host.stopRequested() }
            }
            return r
        }

        while (true) {
            if (host.stopRequested() || JobStore.get(id) == null) throw stopWith(JobState.PAUSED, USER_PAUSE)
            host.refreshWake()
            val todo = remaining()
            if (todo.isEmpty()) break

            // Navbatdagi ketma-ket sahifalar oralig'i (raqamlari ketma-ket bo'lishi shart: tartib buzilmasligi uchun).
            var size = minOf(target(), netCap)
            if (single.contains(todo[0])) size = 1
            var n = 1
            while (n < size && n < todo.size && todo[n] == todo[n - 1] + 1 && !single.contains(todo[n])) n++
            val batch = ArrayList<Int>(n)
            for (j in 0 until n) batch.add(todo[j])
            val first = batch[0]
            val last = batch[n - 1]
            val status = if (n > 1) {
                tok(R.string.msg_ocr_progress, first, last, totalPages)
            } else {
                tok(R.string.msg_ocr_progress_one, first, totalPages)
            }

            val reply = ocrCall(id, pool, batch, lastCall, status) { slot -> fileCall(slot, first, last) }
            when (reply) {
                is OcrOut.Stop -> throw stopWith(reply.state, reply.msg)
                is OcrOut.Smaller -> {
                    netCap = maxOf(1, n / 2)
                    Journal.w(
                        "OCR",
                        "Sahifalar " + first + "-" + last + ": aloqa katta javobni ko'tarmadi (" + reply.why +
                            "); oraliq " + netCap + " sahifagacha kichraytiriladi"
                    )
                }
                is OcrOut.Bad -> {
                    if (uploadBad) {
                        Journal.w("OCR", "PDF ni Gemini Files API ga yuklab bo'lmadi (" + reply.why + "): sahifa-rasm usuliga o'tildi")
                        return false
                    }
                    if (n > 1) {
                        netCap = maxOf(1, n / 2)
                        Journal.w(
                            "OCR",
                            "Sahifalar " + first + "-" + last + " o'qilmadi (" + reply.why + "); oraliq " + netCap + " sahifagacha kichraytiriladi"
                        )
                    } else {
                        cache.putBad(first, reply.why)
                        badStreak++
                        Journal.e("OCR", "Sahifa " + first + " o'qilmadi: " + reply.why)
                        if (badStreak >= 3) throw stopWith(JobState.PAUSED, tok(R.string.msg_ocr_3_bad))
                    }
                }
                is OcrOut.Text -> {
                    val fin = reply.finish
                    val complete = fin.isEmpty() || fin == "STOP"
                    if (n == 1) {
                        // Bitta sahifa: belgisiz javob ham qabul qilinadi.
                        val t = TextTools.cleanOcrPage(OcrParse.stripMarkers(reply.text))
                        if (t.isBlank() && !complete) {
                            cache.putBad(first, "finishReason=" + fin)
                            badStreak++
                            Journal.e("OCR", "Sahifa " + first + " o'qilmadi: finishReason=" + fin)
                            if (badStreak >= 3) throw stopWith(JobState.PAUSED, tok(R.string.msg_ocr_3_bad))
                        } else {
                            if (!complete) Journal.w("OCR", "Sahifa " + first + " to'liq bo'lmasligi mumkin (finishReason=" + fin + ")")
                            cache.putText(first, first, t)
                            single.remove(first)
                            badStreak = 0
                            netCap = minOf(hardMax, maxOf(netCap, 1) * 2)
                        }
                    } else {
                        val sp = OcrParse.splitPrefix(reply.text, batch)
                        // Oxirgi topilgan sahifa kesilgan bo'lishi mumkin: u keyingi so'rovda qayta o'qiladi.
                        var take = sp.matched
                        if (take < n) take = maxOf(0, take - 1)
                        if (take == 0) {
                            markerFails++
                            netCap = maxOf(1, n / 2)
                            Journal.w(
                                "OCR",
                                "Sahifalar " + first + "-" + last + ": javob sahifa belgilariga mos kelmadi (finishReason=" + fin +
                                    "); oraliq " + netCap + " sahifagacha kichraytiriladi"
                            )
                            if (markerFails >= 4) {
                                Journal.w("OCR", "Butun fayl usuli sahifalarni ajrata olmadi: sahifa-rasm usuliga o'tildi")
                                return false
                            }
                        } else {
                            markerFails = 0
                            val cleaned = ArrayList<Pair<Int, String>>()
                            var withText = 0
                            for (j in 0 until take) {
                                val pg = batch[j]
                                val t = TextTools.cleanOcrPage(sp.pages[pg] ?: "")
                                cleaned.add(Pair(pg, t))
                                if (t.isNotBlank()) withText++
                            }
                            var chars = 0
                            var accepted = 0
                            for (pr in cleaned) {
                                val pg = pr.first
                                val t = pr.second
                                if (t.isBlank() && withText > 0) {
                                    // Boshqa sahifalarda matn bor, bu esa bo'sh: model sahifani tashlab ketgan bo'lishi mumkin — alohida qayta tekshiriladi.
                                    single.add(pg)
                                } else {
                                    cache.putText(pg, pg, t)
                                    chars += t.length
                                    accepted++
                                }
                            }
                            if (accepted > 0 && chars > 0) {
                                charsPerPage = (charsPerPage + chars.toDouble() / accepted.toDouble()) / 2.0
                                if (charsPerPage < 400.0) charsPerPage = 400.0
                                if (charsPerPage > 9000.0) charsPerPage = 9000.0
                            }
                            if (!complete) {
                                // Chiqish chegarasiga yetdi: baho juda xushchaqchaq edi, keyingi oraliqlar kichikroq bo'ladi.
                                charsPerPage = minOf(9000.0, charsPerPage * 1.4)
                                netCap = maxOf(1, n / 2)
                                Journal.w(
                                    "OCR",
                                    "Sahifalar " + first + "-" + last + ": javob chegaraga yetdi (finishReason=" + fin + "), " +
                                        take + " sahifa olindi; oraliq " + netCap + " sahifagacha kichraytiriladi"
                                )
                            } else {
                                netCap = minOf(hardMax, maxOf(netCap, 1) * 2)
                            }
                            badStreak = 0
                        }
                    }
                }
            }
        }

        if (doneCount() == 0 && cache.bads().isNotEmpty()) throw stopWith(JobState.PAUSED, tok(R.string.msg_ocr_none_read))
        Journal.i("OCR", "Butun fayl usuli tugadi: o'qilgan sahifalar " + doneCount() + " / " + need.size)
        return true
    }

    /** Bitta OCR soʻrovi: kalit tanlash, limit/tarmoq kutish, qayta urinishlar (TTS dagi synth bilan bir xil qoidalar). */
    private fun ocrCall(
        id: String,
        pool: KeyPool,
        pages: List<Int>,
        lastCall: HashMap<String, Long>,
        status: String,
        doCall: (KeyPool.Slot) -> Gemini.Result
    ): OcrOut {
        var netFails = 0
        var serverFails = 0
        var badFails = 0
        while (true) {
            if (host.stopRequested() || JobStore.get(id) == null) return OcrOut.Stop(JobState.PAUSED, USER_PAUSE)
            if (!VUtil.isOnline(ctx)) {
                waitStatus(id, tok(R.string.msg_wait_internet))
                sleepMs(8000L)
                continue
            }
            pool.reload()
            val pick = pool.pick(System.currentTimeMillis())
            if (pick is KeyPool.Pick.None) {
                if (pool.modelCount() == 0) return OcrOut.Stop(JobState.FAILED, tok(R.string.msg_ocr_no_model))
                if (pool.allKeysDead()) return OcrOut.Stop(JobState.FAILED, tok(R.string.msg_all_keys_dead, pool.limitMessage()))
                return OcrOut.Stop(JobState.LIMIT, tok(R.string.msg_text, pool.limitMessage()))
            }
            if (pick is KeyPool.Pick.Wait) {
                waitStatus(id, tok(R.string.msg_wait_limit, pick.ms / 1000L + 1L))
                sleepMs(minOf(pick.ms + 500L, 20000L))
                continue
            }
            val slot = (pick as KeyPool.Pick.Use).slot
            // Bir kalit/model uchun ketma-ket soʻrovlar orasida kamida OCR_GAP_MS tanaffus (daqiqalik limitga urilmaslik uchun).
            val sid = slot.keySlot.toString() + "|" + slot.model
            val gap = OCR_GAP_MS - (System.currentTimeMillis() - (lastCall[sid] ?: 0L))
            if (gap > 0L) sleepMs(gap)
            if (host.stopRequested()) return OcrOut.Stop(JobState.PAUSED, USER_PAUSE)
            setPreparing(id, status)
            val r = doCall(slot)
            lastCall[sid] = System.currentTimeMillis()
            when (r.kind) {
                Gemini.Kind.OK -> {
                    pool.success(slot)
                    return OcrOut.Text(r.text ?: "", r.finish ?: "")
                }
                Gemini.Kind.CANCELLED -> return OcrOut.Stop(JobState.PAUSED, USER_PAUSE)
                Gemini.Kind.QUOTA_DAY -> {
                    pool.exhaust(slot)
                    Journal.w("Limit", "OCR: kalit " + slot.keySlot + " / " + slot.model + ": kunlik limit tugadi (HTTP " + r.code + "). " + r.message)
                }
                Gemini.Kind.QUOTA_MIN -> {
                    pool.cooldown(slot, r.retryMs)
                    Journal.w(
                        "Limit",
                        "OCR: kalit " + slot.keySlot + " / " + slot.model + ": daqiqalik limit, " + (r.retryMs / 1000L) + " s kutiladi. " + r.message
                    )
                }
                Gemini.Kind.AUTH -> handleAuth(pool, slot, r)
                Gemini.Kind.REGION -> {
                    Journal.e("Region", "HTTP " + r.code + ": " + r.message)
                    return OcrOut.Stop(JobState.FAILED, tok(R.string.msg_region, r.message))
                }
                Gemini.Kind.MODEL -> handleOcrModel(pool, slot, r)
                Gemini.Kind.SERVER -> {
                    serverFails++
                    Journal.w("Tarmoq", "OCR server xatosi HTTP " + r.code + ": " + r.message + " (urinish " + serverFails + ")")
                    if (r.code == 503) {
                        // 503 "high demand": model serverda band. Bu sahifaning aybi emas — sahifa "oʻqilmadi" deb belgilanmaydi.
                        // Model hamma kalitlarda dam oladi, navbat zaxira modelga oʻtadi; hammasi band boʻlsa — kutiladi.
                        val w = pool.overload(slot.model)
                        Journal.w("Model", "OCR modeli band: " + slot.model + " — " + (w / 1000L) + " s dam oladi, zaxira modelga oʻtiladi")
                        if (serverFails >= 6) return OcrOut.Stop(JobState.PAUSED, tok(R.string.msg_ocr_server_busy, r.code))
                    } else {
                        if (serverFails >= 3) return OcrOut.Bad("server xatosi " + r.code)
                        sleepMs(4000L * serverFails)
                    }
                }
                Gemini.Kind.NETWORK -> {
                    netFails++
                    Journal.w("Tarmoq", "OCR tarmoq xatosi: " + r.message + " (urinish " + netFails + "/6)")
                    // Server javob bermadi (oʻqish muddati tugadi): model band — u dam oladi, navbat zaxira modelga oʻtadi.
                    val lm = r.message.lowercase()
                    if (lm.startsWith("sockettimeoutexception") && !lm.contains("connect")) {
                        val w = pool.overload(slot.model)
                        Journal.w("Model", "OCR modeli javob bermadi: " + slot.model + " — " + (w / 1000L) + " s dam oladi, zaxira modelga oʻtiladi")
                    }
                    // Bir nechta sahifa = katta soʻrov (bir necha MB). Ketma-ket 2 marta tarmoq xatosi boʻlsa — guruh kichraytiriladi.
                    if (netFails >= 2 && pages.size > 1) return OcrOut.Smaller(r.message)
                    if (netFails >= 6) return OcrOut.Stop(JobState.PAUSED, tok(R.string.msg_net_repeat, r.message))
                    waitStatus(id, tok(R.string.msg_net_retry, netFails))
                    sleepMs(minOf(60000L, 5000L * netFails))
                }
                Gemini.Kind.NO_AUDIO, Gemini.Kind.BAD_CHUNK -> {
                    badFails++
                    Journal.w("Gemini", "OCR natijasi yaroqsiz: " + r.message + " (urinish " + badFails + ")")
                    if (badFails >= 2) return OcrOut.Bad(r.message)
                    sleepMs(1500L)
                }
            }
        }
    }

    private fun handleOcrModel(pool: KeyPool, slot: KeyPool.Slot, r: Gemini.Result) {
        Journal.e("Model", "OCR modeli topilmadi yoki ishlamayapti: " + slot.model + " (HTTP " + r.code + "). " + r.message)
        pool.deadModel(slot.model)
        val alt = Gemini.findVisionModel(slot.key, pool.deadModels())
        if (alt != null) {
            Journal.i("Model", "Muqobil OCR modeli topildi: " + alt)
            if (slot.model == VPrefs.ocrModel(ctx)) VPrefs.setOcrModel(ctx, alt) else VPrefs.setOcrBackup(ctx, alt)
        } else {
            Journal.w("Model", "Muqobil OCR modeli topilmadi")
        }
    }

    // ---------------------------------------------------------------- imlo tuzatish (kitob mazmunidan chiqmasdan)

    /** Bitta bo'lakni AI orqali imlo/OCR xatolaridan tozalatadi. Xato bo'lsa istisno tashlaydi; natija tekshiruvdan o'tadi. */
    private fun fixSpelling(title: String, prevTail: String, raw: String): SpellOut {
        val system = SPELL_SYSTEM.replace("%TITLE%", title.replace('"', ' '))
        val user = StringBuilder()
        if (prevTail.isNotBlank()) {
            user.append("CONTEXT (end of the previous passage; do NOT output it):\n").append(prevTail.trim()).append("\n\n")
        }
        user.append("PASSAGE TO CORRECT:\n").append(raw.trim())
        val maxTokens = (raw.length / 2 + 600).coerceIn(1024, 8192)
        val reply = runBlocking { Ai.proofread(system, user.toString(), maxTokens) }
        return judgeSpelling(raw, reply)
    }

    /**
     * Model javobini tekshiradi: matn qisqarmagan/uzaymagan, so'zlarning ko'pi o'zgarmagan bo'lishi shart.
     * Aks holda (qayta yozish, tushirib qoldirish, izoh qo'shish) — asl matn qoldiriladi.
     */
    private fun judgeSpelling(raw: String, reply: String): SpellOut {
        val base = raw.trim()
        var f = reply.trim()
        if (f.startsWith("```")) {
            f = f.substringAfter('\n', "")
            val k = f.lastIndexOf("```")
            if (k >= 0) f = f.substring(0, k)
            f = f.trim()
        }
        val nl = f.indexOf('\n')
        if (nl > 0) {
            val firstLine = f.substring(0, nl).trim()
            val baseFirst = (base.lineSequence().firstOrNull() ?: "").trim()
            if (firstLine.endsWith(":") && !baseFirst.endsWith(":")) f = f.substring(nl + 1).trim()
        }
        if (f.isEmpty()) {
            Journal.w("Imlo", "Model boʻsh javob berdi: asl matn qoldirildi")
            return SpellOut(raw, false, true)
        }
        val ratio = f.length.toDouble() / maxOf(base.length, 1).toDouble()
        if (ratio < 0.85 || ratio > 1.15) {
            Journal.w("Imlo", "Rad etildi (uzunlik nisbati " + VUtil.fmt1(ratio) + "): asl matn qoldirildi")
            return SpellOut(raw, false, true)
        }
        val diff = wordDiffRatio(base, f)
        if (diff > 0.30) {
            Journal.w("Imlo", "Rad etildi (soʻzlarning " + (diff * 100.0).toInt() + "% i oʻzgargan): asl matn qoldirildi")
            return SpellOut(raw, false, true)
        }
        if (f == base) return SpellOut(raw, false, false)
        return SpellOut(f, true, false)
    }

    private fun wordTokens(s: String): List<String> {
        val out = ArrayList<String>()
        for (w in s.split(Regex("\\s+"))) {
            val n = w.trim { !it.isLetterOrDigit() }.lowercase(Locale.ROOT)
            if (n.isNotEmpty()) out.add(n)
        }
        return out
    }

    /** Ikki matn orasidagi so'zlar bo'yicha farq (0..1): o'zgargan/qo'shilgan/tushgan so'zlar ulushi. */
    private fun wordDiffRatio(a: String, b: String): Double {
        val x = wordTokens(a)
        val y = wordTokens(b)
        if (x.isEmpty() || y.isEmpty()) return 1.0
        var prev = IntArray(y.size + 1) { it }
        var cur = IntArray(y.size + 1)
        for (i in 1..x.size) {
            cur[0] = i
            for (j in 1..y.size) {
                val cost = if (x[i - 1] == y[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[y.size].toDouble() / maxOf(x.size, y.size).toDouble()
    }

    /** Ilovaga qaytganda (pauza/uzilish) tasdiqlanmagan quyruqni kesadi: corrected.dat job.corrBytes ga mos bo'ladi. */
    private fun prepareCorrected(id: String, job: VoiceJob) {
        try {
            val f = JobStore.corrected(id)
            if (!f.exists()) {
                if (job.corrBytes != 0L) JobStore.update(id) { it.corrBytes = 0L }
                return
            }
            if (f.length() > job.corrBytes) {
                RandomAccessFile(f, "rw").use { it.setLength(job.corrBytes) }
            } else if (f.length() < job.corrBytes) {
                val len = f.length()
                JobStore.update(id) { it.corrBytes = len }
            }
        } catch (t: Throwable) {
            Journal.w("Imlo", "Tuzatilgan matn faylini tayyorlab boʻlmadi", t)
        }
    }

    /** Audiolashtirilgan (tuzatilgan) bo'lakni corrected.dat oxiriga yozadi; yangi uzunlikni qaytaradi. */
    private fun appendCorrected(id: String, voiced: String, gap: String, prevLen: Long): Long {
        try {
            val f = JobStore.corrected(id)
            val nlCount = gap.count { it == '\n' }
            val sep = if (prevLen <= 0L) "" else if (nlCount >= 2) "\n\n" else if (nlCount == 1) "\n" else " "
            val bytes = (sep + voiced.trim()).toByteArray(Charsets.UTF_8)
            FileOutputStream(f, true).use { o ->
                o.write(bytes)
                o.flush()
                o.fd.sync()
            }
            return f.length()
        } catch (t: Throwable) {
            Journal.w("Imlo", "Tuzatilgan matnni yozib boʻlmadi", t)
            return prevLen
        }
    }

    private fun implausible(speech: String, secs: Double): Boolean {
        val eff = TextTools.effectiveLen(speech)
        val cps = eff / maxOf(secs, 0.01)
        return cps > 40.0 || cps < 3.0
    }

    private fun recordSkipped(id: String, raw: String, marks: List<Mark>, offset: Int, why: String) {
        val label = txt(TextTools.labelAt(marks, offset))
        try {
            val head = (if (label.isNotEmpty()) ctx.getString(R.string.skip_line_label, label, offset, why) else ctx.getString(R.string.skip_line, offset, why)) + "\n"
            JobStore.skipped(id).appendText(head + raw.trim() + "\n\n", Charsets.UTF_8)
        } catch (t: Throwable) {
            Journal.e("Job", "Oʻtkazilgan qismni faylga yozib boʻlmadi", t)
        }
        Journal.e(
            "Job",
            "Qism audiolashtirilmadi va oʻtkazib yuborildi (belgi " + offset + "): " + why + " | matn: " + VUtil.snippet(raw, 80)
        )
    }

    // ---------------------------------------------------------------- Gemini soʻrovi, kalit almashtirish, qayta urinishlar

    private fun synth(id: String, pool: KeyPool, cfg: Cfg, speech: String): Out {
        var netFails = 0
        var serverFails = 0
        var badFails = 0
        while (true) {
            if (host.stopRequested() || JobStore.get(id) == null) return Out.Stop(JobState.PAUSED, USER_PAUSE)
            if (!VUtil.isOnline(ctx)) {
                waitStatus(id, tok(R.string.msg_wait_internet))
                sleepMs(8000L)
                continue
            }
            pool.reload()
            val pick = pool.pick(System.currentTimeMillis())
            if (pick is KeyPool.Pick.None) {
                if (pool.modelCount() == 0) {
                    return Out.Stop(JobState.FAILED, tok(R.string.msg_no_model))
                }
                if (pool.allKeysDead()) {
                    return Out.Stop(JobState.FAILED, tok(R.string.msg_all_keys_dead, pool.limitMessage()))
                }
                return Out.Stop(JobState.LIMIT, tok(R.string.msg_text, pool.limitMessage()))
            }
            if (pick is KeyPool.Pick.Wait) {
                waitStatus(id, tok(R.string.msg_wait_limit, pick.ms / 1000L + 1L))
                sleepMs(minOf(pick.ms + 500L, 20000L))
                continue
            }
            val slot = (pick as KeyPool.Pick.Use).slot
            setRunning(id, tok(R.string.msg_synth_running, slot.keySlot))
            val r = Gemini.synthesize(slot.key, slot.model, cfg.voice, speech, cfg.style) { host.stopRequested() }
            when (r.kind) {
                Gemini.Kind.OK -> {
                    pool.success(slot)
                    val pcm = r.pcm
                    if (pcm != null) {
                        return Out.Audio(pcm, null, r.rate, pcm.size * 1000L / (2L * r.rate), "kalit " + slot.keySlot + "; " + slot.model)
                    }
                    badFails++
                    if (badFails >= 2) return Out.Bad("boʻsh audio")
                }
                Gemini.Kind.CANCELLED -> return Out.Stop(JobState.PAUSED, USER_PAUSE)
                Gemini.Kind.QUOTA_DAY -> {
                    pool.exhaust(slot)
                    Journal.w("Limit", "Kalit " + slot.keySlot + " / " + slot.model + ": kunlik limit tugadi (HTTP " + r.code + "). " + r.message)
                }
                Gemini.Kind.QUOTA_MIN -> {
                    pool.cooldown(slot, r.retryMs)
                    Journal.w(
                        "Limit",
                        "Kalit " + slot.keySlot + " / " + slot.model + ": daqiqalik limit, " + (r.retryMs / 1000L) + " s kutiladi. " + r.message
                    )
                }
                Gemini.Kind.AUTH -> handleAuth(pool, slot, r)
                Gemini.Kind.REGION -> {
                    Journal.e("Region", "HTTP " + r.code + ": " + r.message)
                    return Out.Stop(JobState.FAILED, tok(R.string.msg_region, r.message))
                }
                Gemini.Kind.MODEL -> handleModel(pool, slot, r)
                Gemini.Kind.SERVER -> {
                    serverFails++
                    Journal.w("Tarmoq", "Server xatosi HTTP " + r.code + ": " + r.message + " (urinish " + serverFails + ")")
                    if (serverFails >= 3) return Out.Bad("server xatosi " + r.code)
                    sleepMs(4000L * serverFails)
                }
                Gemini.Kind.NETWORK -> {
                    netFails++
                    Journal.w("Tarmoq", "Tarmoq xatosi: " + r.message + " (urinish " + netFails + "/6)")
                    if (netFails >= 6) {
                        return Out.Stop(JobState.PAUSED, tok(R.string.msg_net_repeat, r.message))
                    }
                    waitStatus(id, tok(R.string.msg_net_retry, netFails))
                    sleepMs(minOf(60000L, 5000L * netFails))
                }
                Gemini.Kind.NO_AUDIO, Gemini.Kind.BAD_CHUNK -> {
                    badFails++
                    Journal.w("Gemini", "Natija yaroqsiz: " + r.message + " (urinish " + badFails + ")")
                    if (badFails >= 2) return Out.Bad(r.message)
                    sleepMs(1500L)
                }
            }
        }
    }

    private fun handleAuth(pool: KeyPool, slot: KeyPool.Slot, r: Gemini.Result) {
        val chk = Gemini.checkKey(slot.key)
        if (chk.ok) {
            pool.deadSlot(slot)
            Journal.e("Kalit", "Kalit " + slot.keySlot + ": " + slot.model + " modeliga ruxsat yoʻq (HTTP " + r.code + "): " + r.message)
        } else if (chk.code == 0) {
            Journal.w("Kalit", "Kalit " + slot.keySlot + " tekshirilmadi (tarmoq): " + chk.message)
            sleepMs(5000L)
        } else {
            pool.deadKey(slot)
            Journal.e("Kalit", "Kalit " + slot.keySlot + " yaroqsiz (HTTP " + chk.code + "): " + chk.message)
        }
    }

    private fun handleModel(pool: KeyPool, slot: KeyPool.Slot, r: Gemini.Result) {
        Journal.e("Model", "Model topilmadi yoki ishlamayapti: " + slot.model + " (HTTP " + r.code + "). " + r.message)
        pool.deadModel(slot.model)
        val alt = Gemini.findTtsModel(slot.key, pool.deadModels())
        if (alt != null) {
            Journal.i("Model", "Muqobil TTS modeli topildi: " + alt)
            if (slot.model == VPrefs.model(ctx)) VPrefs.setModel(ctx, alt) else VPrefs.setBackup(ctx, alt)
        } else {
            Journal.w("Model", "Muqobil TTS modeli topilmadi")
        }
    }

    // ---------------------------------------------------------------- boshqa ovozlar: Microsoft, ChatGPT, Grok, qurilma

    private val plainPools = HashMap<String, PlainKeys>()

    private fun plainPool(prov: String): PlainKeys {
        var p = plainPools[prov]
        if (p == null) {
            p = PlainKeys(prov)
            plainPools[prov] = p
        }
        return p
    }

    /** Gemini bo'lmagan ovoz dvigatellari bilan bitta bo'lakni ovozga aylantiradi (Gemini uchun — synth). */
    private fun synthOther(id: String, cfg: Cfg, speech: String): Out {
        if (cfg.engine == VEngine.MICROSOFT) return synthMicrosoft(id, cfg, speech)
        if (cfg.engine == VEngine.DEVICE) return synthDevice(id, cfg, speech)
        return synthCloud(id, cfg, speech)
    }

    /** Microsoft ovozi: kalitsiz, MP3 qaytadi (qayta kodlanmasdan faylga ulanadi). */
    private fun synthMicrosoft(id: String, cfg: Cfg, speech: String): Out {
        // Microsoft o'zbekcha ovozlari lotin yozuvini o'qiydi: kirill yozuvidagi matn lotinga o'giriladi.
        val text = if (cfg.voice.startsWith("uz-")) Uz.ovozgaTayyor(speech).first else speech
        val rate = EdgeTts.rateFor(cfg.rate)
        var netFails = 0
        var badFails = 0
        while (true) {
            if (host.stopRequested() || JobStore.get(id) == null) return Out.Stop(JobState.PAUSED, USER_PAUSE)
            if (!VUtil.isOnline(ctx)) {
                waitStatus(id, tok(R.string.msg_wait_internet))
                sleepMs(8000L)
                continue
            }
            setRunning(id, "Microsoft ovozi bilan o'qilmoqda")
            val r = EdgeTts.synth(text, cfg.voice, rate) { host.stopRequested() }
            when (r.kind) {
                Gemini.Kind.OK -> {
                    val b = r.bytes
                    val info = if (b != null) Mp3Util.analyze(b) else null
                    if (info != null && info.ms >= 300L) {
                        return Out.Audio(null, info.data, info.rate, info.ms, "Microsoft; " + cfg.voice)
                    }
                    badFails++
                    Journal.w("Microsoft", "Audio yaroqsiz yoki juda qisqa (urinish " + badFails + ")")
                    if (badFails >= 2) return Out.Bad("Microsoft ovozi yaroqsiz audio qaytardi")
                    sleepMs(1500L)
                }
                Gemini.Kind.CANCELLED -> return Out.Stop(JobState.PAUSED, USER_PAUSE)
                Gemini.Kind.NETWORK -> {
                    netFails++
                    Journal.w("Tarmoq", "Microsoft ovozi xatosi: " + r.message + " (urinish " + netFails + "/6)")
                    if (netFails >= 6) {
                        return Out.Stop(
                            JobState.PAUSED,
                            "Microsoft ovozi bilan aloqa o'rnatilmadi: " + r.message +
                                ". Birozdan keyin «Davom ettirish» ni bosing yoki Ovoz sozlamalarida boshqa ovoz tanlang."
                        )
                    }
                    waitStatus(id, tok(R.string.msg_net_retry, netFails))
                    sleepMs(minOf(60000L, 4000L * netFails))
                }
                else -> {
                    badFails++
                    Journal.w("Microsoft", "Natija yaroqsiz: " + r.message + " (urinish " + badFails + ")")
                    if (badFails >= 2) return Out.Bad(if (r.message.isEmpty()) "Microsoft ovozi javob bermadi" else r.message)
                    sleepMs(1500L)
                }
            }
        }
    }

    /** Qurilma ovozi: Android TextToSpeech, kalitsiz va internetsiz; PCM qaytadi. */
    private fun synthDevice(id: String, cfg: Cfg, speech: String): Out {
        var fails = 0
        while (true) {
            if (host.stopRequested() || JobStore.get(id) == null) return Out.Stop(JobState.PAUSED, USER_PAUSE)
            setRunning(id, "Qurilma ovozi bilan o'qilmoqda")
            val r = DeviceTts.synthesize(ctx, speech, cfg.rate) { host.stopRequested() }
            if (r.cancelled || host.stopRequested()) return Out.Stop(JobState.PAUSED, USER_PAUSE)
            if (r.fatal) return Out.Stop(JobState.FAILED, r.message)
            val pcm = r.pcm
            if (r.ok && pcm != null) {
                val ms = pcm.size * 1000L / (2L * r.rate)
                return Out.Audio(pcm, null, r.rate, ms, "Qurilma ovozi")
            }
            fails++
            Journal.w("Qurilma", "Ovoz yaratilmadi: " + r.message + " (urinish " + fails + ")")
            if (fails >= 2) return Out.Bad(if (r.message.isEmpty()) "Qurilma ovozi audio bermadi" else r.message)
            sleepMs(1000L)
        }
    }

    /** ChatGPT (OpenAI, PCM) va Grok (xAI, MP3) ovozi: kalitlar navbati, limit kutish va qayta urinish Gemini bilan bir xil qoidalarda. */
    private fun synthCloud(id: String, cfg: Cfg, speech: String): Out {
        val isOpenAi = cfg.engine == VEngine.OPENAI
        val prov = if (isOpenAi) Prov.OPENAI else Prov.XAI
        val label = VEngine.name(cfg.engine)
        val pool = plainPool(prov)
        val models: List<String> = if (isOpenAi) VEngine.openaiModelList() else listOf("")
        var modelIdx = 0
        var netFails = 0
        var serverFails = 0
        var badFails = 0
        while (true) {
            if (host.stopRequested() || JobStore.get(id) == null) return Out.Stop(JobState.PAUSED, USER_PAUSE)
            if (!VUtil.isOnline(ctx)) {
                waitStatus(id, tok(R.string.msg_wait_internet))
                sleepMs(8000L)
                continue
            }
            pool.reload()
            val pick = pool.pick(System.currentTimeMillis())
            if (pick is PlainKeys.Pick.None) {
                if (pool.keyCount() == 0) return Out.Stop(JobState.FAILED, VEngine.needKeyText(cfg.engine))
                if (pool.allDead()) {
                    return Out.Stop(
                        JobState.FAILED,
                        "Barcha " + label + " kalitlari yaroqsiz. Sozlamalar bo'limida kalitlarni tekshiring (" + pool.statusText() + ")."
                    )
                }
                return Out.Stop(
                    JobState.PAUSED,
                    label + " kalitlarining limiti yoki krediti tugadi (" + pool.statusText() +
                        "). Yangi kalit qo'shing yoki keyinroq «Davom ettirish» ni bosing."
                )
            }
            if (pick is PlainKeys.Pick.Wait) {
                waitStatus(id, tok(R.string.msg_wait_limit, pick.ms / 1000L + 1L))
                sleepMs(minOf(pick.ms + 500L, 20000L))
                continue
            }
            val slot = (pick as PlainKeys.Pick.Use).slot
            val model = models[if (modelIdx < models.size) modelIdx else models.size - 1]
            setRunning(id, label + " ovozi bilan o'qilmoqda (kalit " + slot.slot + ")")
            val r = if (isOpenAi) {
                CloudTts.openai(slot.key, model, cfg.voice, speech, cfg.rate, cfg.style) { host.stopRequested() }
            } else {
                CloudTts.xai(slot.key, cfg.voice, speech) { host.stopRequested() }
            }
            when (r.kind) {
                Gemini.Kind.OK -> {
                    pool.success(slot.slot)
                    val b = r.bytes
                    val who = label + "; kalit " + slot.slot + (if (model.isNotEmpty()) "; " + model else "")
                    if (b != null) {
                        if (r.mp3) {
                            val info = Mp3Util.analyze(b)
                            if (info != null && info.ms >= 300L) return Out.Audio(null, info.data, info.rate, info.ms, who)
                        } else {
                            val ms = b.size * 1000L / (2L * r.rate)
                            if (ms >= 300L) return Out.Audio(b, null, r.rate, ms, who)
                        }
                    }
                    badFails++
                    Journal.w(label, "Audio yaroqsiz yoki juda qisqa (urinish " + badFails + ")")
                    if (badFails >= 2) return Out.Bad("bo'sh yoki yaroqsiz audio")
                    sleepMs(1500L)
                }
                Gemini.Kind.CANCELLED -> return Out.Stop(JobState.PAUSED, USER_PAUSE)
                Gemini.Kind.QUOTA_DAY -> {
                    pool.markSpent(slot.slot)
                    Journal.w("Limit", label + ": kalit " + slot.slot + " limiti yoki krediti tugadi (HTTP " + r.code + "). " + r.message)
                }
                Gemini.Kind.QUOTA_MIN -> {
                    pool.cooldown(slot.slot, r.retryMs)
                    Journal.w("Limit", label + ": kalit " + slot.slot + " daqiqalik limitga yetdi, " + (r.retryMs / 1000L) + " s kutiladi. " + r.message)
                }
                Gemini.Kind.AUTH -> {
                    pool.markDead(slot.slot)
                    Journal.e("Kalit", label + ": kalit " + slot.slot + " yaroqsiz yoki ruxsat yo'q (HTTP " + r.code + "): " + r.message)
                }
                Gemini.Kind.REGION -> return Out.Stop(JobState.FAILED, label + " bu hududda ishlamaydi: " + r.message)
                Gemini.Kind.MODEL -> {
                    Journal.w("Model", label + ": model topilmadi yoki ishlamayapti: " + model + " (HTTP " + r.code + "). " + r.message)
                    if (modelIdx + 1 < models.size) {
                        modelIdx++
                    } else {
                        return Out.Bad("ovoz modeli topilmadi: " + r.message)
                    }
                }
                Gemini.Kind.SERVER -> {
                    serverFails++
                    Journal.w("Server", label + " xizmati xatosi (HTTP " + r.code + "), urinish " + serverFails + "/5: " + r.message)
                    if (serverFails >= 5) {
                        return Out.Stop(
                            JobState.PAUSED,
                            label + " xizmati hozir javob bermayapti (HTTP " + r.code + "). Birozdan keyin «Davom ettirish» ni bosing."
                        )
                    }
                    waitStatus(id, tok(R.string.msg_net_retry, serverFails))
                    sleepMs(minOf(60000L, 5000L * serverFails))
                }
                Gemini.Kind.NETWORK -> {
                    netFails++
                    Journal.w("Tarmoq", label + " ovozi xatosi: " + r.message + " (urinish " + netFails + "/6)")
                    if (netFails >= 6) {
                        return Out.Stop(
                            JobState.PAUSED,
                            label + " bilan aloqa o'rnatilmadi: " + r.message + ". Birozdan keyin «Davom ettirish» ni bosing."
                        )
                    }
                    waitStatus(id, tok(R.string.msg_net_retry, netFails))
                    sleepMs(minOf(60000L, 4000L * netFails))
                }
                Gemini.Kind.NO_AUDIO, Gemini.Kind.BAD_CHUNK -> {
                    badFails++
                    Journal.w(label, "Bo'lak qabul qilinmadi (HTTP " + r.code + "): " + r.message + " (urinish " + badFails + ")")
                    if (badFails >= 2) return Out.Bad(if (r.message.isEmpty()) "bo'lak qabul qilinmadi" else r.message)
                    sleepMs(1500L)
                }
            }
        }
    }

    // ---------------------------------------------------------------- audio fayl yozish (MP3, zaxira — WAV)

    private inner class SinkHolder(val id: String) {
        private var fos: FileOutputStream? = null

        /** Muvaffaqiyatli boʻlsa null, aks holda xato matni. */
        fun append(a: Out.Audio): String? {
            val m = a.mp3
            if (m != null) return appendMp3(m)
            val p = a.pcm ?: return tok(R.string.msg_audio_write_failed, "audio yo'q")
            return appendPcm(p, a.rate)
        }

        /** Tayyor MP3 kadrlarini (Microsoft, Grok) qayta kodlamasdan ish fayliga ulaydi. */
        private fun appendMp3(bytes: ByteArray): String? {
            val job = JobStore.get(id) ?: return tok(R.string.msg_job_deleted)
            if (job.format != "mp3") {
                return tok(R.string.msg_audio_write_failed, "ish WAV formatida boshlangan, MP3 bo'lakni ulab bo'lmaydi")
            }
            try {
                var o = fos
                if (o == null) {
                    o = FileOutputStream(JobStore.audio(job), true)
                    fos = o
                }
                o.write(bytes)
                o.flush()
                try {
                    o.fd.sync()
                } catch (t: Throwable) {
                }
                return null
            } catch (t: Throwable) {
                Journal.e("Mp3", "MP3 bo'lakni yozishda xato", t)
                return tok(R.string.msg_audio_write_failed, t.message ?: t.javaClass.simpleName)
            }
        }

        /** PCM ni MP3 ga kodlab (zaxira — WAV) faylga ulaydi. */
        private fun appendPcm(pcm: ByteArray, rate: Int): String? {
            var job = JobStore.get(id) ?: return tok(R.string.msg_job_deleted)
            if (job.format == "mp3") {
                try {
                    val bytes = Mp3Writer.encode(pcm, rate)
                    var o = fos
                    if (o == null) {
                        o = FileOutputStream(JobStore.audio(job), true)
                        fos = o
                    }
                    o.write(bytes)
                    o.flush()
                    try {
                        o.fd.sync()
                    } catch (t: Throwable) {
                    }
                    return null
                } catch (t: Throwable) {
                    Journal.e("Mp3", "MP3 kodlash/yozish xatosi", t)
                    if (job.audioBytes > 0L) {
                        return tok(R.string.msg_mp3_failed, t.javaClass.simpleName + ": " + (t.message ?: ""))
                    }
                    close()
                    try {
                        JobStore.audio(job).delete()
                    } catch (x: Throwable) {
                    }
                    JobStore.update(id) {
                        it.format = "wav"
                        it.message = tok(R.string.msg_mp3_fallback)
                    }
                    job = JobStore.get(id) ?: return tok(R.string.msg_job_deleted)
                }
            }
            try {
                WavWriter.append(JobStore.audio(job), pcm, rate)
                return null
            } catch (t: Throwable) {
                Journal.e("Wav", "Audio faylga yozib boʻlmadi", t)
                return tok(R.string.msg_audio_write_failed, t.message ?: t.javaClass.simpleName)
            }
        }

        fun close() {
            try {
                fos?.close()
            } catch (t: Throwable) {
            }
            fos = null
        }
    }

    // ---------------------------------------------------------------- holat, bildirishnomalar

    private fun fail(id: String, msg: String, t: Throwable?) {
        Journal.e("Job", txt(msg), t)
        finishStop(id, JobState.FAILED, msg, false)
    }

    private fun finishDone(id: String) {
        val j0 = JobStore.get(id) ?: return
        val dur = VUtil.fmtDuration(ctx, j0.audioMs)
        val msg = if (j0.skipped > 0) tok(R.string.msg_done_skipped, j0.skipped) else tok(R.string.msg_done)
        JobStore.update(id) {
            it.state = JobState.DONE
            it.offset = it.total
            it.message = msg
        }
        Journal.i("Job", "Tugadi: '" + j0.title + "', audio " + dur + (if (j0.skipped > 0) ", oʻtkazib yuborilgan qismlar: " + j0.skipped else ""))
        val text = if (j0.skipped > 0) {
            ctx.getString(R.string.notif_done_text_skipped, dur, j0.skipped)
        } else {
            ctx.getString(R.string.notif_done_text, dur)
        }
        host.result(ctx.getString(R.string.notif_done_title, j0.title), text, id)
    }

    private fun finishStop(id: String, state: JobState, msg: String, logIt: Boolean, ocr: Boolean = false) {
        val j0 = JobStore.get(id) ?: return
        // Limit tugagan bo'lsa: kalit/model yana ishlaydigan eng yaqin vaqt hisoblanadi — ish shunda o'zi davom etadi.
        var resumeAt = 0L
        if (state == JobState.LIMIT) {
            resumeAt = try {
                val kp = KeyPool(ctx, ocr)
                kp.reload()
                kp.nextAvailableAt(System.currentTimeMillis()) + 90000L
            } catch (t: Throwable) {
                Journal.w("Job", "Davom etish vaqtini hisoblab boʻlmadi", t)
                0L
            }
        }
        JobStore.update(id) {
            it.state = state
            it.message = msg
            it.resumeAt = resumeAt
        }
        val userPause = state == JobState.PAUSED && msg == USER_PAUSE
        if (userPause) {
            Journal.i("Job", "Toʻxtatildi: '" + j0.title + "' (" + j0.offset + "/" + j0.total + ")")
            return
        }
        if (logIt) {
            Journal.log(if (state == JobState.FAILED) "E" else "W", "Job", "'" + j0.title + "' — " + state.name + ": " + txt(msg))
        }
        val head = when (state) {
            JobState.LIMIT -> ctx.getString(R.string.notif_limit_title, j0.title)
            JobState.FAILED -> ctx.getString(R.string.notif_failed_title, j0.title)
            else -> ctx.getString(R.string.notif_stopped_title, j0.title)
        }
        host.result(head, txt(msg), id)
        if (resumeAt > 0L) host.limitReached(id, resumeAt)
    }

    private fun waitStatus(id: String, msg: String) {
        val j = JobStore.get(id) ?: return
        if (j.state == JobState.WAITING && j.message == msg) return
        JobStore.update(id) {
            it.state = JobState.WAITING
            it.message = msg
        }
        host.progress(j.title, msg)
    }

    private fun setRunning(id: String, msg: String) {
        val j = JobStore.get(id) ?: return
        if (j.state == JobState.RUNNING && j.message == msg) return
        JobStore.update(id) {
            it.state = JobState.RUNNING
            it.message = msg
        }
    }

    private fun sleepMs(ms: Long) {
        var left = ms
        while (left > 0L && !host.stopRequested()) {
            val s = if (left < 250L) left else 250L
            try {
                Thread.sleep(s)
            } catch (e: InterruptedException) {
                return
            }
            left -= s
        }
    }
}
