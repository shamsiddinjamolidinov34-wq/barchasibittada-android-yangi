package uz.barchasibittada

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class VoiceStart(val ok: Boolean, val message: String)

/** Qisqa o'qish natijasi: tayyor audio fayl, davomiyligi (soniya) va izoh (qisman bo'lsa sababi). */
class QuickVoice(val file: File, val seconds: Int, val note: String)

/**
 * Audiolashtirish ishlari bilan ishlash: yaratish, qisqa o'qish (suhbatdagi javob), TXT eksport va holat matni.
 * Ovoz tanlangan dvigatel orqali yaratiladi: Gemini (Audiokitob'dagi usul), Microsoft, ChatGPT, Grok yoki qurilma ovozi (VEngine).
 * Gemini, ChatGPT va Grok kalitlari — Sozlamalardagi kalitlar (har biri uchun kamida 5 ta, eng ko'pi Keys.MAX ta).
 */
object VoiceJobs {
    const val NEED_KEY = "Audiolashtirish uchun ovoz dvigatelini tanlang: Ovoz sozlamalarida kalitsiz Microsoft ovozi bor, " +
        "Gemini, ChatGPT va Grok ovozlari uchun Sozlamalarda API kalit qo'shing (har bir xizmat uchun kamida 5 ta kalit)."

    /** Audiolashtirish uchun ishlaydigan dvigatel bormi: tanlangan dvigatelning kaliti bo'lmasa, kalitsiz Microsoft ovozi ishlatiladi. */
    fun hasKeys(): Boolean = VEngine.ready(VEngine.effective())

    /** Sarlavha berilmagan bo'lsa — matnning birinchi qatoridan olinadi. */
    private fun titleFor(name: String, text: String): String {
        val n = name.trim()
        if (n.isNotEmpty() && n != "audio") return n
        val line = text.trimStart().lineSequence().firstOrNull() ?: ""
        val t = VUtil.snippet(line, 60).removeSuffix("…")
        return if (t.isEmpty()) "Matn" else t
    }

    /** Oddiy matndan ish yaratadi. Og'ir ish: fon oqimida chaqiring. */
    fun createFromText(name: String, rawText: String): VoiceStart {
        if (!hasKeys()) return VoiceStart(false, NEED_KEY)
        val cleaned = TextTools.clean(TextTools.cleanWatermarks(rawText))
        return create(name, cleaned, null)
    }

    /** Kitob sahifalaridan ish yaratadi: «qayergacha audiolashtirildi» sahifa raqami bilan ko'rsatiladi. */
    fun createFromPages(name: String, pages: List<String>): VoiceStart {
        if (!hasKeys()) return VoiceStart(false, NEED_KEY)
        val sb = StringBuilder()
        val marks = ArrayList<Mark>()
        var n = 0
        for (p in pages) {
            n += 1
            val c = TextTools.clean(TextTools.cleanWatermarks(p))
            if (c.isBlank()) continue
            if (sb.isNotEmpty()) sb.append("\n\n")
            marks.add(Mark(sb.length, n.toString() + "-sahifa"))
            sb.append(c)
        }
        return create(name, sb.toString(), marks, true)
    }

    private fun create(name: String, text: String, marks: List<Mark>?, scanned: Boolean = false): VoiceStart {
        if (text.isBlank()) return VoiceStart(false, "Audiolashtiriladigan matn bo'sh.")
        val title = titleFor(name, text)
        var jobId: String? = null
        try {
            val job = JobStore.create(title, "")
            jobId = job.id
            JobStore.source(job.id).writeText(text, Charsets.UTF_8)
            if (marks != null && marks.isNotEmpty()) TextTools.saveMarks(JobStore.marks(job.id), marks)
            JobStore.update(job.id) {
                it.total = text.length
                it.preview = VUtil.snippet(text, 160)
                it.scanned = scanned
            }
            Journal.i("Import", "Matn qabul qilindi: '" + title + "' (" + text.length + " belgi)")
            VoiceService.start(Store.app, job.id)
            return VoiceStart(
                true,
                "Audiolashtirish ishi navbatga qo'yildi: «" + title + "» (" + text.length + " belgi). " +
                    "Jarayonni «Ovoz» bo'limida kuzating. Ish fonda davom etadi."
            )
        } catch (t: Throwable) {
            Journal.e("Import", "Matnni qabul qilib bo'lmadi", t)
            val jid = jobId
            if (jid != null) JobStore.delete(jid)
            return VoiceStart(false, "Matnni qabul qilib bo'lmadi: " + Safe.mask(t.message ?: t.javaClass.simpleName).take(120))
        }
    }

