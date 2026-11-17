package uz.barchasibittada

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Skanerlangan PDF ni AI (Gemini Vision) bilan oʻqish: yordamchi sinflar.
 * Asosiy sikl TtsService.ocrRun() da (kalit almashtirish, limit, tarmoq kutish shu yerda ishlaydi).
 */

/**
 * OCR oxirigacha bajarilmay toʻxtadi (limit, pauza, tarmoq, xato). Oʻqilgan sahifalar keshda saqlanib qoladi.
 * msg — Msg belgisi. done/total — OCR qilinishi kerak boʻlgan sahifalardan nechtasi oʻqilgani.
 */
class OcrInterrupted(
    val state: JobState,
    val msg: String,
    var partial: Extracted? = null,
    val done: Int = 0,
    val total: Int = 0
) : Exception("OCR toʻxtadi")

/** Extractors ga beriladigan OCR imkoniyati: kesh va "sahifalarni oʻqib keshga yoz" funksiyasi. */
class OcrSupport(val cache: OcrCache, val recognize: (List<Int>, Int) -> Unit)

/**
 * OCR natijalari keshi: jobs/<id>/ocr/ papkasida har bir sahifa (yoki sahifalar oraligʻi) uchun alohida fayl.
 * pXXXXX-YYYYY.txt — matn (boʻsh boʻlishi mumkin = boʻsh sahifa), pXXXXX-YYYYY.bad — oʻqib boʻlmadi (sabab ichida).
 * Ilova oʻchib qolsa yoki limit tugasa, qayta ishga tushganda tayyor sahifalar qayta oʻqilmaydi (limit tejaladi).
 */
class OcrCache(private val dir: File) {

    class Entry(val from: Int, val to: Int, val bad: Boolean, val file: File)

    class Piece(val from: Int, val to: Int, val text: String)

    private val nameRe = Regex("p(\\d{5})-(\\d{5})\\.(txt|bad)")

    private fun fname(from: Int, to: Int, bad: Boolean): String =
        String.format(Locale.US, "p%05d-%05d.%s", from, to, if (bad) "bad" else "txt")

    fun entries(): List<Entry> {
        val res = ArrayList<Entry>()
        val files = dir.listFiles() ?: return res
        for (f in files) {
            val m = nameRe.matchEntire(f.name) ?: continue
            val from = m.groupValues[1].toIntOrNull() ?: continue
            val to = m.groupValues[2].toIntOrNull() ?: continue
            res.add(Entry(from, to, m.groupValues[3] == "bad", f))
        }
        res.sortBy { it.from }
        return res
    }

    /** Keshda bor sahifalar. includeBad=false boʻlsa — faqat muvaffaqiyatli oʻqilganlar. */
    fun donePages(includeBad: Boolean): HashSet<Int> {
        val s = HashSet<Int>()
        for (e in entries()) {
            if (e.bad && !includeBad) continue
            for (p in e.from..e.to) s.add(p)
        }
        return s
    }

    fun putText(from: Int, to: Int, text: String) {
        write(fname(from, to, false), text)
    }

    fun putBad(page: Int, reason: String) {
        write(fname(page, page, true), reason)
    }

    /** Oldingi urinishda oʻqilmagan sahifalar yangi urinishda qayta sinab koʻriladi. */
    fun clearBad() {
        for (e in entries()) {
            if (e.bad) {
                try {
                    e.file.delete()
                } catch (t: Throwable) {
                }
            }
        }
    }

    fun pieces(): List<Piece> {
        val res = ArrayList<Piece>()
        for (e in entries()) {
            if (e.bad) continue
            var t: String? = null
            try {
                t = e.file.readText(Charsets.UTF_8)
            } catch (x: Throwable) {
                Journal.w("OCR", "Kesh fayli oʻqilmadi: " + e.file.name, x)
            }
            if (t == null) continue
            res.add(Piece(e.from, e.to, t))
        }
        return res
    }

    /** Oʻqib boʻlmagan sahifalar va sabablari. */
    fun bads(): List<Pair<Int, String>> {
        val res = ArrayList<Pair<Int, String>>()
        for (e in entries()) {
            if (!e.bad) continue
            var why = ""
            try {
                why = e.file.readText(Charsets.UTF_8)
            } catch (x: Throwable) {
            }
            res.add(Pair(e.from, why))
        }
        return res
    }

    fun clear() {
        try {
            dir.deleteRecursively()
        } catch (t: Throwable) {
            Journal.w("OCR", "OCR keshini oʻchirib boʻlmadi", t)
        }
    }

    private fun write(name: String, content: String) {
        dir.mkdirs()
        val tmp = File(dir, name + ".tmp")
        val dst = File(dir, name)
        tmp.writeText(content, Charsets.UTF_8)
        if (!tmp.renameTo(dst)) {
            dst.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
    }
}

/** Gemini javobini sahifalarga ajratish: har bir sahifa matni oldidan <<<PAGE n>>> belgisi turadi. */
object OcrParse {
    private val MARK = Regex("<<<\\s*PAGE\\s*(\\d+)\\s*>>>", RegexOption.IGNORE_CASE)

