package uz.barchasibittada

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.ZipFile

/** Matndagi joy belgisi: offset dan boshlab "sahifa 12 / 300" va hokazo. */
class Mark(val offset: Int, val label: String)

/**
 * warning — Msg belgisi (tilga bogʻliq xabar). unreadable — AI (OCR) bilan ham oʻqib boʻlmagan sahifalar va sabablari.
 */
class Extracted(
    val text: String,
    val marks: List<Mark>,
    val warning: String? = null,
    val unreadable: List<Pair<Int, String>> = emptyList()
)

/** Xato matni tilga bogʻliq: resurs id + argumentlar. token() — saqlash va koʻrsatish uchun Msg belgisi. */
class ExtractException(val resId: Int, vararg val args: Any?) : Exception("extract error #" + resId) {
    fun token(): String = VMsg.make(resId, *args)
}

/** Oddiy HTML/XHTML dan matn ajratish (EPUB boʻlimlari va HTML fayllar uchun). */
object HtmlText {
    private val COMMENT = Regex("(?s)<!--.*?-->")
    private val HIDDEN = Regex("(?is)<(script|style|head)\\b.*?</\\1\\s*>")
    private val BLOCK = Regex("(?i)</(p|div|h[1-6]|li|tr|blockquote|section|article|pre|table)\\s*>|<br\\s*/?>|<hr\\s*/?>")
    private val TAG = Regex("(?s)<[^>]*>")
    private val ENT = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);")
    private val NAMED = mapOf(
        "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "laquo" to "«", "raquo" to "»", "mdash" to "—", "ndash" to "–", "hellip" to "…",
        "rsquo" to "’", "lsquo" to "‘", "ldquo" to "“", "rdquo" to "”", "bdquo" to "„",
        "shy" to "", "zwnj" to "", "zwj" to ""
    )

    fun toPlain(html: String): String {
        var s = COMMENT.replace(html, " ")
        s = HIDDEN.replace(s, " ")
        s = BLOCK.replace(s, "\n")
        s = TAG.replace(s, "")
        return decode(s)
    }

    private fun cp(v: Int?): String {
        if (v == null || v <= 0 || v > 0x10FFFF) return ""
        return String(Character.toChars(v))
    }

    fun decode(s: String): String {
        if (s.indexOf('&') < 0) return s
        return ENT.replace(s) { m ->
            val g = m.groupValues[1]
            if (g.startsWith("#x") || g.startsWith("#X")) cp(g.substring(2).toIntOrNull(16))
            else if (g.startsWith("#")) cp(g.substring(1).toIntOrNull())
            else (NAMED[g] ?: m.value)
        }
    }
}

/** Fayldan matn ajratish: TXT, PDF, EPUB, DOCX, ODT, FB2 (+zip), HTML. */
object Extractors {

    /** ocr — skanerlangan PDF sahifalarini AI bilan oʻqish imkoniyati (null boʻlsa oʻqilmaydi). progress Msg belgisini oladi. */
    fun extract(ctx: Context, file: File, name: String, ocr: OcrSupport?, progress: (String) -> Unit): Extracted {
        val type = detect(file, name)
        Journal.i("Extract", "Fayl turi: " + type + ", hajmi: " + file.length() + " bayt, nomi: " + name)
        val r0: Extracted = when (type) {
            "pdf" -> pdf(ctx, file, ocr, progress)
            "epub" -> epub(file, progress)
            "docx" -> docx(file)
            "odt" -> odt(file)
            "fb2" -> {
                val ins = file.inputStream().buffered()
                try {
                    fb2(ins)
                } finally {
                    ins.close()
                }
            }
            "fb2zip" -> fb2zip(file)
            "html" -> Extracted(TextTools.clean(HtmlText.toPlain(decode(file.readBytes()))), emptyList())
            "rtf" -> throw ExtractException(R.string.err_rtf)
            "doc" -> throw ExtractException(R.string.err_doc)
            "mobi" -> throw ExtractException(R.string.err_mobi)
            "zip" -> throw ExtractException(R.string.err_zip)
            else -> txt(file)
        }
        // E-kutubxona belgilari (ziyouz.com...) joy belgisi yoʻq formatlarda butun matndan olib tashlanadi.
        val r = if (r0.marks.isEmpty()) {
            Extracted(TextTools.clean(TextTools.cleanWatermarks(r0.text)), r0.marks, r0.warning, r0.unreadable)
        } else {
            r0
        }
        if (r.text.isBlank()) throw ExtractException(R.string.err_no_text)
        return r
    }