    /**
     * Fayldan (PDF, Word, EPUB, FB2, TXT...) ish yaratadi. Matn olish — skanerlangan PDF ni AI bilan o'qish ham —
     * va imlo tuzatish fon xizmatida bajariladi; ilova o'chsa ham ish davom etadi. Og'ir ish: fon oqimida chaqiring.
     */
    fun createFromPicked(p: Picked): VoiceStart {
        if (!hasKeys()) return VoiceStart(false, NEED_KEY)
        val title = p.name.substringBeforeLast('.').trim().ifEmpty { "Kitob" }
        var jobId: String? = null
        try {
            val job = JobStore.create(title, p.name)
            jobId = job.id
            p.file.copyTo(JobStore.original(job.id), true)
            Journal.i("Import", "Fayl qabul qilindi: '" + p.name + "' (" + p.file.length() + " bayt)")
            VoiceService.start(Store.app, job.id)
            return VoiceStart(
                true,
                "Audiolashtirish ishi navbatga qo'yildi: «" + title + "». Matn fayldan olinadi (skanerlangan sahifalar AI bilan o'qiladi), " +
                    "so'ng imlosi tekshirilib audiolashtiriladi. Jarayonni «Ovoz» bo'limida kuzating. Ish fonda davom etadi."
            )
        } catch (t: Throwable) {
            Journal.e("Import", "Faylni qabul qilib bo'lmadi", t)
            val jid = jobId
            if (jid != null) JobStore.delete(jid)
            return VoiceStart(false, "Faylni qabul qilib bo'lmadi: " + Safe.mask(t.message ?: t.javaClass.simpleName).take(120))
        }
    }

    // ------------------------------------------------------------------
    // Avtomatik davom ettirish (limit qayta ochilganda / yangi kalit qo'shilganda)
    // ------------------------------------------------------------------

    /** Limit tugab to'xtagan ishlardan vaqti kelganlarini davom ettiradi. Signal, ilova ochilishi va boshqa joylardan chaqiriladi. */
    fun resumeDue(ctx: Context) {
        val now = System.currentTimeMillis()
        for (j in JobStore.all()) {
            if (j.state != JobState.LIMIT) continue
            if (j.resumeAt > now + 30000L) continue
            val ok = VoiceService.startQuiet(ctx, j.id, false)
            Journal.i("Resume", "Avtomatik davom: '" + j.title + "' " + (if (ok) "boshlandi" else "boshlab boʻlmadi"))
            if (ok) {
                JobStore.update(j.id) {
                    it.state = JobState.QUEUED
                    it.resumeAt = 0L
                }
            } else {
                VoiceResume.notifyReady(ctx, j.title)
            }
        }
        VoiceResume.reschedule(ctx)
    }

    /** Yangi Gemini kaliti qo'shilganda: kalit/limit tugagani uchun to'xtab qolgan ishlar o'zi davom etadi. */
    fun resumeAfterKeyAdded(ctx: Context) {
        for (j in JobStore.all()) {
            val keyProblem = j.state == JobState.FAILED &&
                (VMsg.isRes(ctx, j.message, R.string.msg_no_keys) || VMsg.isRes(ctx, j.message, R.string.msg_all_keys_dead))
            if (j.state != JobState.LIMIT && !keyProblem) continue
            val ok = VoiceService.startQuiet(ctx, j.id, false)
            Journal.i("Resume", "Yangi kalit qoʻshildi, davom: '" + j.title + "' " + (if (ok) "boshlandi" else "boshlab boʻlmadi"))
            if (ok) {
                JobStore.update(j.id) {
                    it.state = JobState.QUEUED
                    it.resumeAt = 0L
                }
            }
        }
        VoiceResume.reschedule(ctx)
    }

    // ------------------------------------------------------------------
    // Qisqa o'qish (suhbatdagi javob, sinov): ro'yxatga qo'shilmaydi
    // ------------------------------------------------------------------

    /**
     * Matnni ovozga aylantiradi va tayyor audio faylni qaytaradi. Audiolashtirish yadrosi (kalitlar navbati, qayta urinishlar,
     * xato jurnali) uzoq ishlardagi bilan bir xil. Korutina bekor qilinsa, so'rov uziladi.
     */
    suspend fun speak(text: String, title: String, onProgress: (Int) -> Unit): QuickVoice =
        suspendCancellableCoroutine { cont ->
            val flag = AtomicBoolean(false)
            cont.invokeOnCancellation {
                flag.set(true)
                VEngine.cancelAll()
            }
            Thread({
                try {
                    val r = speakBlocking(text, title, flag, onProgress)
                    if (cont.isActive) cont.resume(r)
                } catch (e: Throwable) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }, "voice-quick").start()
        }