    fun marker(page: Int): String = "<<<PAGE " + page + ">>>"

    fun stripMarkers(raw: String): String = MARK.replace(raw, "\n")

    /** splitPrefix natijasi: matched — belgilari kutilgan tartibda kelgan sahifalar soni, pages — ularning matni. */
    class PrefixSplit(val pages: LinkedHashMap<Int, String>, val matched: Int)

    /**
     * Butun PDF rejimi: javobdagi belgilar kutilgan sahifalar tartibida qayerigacha mos kelganini topadi.
     * Javob oxiri kesilgan yoki model bir sahifani tashlab ketgan bo'lsa ham, ishonchli boshlang'ich sahifalar saqlanadi.
     */
    fun splitPrefix(raw: String, expected: List<Int>): PrefixSplit {
        val ms = MARK.findAll(raw).toList()
        val res = LinkedHashMap<Int, String>()
        var i = 0
        while (i < ms.size && i < expected.size) {
            val n = ms[i].groupValues[1].toIntOrNull() ?: break
            if (n != expected[i]) break
            val st = ms[i].range.last + 1
            val en = if (i + 1 < ms.size) ms[i + 1].range.first else raw.length
            res[expected[i]] = raw.substring(st, en).trim()
            i++
        }
        return PrefixSplit(res, i)
    }

    /**
     * Belgilar kutilgan sahifalar bilan aynan (soni va tartibi) mos kelsa — sahifa -> matn.
     * Mos kelmasa null: sahifalar chalkashib ketgan boʻlishi mumkin, matn jim yoʻqolmasligi uchun qayta soʻraladi.
     */
    fun split(raw: String, expected: List<Int>): Map<Int, String>? {
        val ms = MARK.findAll(raw).toList()
        if (ms.size != expected.size) return null
        for (i in expected.indices) {
            val n = ms[i].groupValues[1].toIntOrNull() ?: return null
            if (n != expected[i]) return null
        }
        val res = LinkedHashMap<Int, String>()
        for (i in expected.indices) {
            val st = ms[i].range.last + 1
            val en = if (i + 1 < ms.size) ms[i + 1].range.first else raw.length
            res[expected[i]] = raw.substring(st, en).trim()
        }
        return res
    }
}

/** Gemini ga yuboriladigan koʻrsatmalar (inglizcha — modellar ularga eng aniq amal qiladi). */
object OcrPrompt {
    val SYSTEM: String = listOf(
        "You are a professional OCR engine for scanned book pages.",
        "The pages are mostly in Uzbek (Latin, Cyrillic or Arabic script, including older orthographies) but may contain other languages.",
        "Rules:",
        "1. Transcribe all running text exactly as printed: keep the original script, spelling, punctuation, apostrophes and diacritics. " +
            "Never translate, transliterate, correct, modernize, summarize or explain anything.",
        "2. Separate paragraphs with one blank line. Keep lines of poetry on separate lines. Join words that are hyphenated across line breaks.",
        "3. Leave out page numbers, running headers and footers, and e-library watermarks such as www.ziyouz.com.",
        "4. Put footnotes after the text of their page. Read multi-column pages column by column.",
        "5. Do not use Markdown and do not add comments, notes or introductions.",
        "6. If a page has no text, output nothing for that page."
    ).joinToString("\n")

    /** Butun PDF fayli ilova qilingan holda: first..last sahifalarni o'qish ko'rsatmasi. */
    fun fileTask(first: Int, last: Int, total: Int): String =
        "The attached PDF file has " + total + " pages. Transcribe pages " + first + " to " + last + " inclusive. " +
            "Count pages by their position in the PDF file (the first page of the file is page 1), not by printed page numbers. " +
            "Begin each page's transcription with exactly its own marker line on a line by itself: <<<PAGE n>>>, " +
            "where n is that page's position in the PDF file, even if the page has no text. " +
            "Output every page of the range in order; never skip, merge, shorten or summarize any page, " +
            "and do not stop before page " + last + "."

    fun task(count: Int): String =
        "Above are " + count + " page image(s), each preceded by a marker line such as <<<PAGE 5>>>. " +
            "Output the transcription of every page in the same order. " +
            "Begin each page's transcription with exactly its own marker line on a line by itself, even if the page has no text."
}

/** PDF sahifalarini JPEG rasmga aylantiradi (Android PdfRenderer). Bir vaqtda faqat bitta sahifa ochiq turadi. */
class PageRenderer private constructor(private val pfd: ParcelFileDescriptor, private val r: PdfRenderer) {

    companion object {
        /** Rasmning uzun tomoni (piksel). Katta sahifalarda xotira yetmasligi uchun cheklanadi. */
        const val MAX_SIDE = 2200
        private const val MAX_SCALE = 3.0f
        private const val JPEG_QUALITY = 85

        fun open(file: File): PageRenderer {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                return PageRenderer(pfd, PdfRenderer(pfd))
            } catch (t: Throwable) {
                try {
                    pfd.close()
                } catch (x: Throwable) {
                }
                throw t
            }
        }
    }

    val pageCount: Int
        get() = r.pageCount