    fun detect(file: File, name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val head = ByteArray(4096)
        val n = try {
            file.inputStream().use { it.read(head) }
        } catch (e: Exception) {
            -1
        }
        if (n >= 4 && head[0] == '%'.code.toByte() && head[1] == 'P'.code.toByte() &&
            head[2] == 'D'.code.toByte() && head[3] == 'F'.code.toByte()
        ) return "pdf"
        if (n >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) return sniffZip(file, ext)
        if (n >= 5 && String(head, 0, 5, Charsets.ISO_8859_1) == "{\\rtf") return "rtf"
        if (n >= 4 && head[0] == 0xD0.toByte() && head[1] == 0xCF.toByte() &&
            head[2] == 0x11.toByte() && head[3] == 0xE0.toByte()
        ) return "doc"
        val sample = if (n > 0) String(head, 0, n, Charsets.ISO_8859_1).lowercase(Locale.ROOT) else ""
        if (sample.contains("<fictionbook")) return "fb2"
        if (sample.contains("<html") || sample.contains("<!doctype html")) return "html"
        return when (ext) {
            "fb2" -> "fb2"
            "html", "htm", "xhtml" -> "html"
            "rtf" -> "rtf"
            "doc" -> "doc"
            "mobi", "azw", "azw3" -> "mobi"
            else -> "txt"
        }
    }

    private fun sniffZip(file: File, ext: String): String {
        try {
            val z = ZipFile(file)
            try {
                if (z.getEntry("word/document.xml") != null) return "docx"
                if (z.getEntry("META-INF/container.xml") != null) return "epub"
                if (z.getEntry("content.xml") != null && z.getEntry("mimetype") != null) return "odt"
                val en = z.entries()
                while (en.hasMoreElements()) {
                    val e = en.nextElement()
                    if (e.name.lowercase(Locale.ROOT).endsWith(".fb2")) return "fb2zip"
                }
            } finally {
                try {
                    z.close()
                } catch (t: Throwable) {
                }
            }
        } catch (e: Exception) {
            Journal.w("Extract", "ZIP tuzilishini aniqlab boʻlmadi", e)
        }
        return when (ext) {
            "epub" -> "epub"
            "docx" -> "docx"
            "odt" -> "odt"
            else -> "zip"
        }
    }