    private fun speakBlocking(text: String, title: String, flag: AtomicBoolean, onProgress: (Int) -> Unit): QuickVoice {
        val ctx = Store.app
        if (!hasKeys()) throw ProviderError(NEED_KEY, 0, false, "kalit_yoq")
        val cleaned = TextTools.clean(TextTools.cleanWatermarks(text))
        if (cleaned.isBlank()) throw ProviderError("Ovozga aylantiriladigan matn bo'sh.")
        val id = JobStore.create(title, "", true).id
        try {
            JobStore.source(id).writeText(cleaned, Charsets.UTF_8)
            JobStore.update(id) { it.total = cleaned.length }
            val host = object : VoiceHost {
                override fun stopRequested(): Boolean = flag.get()

                override fun progress(title: String, text: String) {
                    val j = JobStore.get(id)
                    if (j != null && j.total > 0) onProgress((j.offset.toLong() * 100L / j.total).toInt())
                }

                override fun result(title: String, text: String, jobId: String) {
                }

                override fun refreshWake() {
                }

                override fun limitReached(jobId: String, resumeAt: Long) {
                }
            }
            VoiceCore(ctx, host).process(id)
            if (flag.get()) throw CancellationException("Bekor qilindi")
            val j = JobStore.get(id) ?: throw ProviderError("Ovoz yaratilmadi.")
            val src = JobStore.audio(j)
            val msg = VMsg.text(ctx, j.message)
            if (j.audioBytes <= 0L || !src.exists() || src.length() <= 0L) {
                throw ProviderError(if (msg.isNotBlank()) msg else "Ovoz yaratilmadi.")
            }
            val dst = File(Store.audioDir(), VUtil.safeName(title) + "-" + System.currentTimeMillis() + "." + j.format)
            src.copyTo(dst, true)
            val secs = (j.audioMs / 1000L).toInt()
            var note = ""
            if (j.state != JobState.DONE) {
                val pct = if (j.total > 0) (j.offset.toLong() * 100L / j.total).toInt() else 0
                note = "Ovoz qisman tayyor bo'ldi (" + pct + "%)." + (if (msg.isNotBlank()) " " + msg else "")
            } else if (j.skipped > 0) {
                note = "Ba'zi qismlar audiolashtirilmadi (" + j.skipped + " ta); sababi xato jurnalida."
            }
            return QuickVoice(dst, secs, note)
        } finally {
            JobStore.delete(id)
        }
    }

    // ------------------------------------------------------------------
    // TXT eksport (fon oqimida chaqiring)
    // ------------------------------------------------------------------

    /** Audiolashtirilmagan qolgan matn: faqat o'sha matnning o'zi, nomi = kitob nomi. */
    fun remainderFile(id: String): File? {
        try {
            val j = JobStore.get(id) ?: return null
            val text = JobStore.source(id).readText(Charsets.UTF_8)
            val rest = text.substring(minOf(j.offset, text.length)).trim()
            if (rest.isEmpty()) return null
            val f = JobStore.remainder(j)
            f.writeText(rest, Charsets.UTF_8)
            return f
        } catch (t: Throwable) {
            Journal.e("Export", "Qolgan matnni tayyorlab bo'lmadi", t)
            return null
        }
    }

    /** O'tkazib yuborilgan qismlar (sababi bilan) alohida TXT fayl sifatida. */
    fun skippedFile(id: String): File? {
        try {
            val j = JobStore.get(id) ?: return null
            val src = JobStore.skipped(id)
            if (!src.exists()) return null
            val f = JobStore.skippedExport(j, "o'tkazib yuborilgan qismlar")
            f.writeText(src.readText(Charsets.UTF_8), Charsets.UTF_8)
            return f
        } catch (t: Throwable) {
            Journal.e("Export", "O'tkazilgan qismlarni tayyorlab bo'lmadi", t)
            return null
        }
    }

    /** Skanerdan hozirgacha o'qilgan matn (OCR to'xtab qolgan bo'lsa) — alohida TXT fayl. */
    fun partialFile(id: String): File? {
        try {
            val j = JobStore.get(id) ?: return null
            val src = JobStore.partial(id)
            if (!src.exists() || src.length() == 0L) return null
            val f = JobStore.skippedExport(j, "hozirgacha o'qilgan matn")
            f.writeText(src.readText(Charsets.UTF_8), Charsets.UTF_8)
            return f
        } catch (t: Throwable) {
            Journal.e("Export", "Qisman matnni tayyorlab bo'lmadi", t)
            return null
        }
    }