    /** page — 1 dan boshlanadi. Xato boʻlsa istisno tashlaydi (OutOfMemoryError ham). */
    fun jpeg(page: Int, maxSide: Int): ByteArray {
        if (page < 1 || page > r.pageCount) throw IOException("Sahifa raqami notoʻgʻri: " + page)
        val pg = r.openPage(page - 1)
        var bmp: Bitmap? = null
        try {
            val w0 = maxOf(pg.width, 1)
            val h0 = maxOf(pg.height, 1)
            var scale = maxSide.toFloat() / maxOf(w0, h0).toFloat()
            if (scale > MAX_SCALE) scale = MAX_SCALE
            if (scale < 0.1f) scale = 0.1f
            val w = maxOf((w0 * scale).toInt(), 16)
            val h = maxOf((h0 * scale).toInt(), 16)
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp = b
            // PdfRenderer shaffof fonga chizadi; oq fon boʻlmasa JPEG qora chiqadi va OCR hech narsa koʻrmaydi.
            b.eraseColor(Color.WHITE)
            pg.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val bos = ByteArrayOutputStream(256 * 1024)
            if (!b.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)) {
                throw IOException("JPEG ga siqib boʻlmadi")
            }
            return bos.toByteArray()
        } finally {
            try {
                pg.close()
            } catch (t: Throwable) {
            }
            try {
                bmp?.recycle()
            } catch (t: Throwable) {
            }
        }
    }

    fun close() {
        try {
            r.close()
        } catch (t: Throwable) {
        }
        try {
            pfd.close()
        } catch (t: Throwable) {
        }
    }
}

/**
 * Gemini Files API ga yuklangan PDF nusxalari. Yuklangan fayl Google loyihasiga bog'liq, shuning uchun har bir kalit uchun alohida yuklanadi.
 * Ro'yxat jobs/<id>/ocr/gfiles.json da saqlanadi: ilova o'chib qolsa yoki limit tugasa, PDF qayta yuklanmaydi (Google faylni 48 soat saqlaydi).
 * O'qish tugagach cleanup() yuklangan fayllarni Google'dan o'chiradi.
 */
class RemotePdf(private val dir: File, pdf: File) {
    class Entry(val uri: String, val name: String, val at: Long)

    private val map = HashMap<String, Entry>()
    private val sig = pdf.length().toString()

    init {
        load()
    }

    private fun jsonFile(): File = File(dir, FILE)

    private fun fp(key: String): String = Integer.toHexString(key.hashCode()) + "-" + sig

    @Synchronized
    fun get(key: String): Entry? {
        val e = map[fp(key)] ?: return null
        if (System.currentTimeMillis() - e.at > MAX_AGE_MS) return null
        return e
    }

    @Synchronized
    fun put(key: String, e: Entry) {
        map[fp(key)] = e
        save()
    }

    @Synchronized
    fun drop(key: String) {
        map.remove(fp(key))
        save()
    }

    private fun load() {
        try {
            val f = jsonFile()
            if (!f.exists()) return
            val o = JSONObject(f.readText(Charsets.UTF_8))
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                val e = o.optJSONObject(k) ?: continue
                map[k] = Entry(e.optString("u", ""), e.optString("n", ""), e.optLong("t", 0L))
            }
        } catch (t: Throwable) {
            Journal.w("OCR", "Yuklangan fayllar ro'yxatini o'qib bo'lmadi", t)
        }
    }

    private fun save() {
        try {
            dir.mkdirs()
            val o = JSONObject()
            for ((k, e) in map) o.put(k, JSONObject().put("u", e.uri).put("n", e.name).put("t", e.at))
            jsonFile().writeText(o.toString(), Charsets.UTF_8)
        } catch (t: Throwable) {
            Journal.w("OCR", "Yuklangan fayllar ro'yxatini saqlab bo'lmadi", t)
        }
    }

    companion object {
        private const val FILE = "gfiles.json"
        private const val MAX_AGE_MS = 40L * 3600L * 1000L

        /** Ro'yxatdagi yuklangan fayllarni Google'dan o'chiradi (kalit bo'yicha topiladi) va ro'yxatni tozalaydi. Fon oqimida chaqiriladi. */
        fun cleanup(ctx: Context, dir: File) {
            val f = File(dir, FILE)
            if (!f.exists()) return
            try {
                val o = JSONObject(f.readText(Charsets.UTF_8))
                val keys = VPrefs.keys(ctx)
                val it = o.keys()
                while (it.hasNext()) {
                    val fpKey = it.next()
                    val e = o.optJSONObject(fpKey) ?: continue
                    val name = e.optString("n", "")
                    if (name.isEmpty()) continue
                    val hash = fpKey.substringBefore('-')
                    for (k in keys) {
                        if (Integer.toHexString(k.key.hashCode()) == hash) {
                            Gemini.deleteFile(k.key, name)
                            break
                        }
                    }
                }
            } catch (t: Throwable) {
                Journal.w("OCR", "Yuklangan fayllarni o'chirib bo'lmadi", t)
            }
            try {
                f.delete()
            } catch (t: Throwable) {
            }
        }
    }
}