    /** BOM → qatʼiy UTF-8 → (boʻlmasa) windows-1251 (eski kirill matnlar uchun). */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        return try {
            val dec = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            dec.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            Journal.w("Extract", "Fayl UTF-8 emas — windows-1251 deb oʻqildi")
            String(bytes, Charset.forName("windows-1251"))
        }
    }

    private fun txt(file: File): Extracted {
        val bytes = file.readBytes()
        var zeros = 0
        val lim = minOf(bytes.size, 4096)
        for (i in 0 until lim) if (bytes[i].toInt() == 0) zeros++
        val bom16 = bytes.size >= 2 &&
            ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) || (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))
        if (zeros > 20 && !bom16) throw ExtractException(R.string.err_binary)
        return Extracted(TextTools.clean(decode(bytes)), emptyList())
    }

    /** Sahifada shuncha belgidan kam raqamli matn boʻlsa (asosan skanerlangan hujjatda) — u sahifa AI bilan oʻqiladi. */
    private const val MIN_DIGITAL = 80

    /** Sahifaning raqamli matni buzilgan koʻrinadimi (shrift kodirovkasi muammosi, "�" va shu kabi belgilar koʻp)? */
    private fun looksGarbled(t: String): Boolean {
        var nonSpace = 0
        var letters = 0
        var junk = 0
        for (c in t) {
            if (c.isWhitespace()) continue
            nonSpace++
            if (c.isLetter()) {
                letters++
            } else if (c == '\uFFFD' || (c >= '\uE000' && c <= '\uF8FF')) {
                junk++
            }
        }
        if (nonSpace < 40) return false
        if (junk * 10 > nonSpace) return true
        return letters.toDouble() / nonSpace < 0.5
    }

    private fun pageList(pages: List<Int>): String {
        val sb = StringBuilder()
        for (i in pages.indices) {
            if (i >= 12) {
                sb.append(", …")
                break
            }
            if (i > 0) sb.append(", ")
            sb.append(pages[i])
        }
        return sb.toString()
    }

    /** Sahifadagi shriftlar va rasm/obyektlar soni (tashxis uchun): shrift yoʻq-u rasm bor boʻlsa — skanerlangan sahifa. */
    private fun pageKinds(d: PDDocument, p: Int): String {
        return try {
            val res = d.getPage(p - 1).getResources()
            if (res == null) {
                "resurs yoʻq"
            } else {
                "shriftlar=" + res.getFontNames().count() + ", rasm/obyektlar=" + res.getXObjectNames().count()
            }
        } catch (t: Throwable) {
            "?"
        }
    }

    /**
     * PDF: avval har bir sahifaning raqamli matni olinadi (API ishlatilmaydi). Matni yoʻq, buzilgan yoki juda qisqa
     * (skaner) sahifalar — ocr berilgan boʻlsa — AI (Gemini Vision) bilan oʻqiladi. Oʻqilgan sahifalar keshda saqlanadi.
     */
    private fun pdf(ctx: Context, file: File, ocr: OcrSupport?, progress: (String) -> Unit): Extracted {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        val texts = ArrayList<String>()
        val failed = ArrayList<Boolean>()
        var pages = 0
        var diag = ""
        var toClose: PDDocument? = null
        try {
            val d: PDDocument = try {
                PDDocument.load(file)
            } catch (e: Exception) {
                val m = (e.message ?: "").lowercase(Locale.ROOT)
                if (e.javaClass.simpleName.contains("InvalidPassword") || m.contains("password")) {
                    throw ExtractException(R.string.err_pdf_password)
                }
                throw ExtractException(R.string.err_pdf_open, e.javaClass.simpleName + " " + (e.message ?: ""))
            }
            toClose = d
            pages = d.getNumberOfPages()
            val stripper = PDFTextStripper()
            stripper.setSortByPosition(true)
            for (p in 1..pages) {
                stripper.setStartPage(p)
                stripper.setEndPage(p)
                var bad = false
                val raw = try {
                    stripper.getText(d)
                } catch (e: Exception) {
                    bad = true
                    Journal.w("Extract", "PDF sahifa $p oʻqilmadi", e)
                    ""
                }
                texts.add(TextTools.cleanPdfPage(raw))
                failed.add(bad)
                if (p % 5 == 0 || p == pages) progress(VMsg.make(R.string.prog_pdf, p, pages))
            }
            // Tashxis (xato jurnali uchun): birinchi sahifalarda raqamli belgilar, shriftlar va rasmlar soni.
            // Shrift yoʻq-u rasm bor — skaner (matn qatlami yoʻq); shrift bor-u belgi 0 — matn qatlami bor, lekin oʻqib boʻlmadi.
            val sbd = StringBuilder()
            for (p in 1..minOf(pages, 3)) {
                sbd.append(" | ").append(p).append("-sahifa: belgi=").append(texts[p - 1].length).append(", ").append(pageKinds(d, p))
            }
            diag = sbd.toString()
        } finally {
            try {
                toClose?.close()
            } catch (t: Throwable) {
            }
        }
        if (pages == 0) throw ExtractException(R.string.err_pdf_empty)

        // Qaysi sahifalar AI bilan oʻqilishi kerak: matni yoʻq, buzilgan yoki (asosan skanerlangan hujjatda) juda qisqa.
        var good = 0
        for (t in texts) if (t.length >= MIN_DIGITAL) good++
        val mostlyScanned = good * 2 < pages
        val need = ArrayList<Int>()
        for (i in 0 until pages) {
            val t = texts[i]
            if (failed[i] || t.isEmpty() || looksGarbled(t) || (mostlyScanned && t.length < MIN_DIGITAL)) need.add(i + 1)
        }
        Journal.i(
            "Extract",
            "PDF: " + pages + " sahifa, raqamli matni yetarli: " + good + ", AI bilan oʻqish kerak: " + need.size +
                ", OCR " + (if (ocr != null) "yoqilgan" else "oʻchiq") + diag
        )

        var ocrUsed = false
        if (ocr != null && need.isNotEmpty()) {
            ocrUsed = true
            try {
                ocr.recognize(need, pages)
            } catch (e: OcrInterrupted) {
                // Toʻxtab qolsa ham shu paytgacha oʻqilgan qism (raqamli + OCR) saqlab qoʻyiladi.
                val part = assemblePdf(texts, failed, pages, need, ocr.cache, true)
                e.partial = if (part.text.isBlank()) null else part
                throw e
            }
        }
        val res = assemblePdf(texts, failed, pages, need, if (ocrUsed) ocr?.cache else null, false)
        if (res.text.isBlank()) {
            if (ocr == null && need.isNotEmpty()) throw ExtractException(R.string.err_pdf_scanned)
            if (res.unreadable.isNotEmpty()) {
                // Sahifalar xato tufayli oʻqilmadi — bu "matn yoʻq" degani emas.
                val list = ArrayList<Int>()
                for (u in res.unreadable) list.add(u.first)
                throw ExtractException(R.string.err_pdf_ocr_unread, pageList(list))
            }
            throw ExtractException(R.string.err_pdf_no_text_ocr)
        }
        return res
    }

    /**
     * Sahifalarni tartib bilan yigʻadi: OCR keshidagi matn boʻlsa — shu, aks holda raqamli matn.
     * partial=true: hali OCR qilinmagan sahifalar tashlab ketiladi (qisman matn uchun).
     */
    private fun assemblePdf(
        texts: List<String>,
        failed: List<Boolean>,
        pages: Int,
        need: List<Int>,
        cache: OcrCache?,
        partial: Boolean
    ): Extracted {
        val needSet = HashSet<Int>(need)
        val pieceAt = HashMap<Int, OcrCache.Piece>()
        val badAt = HashMap<Int, String>()
        if (cache != null) {
            for (pc in cache.pieces()) {
                var ok = pc.to >= pc.from
                for (q in pc.from..pc.to) if (!needSet.contains(q)) ok = false
                if (ok) pieceAt[pc.from] = pc
            }
            for (b in cache.bads()) badAt[b.first] = b.second
        }
        val sb = StringBuilder()
        val marks = ArrayList<Mark>()
        val unreadable = ArrayList<Pair<Int, String>>()
        var letters = 0L
        var nonSpace = 0L
        var p = 1
        while (p <= pages) {
            val pc = pieceAt[p]
            val text: String
            val label: String
            var next = p + 1
            if (pc != null) {
                text = pc.text
                label = if (pc.to > pc.from) VMsg.make(R.string.mark_pages, pc.from, pc.to, pages) else VMsg.make(R.string.mark_page, p, pages)
                next = pc.to + 1
            } else {
                label = VMsg.make(R.string.mark_page, p, pages)
                if (partial && needSet.contains(p)) {
                    text = ""
                } else {
                    text = texts[p - 1]
                    if (text.isEmpty() && needSet.contains(p) && badAt.containsKey(p)) {
                        unreadable.add(Pair(p, badAt[p] ?: ""))
                    }
                }
            }
            if (text.isNotEmpty()) {
                marks.add(Mark(sb.length, label))
                sb.append(text).append('\n')
                for (c in text) {
                    if (!c.isWhitespace()) {
                        nonSpace++
                        if (c.isLetter()) letters++
                    }
                }
            }
            p = next
        }
        var warn: String? = null
        if (!partial) {
            if (unreadable.isNotEmpty()) {
                val list = ArrayList<Int>()
                for (u in unreadable) list.add(u.first)
                warn = VMsg.make(R.string.msg_ocr_unreadable, unreadable.size, pageList(list))
            } else if (nonSpace > 200 && letters.toDouble() / nonSpace < 0.5) {
                warn = VMsg.make(R.string.warn_pdf_garbled)
            } else {
                var badRead = 0
                for (i in 0 until pages) {
                    if (failed[i] && texts[i].isEmpty() && !pieceAt.containsKey(i + 1)) badRead++
                }
                if (badRead > 0) warn = VMsg.make(R.string.warn_pdf_bad_pages, badRead)
            }
        }
        return Extracted(sb.toString(), marks, warn, unreadable)
    }

    private fun newParser(ins: InputStream): XmlPullParser {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(ins, null)
        return p
    }

    private fun local(n: String?): String {
        if (n == null) return ""
        val i = n.indexOf(':')
        return if (i >= 0) n.substring(i + 1) else n
    }

    private fun resolvePath(base: String, rel: String): String {
        val parts = ArrayList<String>()
        if (base.isNotEmpty()) parts.addAll(base.split('/'))
        for (seg in rel.split('/')) {
            if (seg == "" || seg == ".") continue
            if (seg == "..") {
                if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            } else {
                parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    private fun epub(file: File, progress: (String) -> Unit): Extracted {
        val zip = ZipFile(file)
        try {
            val ce = zip.getEntry("META-INF/container.xml")
                ?: throw ExtractException(R.string.err_epub_container)
            var opfPath: String? = null
            zip.getInputStream(ce).use { ins ->
                val p = newParser(ins)
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT && opfPath == null) {
                    if (ev == XmlPullParser.START_TAG && local(p.name) == "rootfile") {
                        opfPath = p.getAttributeValue(null, "full-path")
                    }
                    ev = p.next()
                }
            }
            val opf: String = opfPath ?: throw ExtractException(R.string.err_epub_opf_path)
            val oe = zip.getEntry(opf) ?: zip.getEntry(Uri.decode(opf))
                ?: throw ExtractException(R.string.err_epub_opf_missing)
            val manifest = HashMap<String, String>()
            val spine = ArrayList<String>()
            zip.getInputStream(oe).use { ins ->
                val p = newParser(ins)
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        val n = local(p.name)
                        if (n == "item") {
                            val id = p.getAttributeValue(null, "id")
                            val href = p.getAttributeValue(null, "href")
                            if (id != null && href != null) manifest[id] = href
                        } else if (n == "itemref") {
                            val idref = p.getAttributeValue(null, "idref")
                            if (idref != null) spine.add(idref)
                        }
                    }
                    ev = p.next()
                }
            }
            if (spine.isEmpty()) throw ExtractException(R.string.err_epub_spine)
            val dirOf = opf.substringBeforeLast('/', "")
            val sb = StringBuilder()
            val marks = ArrayList<Mark>()
            for ((idx, idref) in spine.withIndex()) {
                val href = manifest[idref] ?: continue
                val path = resolvePath(dirOf, Uri.decode(href.substringBefore('#')))
                val entry = zip.getEntry(path) ?: continue
                val html = try {
                    decode(zip.getInputStream(entry).use { it.readBytes() })
                } catch (e: Exception) {
                    Journal.w("Extract", "EPUB boʻlimi oʻqilmadi: $path", e)
                    ""
                }
                val t = TextTools.clean(TextTools.cleanWatermarks(HtmlText.toPlain(html)))
                if (t.isNotEmpty()) {
                    marks.add(Mark(sb.length, VMsg.make(R.string.mark_section, idx + 1, spine.size)))
                    sb.append(t).append("\n\n")
                }
                if (idx % 3 == 0 || idx == spine.size - 1) progress(VMsg.make(R.string.prog_epub, idx + 1, spine.size))
            }
            return Extracted(sb.toString(), marks)
        } finally {
            try {
                zip.close()
            } catch (t: Throwable) {
            }
        }
    }

    private fun docx(file: File): Extracted {
        val zip = ZipFile(file)
        try {
            val e = zip.getEntry("word/document.xml")
                ?: throw ExtractException(R.string.err_docx_xml)
            val sb = StringBuilder()
            zip.getInputStream(e).use { ins ->
                val p = newParser(ins)
                var inT = false
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        val n = p.name
                        if (n == "w:t") {
                            inT = true
                        } else if (n == "w:tab") {
                            sb.append(' ')
                        } else if (n == "w:br" || n == "w:cr") {
                            sb.append('\n')
                        }
                    } else if (ev == XmlPullParser.END_TAG) {
                        val n = p.name
                        if (n == "w:t") {
                            inT = false
                        } else if (n == "w:p") {
                            sb.append('\n')
                        }
                    } else if (ev == XmlPullParser.TEXT) {
                        if (inT) sb.append(p.text)
                    }
                    ev = p.next()
                }
            }
            return Extracted(TextTools.clean(sb.toString()), emptyList())
        } finally {
            try {
                zip.close()
            } catch (t: Throwable) {
            }
        }
    }

    private fun odt(file: File): Extracted {
        val zip = ZipFile(file)
        try {
            val e = zip.getEntry("content.xml") ?: throw ExtractException(R.string.err_odt_xml)
            val sb = StringBuilder()
            zip.getInputStream(e).use { ins ->
                val p = newParser(ins)
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        val n = p.name
                        if (n == "text:tab") {
                            sb.append(' ')
                        } else if (n == "text:line-break") {
                            sb.append('\n')
                        } else if (n == "text:s") {
                            val c = p.getAttributeValue(null, "text:c")?.toIntOrNull() ?: 1
                            for (k in 0 until minOf(c, 50)) sb.append(' ')
                        }
                    } else if (ev == XmlPullParser.END_TAG) {
                        val n = p.name
                        if (n == "text:p" || n == "text:h") sb.append('\n')
                    } else if (ev == XmlPullParser.TEXT) {
                        sb.append(p.text)
                    }
                    ev = p.next()
                }
            }
            return Extracted(TextTools.clean(sb.toString()), emptyList())
        } finally {
            try {
                zip.close()
            } catch (t: Throwable) {
            }
        }
    }

    /** FB2: <binary> (rasmlar, base64) va <description> oʻtkazib yuboriladi. */
    private fun fb2(ins: InputStream): Extracted {
        val p = newParser(ins)
        val sb = StringBuilder()
        var skipTag: String? = null
        var skipDepth = 0
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                val n = p.name
                if (skipTag != null) {
                    if (n == skipTag) skipDepth++
                } else if (n == "binary" || n == "description") {
                    skipTag = n
                    skipDepth = 1
                } else if (n == "empty-line") {
                    sb.append('\n')
                }
            } else if (ev == XmlPullParser.END_TAG) {
                val n = p.name
                if (skipTag != null) {
                    if (n == skipTag) {
                        skipDepth--
                        if (skipDepth == 0) skipTag = null
                    }
                } else if (n == "p" || n == "v" || n == "subtitle" || n == "text-author" || n == "th" || n == "td") {
                    sb.append('\n')
                } else if (n == "title" || n == "stanza" || n == "section" || n == "epigraph" || n == "cite" || n == "poem") {
                    sb.append("\n\n")
                }
            } else if (ev == XmlPullParser.TEXT) {
                if (skipTag == null) sb.append(p.text)
            }
            ev = p.next()
        }
        return Extracted(TextTools.clean(sb.toString()), emptyList())
    }

    private fun fb2zip(file: File): Extracted {
        val zip = ZipFile(file)
        try {
            val en = zip.entries()
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                if (e.name.lowercase(Locale.ROOT).endsWith(".fb2")) {
                    return zip.getInputStream(e).use { fb2(it) }
                }
            }
            throw ExtractException(R.string.err_fb2_missing)
        } finally {
            try {
                zip.close()
            } catch (t: Throwable) {
            }
        }
    }
}