    /** Imlosi tuzatilib audiolashtirilgan matn (tayyor bo'lgan qismi) — TXT fayl. */
    fun correctedFile(id: String): File? {
        try {
            val j = JobStore.get(id) ?: return null
            val src = JobStore.corrected(id)
            if (!src.exists() || src.length() == 0L) return null
            val f = JobStore.skippedExport(j, "imlosi tuzatilgan matn")
            f.writeText(src.readText(Charsets.UTF_8), Charsets.UTF_8)
            return f
        } catch (t: Throwable) {
            Journal.e("Export", "Tuzatilgan matnni tayyorlab bo'lmadi", t)
            return null
        }
    }

    fun mimeOf(j: VoiceJob): String = if (j.format == "wav") "audio/x-wav" else "audio/mpeg"

    // ------------------------------------------------------------------
    // Ish kartasi matni
    // ------------------------------------------------------------------

    fun statusText(j: VoiceJob): String {
        val ctx = Store.app
        val pct = if (j.total > 0) (j.offset.toLong() * 100L / j.total).toInt() else 0
        val sb = StringBuilder()
        val head = when (j.state) {
            JobState.NEW -> "Holat: boshlanmagan"
            JobState.PREPARING -> "Holat: matn tayyorlanmoqda"
            JobState.QUEUED -> "Holat: navbatda"
            JobState.RUNNING -> "Holat: audiolashtirilmoqda"
            JobState.WAITING -> "Holat: kutilmoqda"
            JobState.PAUSED -> "Holat: to'xtatilgan"
            JobState.LIMIT -> "Holat: limit tugadi"
            JobState.DONE -> "Holat: tayyor"
            JobState.FAILED -> "Holat: xatolik"
        }
        sb.append(head)
        if (j.state == JobState.DONE) sb.append(" — 100%")
        val withPct = j.total > 0 && (j.state == JobState.RUNNING || j.state == JobState.WAITING ||
            j.state == JobState.PAUSED || j.state == JobState.LIMIT || j.state == JobState.FAILED)
        if (withPct) sb.append(" — ").append(pct).append("%")
        val msg = VMsg.text(ctx, j.message)
        if (msg.isNotBlank()) sb.append("\n").append(msg)
        if (j.audioMs > 0L) {
            sb.append("\nAudio uzunligi: ").append(VUtil.fmtDuration(ctx, j.audioMs)).append(" (").append(j.format.uppercase()).append(")")
        }
        if (j.offset > 0 && j.state != JobState.DONE) {
            val place = VMsg.text(ctx, j.posLabel)
            sb.append("\nAudiolashtirilgan joy: ")
            if (place.isNotBlank()) sb.append(place).append(", ")
            sb.append(j.offset).append(" / ").append(j.total).append(" belgi")
        }
        if (j.lastSnippet.isNotBlank() && j.state != JobState.DONE) {
            sb.append("\nOxirgi audiolashtirilgan matn: «").append(j.lastSnippet).append("»")
        }
        if (j.skipped > 0) sb.append("\nO'tkazib yuborilgan qismlar: ").append(j.skipped).append(" ta")
        if (j.spellFixed > 0 || j.spellKept > 0) {
            sb.append("\nImlo tuzatish: ").append(j.spellFixed).append(" ta bo'lak tuzatildi")
            if (j.spellKept > 0) sb.append(", ").append(j.spellKept).append(" ta bo'lak xavfsizlik tekshiruvidan o'tmay asl holida qoldirildi")
        }
        if (j.state == JobState.LIMIT && j.resumeAt > 0L) {
            sb.append("\nAvtomatik davom etish: ").append(VoiceResume.clock(j.resumeAt)).append(" dan keyin (kalit/limit qayta ochilganda)")
        }
        val stopped = j.state == JobState.PAUSED || j.state == JobState.LIMIT || j.state == JobState.FAILED
        if (stopped && j.total > 0 && j.offset < j.total) {
            sb.append("\nAudiolashtirilmagan qism TXT sifatida tayyor (Amallar → Qolgan matnni saqlash).")
        }
        if (j.preview.isNotBlank() && j.offset == 0 && j.state != JobState.DONE) {
            sb.append("\nMatn boshi: «").append(j.preview).append("»")
        }
        return sb.toString()
    }
}
