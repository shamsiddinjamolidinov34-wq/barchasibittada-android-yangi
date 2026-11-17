package uz.barchasibittada

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import android.webkit.MimeTypeMap
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

/** Tanlangan yoki ulashilgan fayl (ilova keshiga ko'chirilgan nusxa). */
class Picked(val name: String, val mime: String, val file: File) {
    val size: Long get() = file.length()
}

class DocError(message: String) : Exception(message)

/** Skanerlangan PDF: matn qatlami yo'q. */
class ScannedPdfException(val pages: Int) : Exception("PDF skanerlangan ko'rinadi (matn qatlami yo'q).")

object Kinds {
    const val IMAGE = "image"
    const val AUDIO = "audio"
    const val VIDEO = "video"
    const val PDF = "pdf"
    const val DOC = "doc"
    const val TEXT = "text"
    const val OTHER = "other"

    fun ext(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i < 0 || i == name.length - 1) "" else name.substring(i + 1).lowercase()
    }

    fun of(p: Picked): String {
        val e = ext(p.name)
        val m = p.mime.lowercase()
        if (m.startsWith("image/") || e in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "jfif")) return IMAGE
        if (m.startsWith("audio/") || e in setOf("mp3", "m4a", "m4b", "wav", "ogg", "oga", "opus", "aac", "flac", "amr", "wma", "aiff", "aif", "weba")) return AUDIO
        if (m.startsWith("video/") || e in setOf("mp4", "mov", "webm", "mkv", "avi", "3gp", "m4v", "mpg", "mpeg", "wmv", "flv")) return VIDEO
        if (e == "pdf" || m == "application/pdf") return PDF
        if (e in setOf("docx", "odt", "ods", "odp", "xlsx", "pptx", "epub", "fb2", "html", "htm", "xhtml", "rtf")) return DOC
        if (m.startsWith("text/") || e in setOf("txt", "md", "csv", "tsv", "srt", "vtt", "json", "log", "xml", "ini")) return TEXT
        return OTHER
    }
}

object Files {
    fun guessMime(name: String): String {
        val e = Kinds.ext(name)
        val m = if (e.isEmpty()) null else MimeTypeMap.getSingleton().getMimeTypeFromExtension(e)
        return m ?: "application/octet-stream"
    }

    fun safeName(name: String): String {
        val sb = StringBuilder()
        for (ch in name) {
            if (ch.isLetterOrDigit() || ch == '.' || ch == '-' || ch == '_') sb.append(ch) else sb.append('_')
        }
        val s = sb.toString()
        return if (s.length > 80) s.takeLast(80) else s
    }

    /** Uri dan ilova keshiga nusxa oladi (oqim sifatida, xotirani to'ldirmaydi). */
    fun fromUri(ctx: Context, uri: Uri): Picked? {
        try {
            val cr = ctx.contentResolver
            var name = "fayl"
            val c = cr.query(uri, null, null, null, null)
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) {
                            val n = c.getString(idx)
                            if (!n.isNullOrBlank()) name = n
                        }
                    }
                } finally {
                    c.close()
                }
            } else {
                val last = uri.lastPathSegment
                if (!last.isNullOrBlank()) name = last.substringAfterLast('/')
            }
            var mime = cr.getType(uri) ?: ""
            if (mime.isBlank() || mime == "application/octet-stream") mime = guessMime(name)
            val dest = File(Store.inDir(), System.currentTimeMillis().toString() + "-" + safeName(name))
            val input = cr.openInputStream(uri) ?: return null
            input.use { ins ->
                dest.outputStream().use { outs -> ins.copyTo(outs) }
            }
            return Picked(name, mime, dest)
        } catch (e: Exception) {
            return null
        }
    }

    fun saveText(baseName: String, text: String): File {
        val f = File(Store.outDir(), safeName(baseName) + "-" + System.currentTimeMillis() + ".txt")
        f.writeText(text, Charsets.UTF_8)
        return f
    }
}

object Docs {
    /** Bir hujjatdan olinadigan eng ko'p belgi (xotirani asrash uchun yuqori chegara). */
    const val CHAR_LIMIT = 5000000

    /** Fayldan matn ajratadi (kalit kerak emas). Fon oqimida chaqiriladi. */
    fun extract(p: Picked): String {
        val ext = Kinds.ext(p.name)
        val raw: String = when (ext) {
            "docx" -> docx(p.file)
            "odt", "ods", "odp" -> odt(p.file)
            "pptx" -> pptx(p.file)
            "xlsx" -> xlsx(p.file)
            "epub" -> epub(p.file)
            "fb2" -> fb2(p.file)
            "html", "htm", "xhtml" -> htmlToText(decode(p.file.readBytes()))
            "rtf" -> rtf(decode(p.file.readBytes()))
            "pdf" -> pdf(p.file)
            else -> {
                val bytes = p.file.readBytes()
                if (looksBinary(bytes)) {
                    throw DocError("Bu fayl turini (" + (if (ext.isEmpty()) "noma'lum" else "." + ext) + ") matn sifatida o'qib bo'lmaydi.")
                }
                decode(bytes)
            }
        }
        val t = clean(raw)
        if (t.isBlank()) throw DocError("Faylda o'qiladigan matn topilmadi.")
        return if (t.length > CHAR_LIMIT) t.substring(0, CHAR_LIMIT) else t
    }

    private fun clean(s: String): String {
        var t = s.replace("\r\n", "\n").replace("\r", "\n").replace("\u0000", "")
        t = Regex("[ \\t]+\\n").replace(t, "\n")
        t = Regex("\\n{4,}").replace(t, "\n\n\n")
        return t.trim()
    }

    fun decode(b: ByteArray): String {
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) {
            return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        }
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) {
            return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        }
        var start = 0
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) start = 3
        try {
            val dec = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return dec.decode(ByteBuffer.wrap(b, start, b.size - start)).toString()
        } catch (e: CharacterCodingException) {
            // UTF-8 emas — kirill (windows-1251) deb olamiz
        }
        return String(b, start, b.size - start, Charset.forName("windows-1251"))
    }

    // ---------------- PDF ----------------
    private fun pdf(f: File): String {
        var doc: PDDocument? = null
        try {
            val d = PDDocument.load(f)
            doc = d
            val pages = d.numberOfPages
            val text = PDFTextStripper().getText(d)
            var meaningful = 0
            for (ch in text) if (!ch.isWhitespace()) meaningful += 1
            if (pages > 0 && meaningful < 15 * pages) throw ScannedPdfException(pages)
            return text
        } catch (e: ScannedPdfException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw DocError("PDF juda katta: xotira yetmadi. Kitob rejimidan (skaner) foydalaning.")
        } catch (e: Exception) {
            throw DocError("PDF ni o'qib bo'lmadi (himoyalangan yoki buzilgan bo'lishi mumkin).")
        } finally {
            try {
                doc?.close()
            } catch (e: Exception) {
                // e'tiborsiz
            }
        }
    }

    // ---------------- DOCX ----------------

    private fun pptx(f: File): String {
        ZipFile(f).use { zip ->
            val slides = ArrayList<String>()
            val en = zip.entries()
            while (en.hasMoreElements()) {
                val nm = en.nextElement().name
                if (nm.startsWith("ppt/slides/slide") && nm.endsWith(".xml")) slides.add(nm)
            }
            if (slides.isEmpty()) throw DocError("Bu .pptx fayl emas yoki buzilgan.")
            slides.sortWith(compareBy({ it.length }, { it }))
            val sb = StringBuilder()
            var idx = 0
            for (nm in slides) {
                idx++
                sb.append("Slayd ").append(idx).append(":\n")
                val parser = Xml.newPullParser()
                parser.setInput(zip.getInputStream(zip.getEntry(nm)), "UTF-8")
                var inT = false
                var ev = parser.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        val n = parser.name
                        if (n == "a:t") inT = true
                        else if (n == "a:br") sb.append('\n')
                    } else if (ev == XmlPullParser.END_TAG) {
                        val n = parser.name
                        if (n == "a:t") inT = false
                        else if (n == "a:p") sb.append('\n')
                    } else if (ev == XmlPullParser.TEXT) {
                        if (inT) sb.append(parser.text)
                    }
                    ev = parser.next()
                }
                sb.append('\n')
            }
            return sb.toString()
        }
    }

    private fun xlsx(f: File): String {
        ZipFile(f).use { zip ->
            val shared = ArrayList<String>()
            val se = zip.getEntry("xl/sharedStrings.xml")
            if (se != null) {
                val parser = Xml.newPullParser()
                parser.setInput(zip.getInputStream(se), "UTF-8")
                var cur: StringBuilder? = null
                var inT = false
                var ev = parser.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        val n = parser.name
                        if (n == "si") cur = StringBuilder()
                        else if (n == "t") inT = true
                    } else if (ev == XmlPullParser.END_TAG) {
                        val n = parser.name
                        if (n == "t") {
                            inT = false
                        } else if (n == "si") {
                            shared.add(cur?.toString() ?: "")
                            cur = null
                        }
                    } else if (ev == XmlPullParser.TEXT) {
                        if (inT) cur?.append(parser.text)
                    }
                    ev = parser.next()
                }
            }
            val sheets = ArrayList<String>()
            val en = zip.entries()
            while (en.hasMoreElements()) {
                val nm = en.nextElement().name
                if (nm.startsWith("xl/worksheets/sheet") && nm.endsWith(".xml")) sheets.add(nm)
            }
            if (sheets.isEmpty()) throw DocError("Bu .xlsx fayl emas yoki buzilgan.")
            sheets.sortWith(compareBy({ it.length }, { it }))
            val sb = StringBuilder()
            var idx = 0
            for (nm in sheets) {
                idx++
                if (sheets.size > 1) sb.append("Varaq ").append(idx).append(":\n")
                val parser = Xml.newPullParser()
                parser.setInput(zip.getInputStream(zip.getEntry(nm)), "UTF-8")
                var cellType = ""
                var cell = StringBuilder()
                var inV = false
                var inT = false
                var firstInRow = true
                var ev = parser.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        val n = parser.name
                        if (n == "c") {
                            cellType = parser.getAttributeValue(null, "t") ?: ""
                            cell = StringBuilder()
                        } else if (n == "v") {
                            inV = true
                        } else if (n == "t") {
                            inT = true
                        }
                    } else if (ev == XmlPullParser.END_TAG) {
                        val n = parser.name
                        if (n == "v") {
                            inV = false
                        } else if (n == "t") {
                            inT = false
                        } else if (n == "c") {
                            var value = cell.toString()
                            if (cellType == "s") value = shared.getOrNull(value.trim().toIntOrNull() ?: -1) ?: ""
                            if (!firstInRow) sb.append('\t')
                            sb.append(value)
                            firstInRow = false
                        } else if (n == "row") {
                            sb.append('\n')
                            firstInRow = true
                        }
                    } else if (ev == XmlPullParser.TEXT) {
                        if (inV || inT) cell.append(parser.text)
                    }
                    ev = parser.next()
                }
                sb.append('\n')
            }
            return sb.toString()
        }
    }

    /** Boshqaruv belgilari ko'p bo'lsa — bu matn emas (arxiv, dastur, noma'lum format). UTF-16 BOM li fayllar matn hisoblanadi. */
    private fun looksBinary(b: ByteArray): Boolean {
        if (b.size >= 2) {
            val b0 = b[0].toInt() and 0xFF
            val b1 = b[1].toInt() and 0xFF
            if ((b0 == 0xFF && b1 == 0xFE) || (b0 == 0xFE && b1 == 0xFF)) return false
        }
        val n = minOf(b.size, 4096)
        if (n == 0) return false
        var bad = 0
        for (i in 0 until n) {
            val v = b[i].toInt() and 0xFF
            if (v == 0 || v < 9 || (v in 14..31 && v != 27)) bad++
        }
        return bad * 20 > n
    }

    private fun docx(f: File): String {
        ZipFile(f).use { zip ->
            val e = zip.getEntry("word/document.xml") ?: throw DocError("Bu .docx fayl emas yoki buzilgan.")
            val parser = Xml.newPullParser()
            parser.setInput(zip.getInputStream(e), "UTF-8")
            val sb = StringBuilder()
            var inT = false
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    val n = parser.name
                    if (n == "w:t") inT = true
                    else if (n == "w:tab") sb.append('\t')
                    else if (n == "w:br" || n == "w:cr") sb.append('\n')
                } else if (ev == XmlPullParser.END_TAG) {
                    val n = parser.name
                    if (n == "w:t") inT = false
                    else if (n == "w:p") sb.append('\n')
                } else if (ev == XmlPullParser.TEXT) {
                    if (inT) sb.append(parser.text)
                }
                ev = parser.next()
            }
            return sb.toString()
        }
    }

    // ---------------- ODT ----------------
    private fun odt(f: File): String {
        ZipFile(f).use { zip ->
            val e = zip.getEntry("content.xml") ?: throw DocError("Bu .odt fayl emas yoki buzilgan.")
            val parser = Xml.newPullParser()
            parser.setInput(zip.getInputStream(e), "UTF-8")
            val sb = StringBuilder()
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    val n = parser.name
                    if (n == "text:tab") sb.append('\t')
                    else if (n == "text:line-break") sb.append('\n')
                    else if (n == "text:s") {
                        val c = parser.getAttributeValue(null, "text:c")?.toIntOrNull() ?: 1
                        for (k in 0 until minOf(c, 50)) sb.append(' ')
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    val n = parser.name
                    if (n == "text:p" || n == "text:h") sb.append('\n')
                } else if (ev == XmlPullParser.TEXT) {
                    sb.append(parser.text)
                }
                ev = parser.next()
            }
            return sb.toString()
        }
    }

    // ---------------- FB2 ----------------
    private fun fb2(f: File): String {
        f.inputStream().use { ins ->
            val parser = Xml.newPullParser()
            parser.setInput(ins, null)
            val sb = StringBuilder()
            var inBody = false
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    if (parser.name == "body") inBody = true
                } else if (ev == XmlPullParser.END_TAG) {
                    val n = parser.name
                    if (n == "body") inBody = false
                    else if (n == "p" || n == "title" || n == "subtitle" || n == "section" || n == "v" || n == "empty-line") sb.append('\n')
                } else if (ev == XmlPullParser.TEXT) {
                    if (inBody) sb.append(parser.text)
                }
                ev = parser.next()
            }
            return sb.toString()
        }
    }

    // ---------------- EPUB ----------------
    private fun epub(f: File): String {
        ZipFile(f).use { zip ->
            val container = zip.getEntry("META-INF/container.xml") ?: throw DocError("Bu .epub fayl emas yoki buzilgan.")
            var opfPath = ""
            run {
                val p = Xml.newPullParser()
                p.setInput(zip.getInputStream(container), "UTF-8")
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG && p.name.substringAfter(':') == "rootfile") {
                        opfPath = p.getAttributeValue(null, "full-path") ?: ""
                        break
                    }
                    ev = p.next()
                }
            }
            val manifest = HashMap<String, String>()
            val spine = ArrayList<String>()
            if (opfPath.isNotEmpty()) {
                val opf = zip.getEntry(opfPath)
                if (opf != null) {
                    val p = Xml.newPullParser()
                    p.setInput(zip.getInputStream(opf), "UTF-8")
                    var ev = p.eventType
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        if (ev == XmlPullParser.START_TAG) {
                            val n = p.name.substringAfter(':')
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
            }
            val dir = if (opfPath.contains("/")) opfPath.substringBeforeLast('/') + "/" else ""
            val paths = ArrayList<String>()
            for (id in spine) {
                val href = manifest[id] ?: continue
                val dec = try {
                    URLDecoder.decode(href, "UTF-8")
                } catch (e: Exception) {
                    href
                }
                paths.add(dir + dec.substringBefore('#'))
            }
            if (paths.isEmpty()) {
                val names = ArrayList<String>()
                val en = zip.entries()
                while (en.hasMoreElements()) {
                    val n = en.nextElement().name
                    val low = n.lowercase()
                    if (low.endsWith(".xhtml") || low.endsWith(".html") || low.endsWith(".htm")) names.add(n)
                }
                names.sort()
                paths.addAll(names)
            }
            val sb = StringBuilder()
            for (path in paths) {
                val entry = zip.getEntry(path) ?: continue
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                sb.append(htmlToText(decode(bytes))).append("\n\n")
            }
            return sb.toString()
        }
    }

    // ---------------- HTML ----------------
    private val ENTITY_RE = Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);")

    private fun decodeEntities(s: String): String {
        return ENTITY_RE.replace(s) { m ->
            val g = m.groupValues[1]
            if (g.startsWith("#x") || g.startsWith("#X")) {
                val cp = g.substring(2).toIntOrNull(16)
                if (cp != null && cp in 1..0x10FFFF) String(Character.toChars(cp)) else " "
            } else if (g.startsWith("#")) {
                val cp = g.substring(1).toIntOrNull()
                if (cp != null && cp in 1..0x10FFFF) String(Character.toChars(cp)) else " "
            } else {
                when (g) {
                    "nbsp" -> " "
                    "amp" -> "&"
                    "lt" -> "<"
                    "gt" -> ">"
                    "quot" -> "\""
                    "apos" -> "'"
                    "laquo" -> "«"
                    "raquo" -> "»"
                    "mdash" -> "—"
                    "ndash" -> "–"
                    "hellip" -> "…"
                    else -> " "
                }
            }
        }
    }

    fun htmlToText(html: String): String {
        var t = Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>").replace(html, " ")
        t = Regex("(?i)<br\\s*/?>").replace(t, "\n")
        t = Regex("(?i)</(p|div|h[1-6]|li|tr|section|article|blockquote|title)>").replace(t, "\n")
        t = Regex("(?s)<[^>]+>").replace(t, "")
        t = decodeEntities(t)
        t = Regex("[ \\t\\u00A0]+").replace(t, " ")
        t = Regex(" ?\\n ?").replace(t, "\n")
        return t
    }

    // ---------------- RTF (soddalashtirilgan) ----------------
    private val SKIP_DEST = listOf(
        "\\*", "\\fonttbl", "\\colortbl", "\\stylesheet", "\\info", "\\pict", "\\header", "\\footer",
        "\\themedata", "\\colorschememapping", "\\datastore", "\\latentstyles", "\\generator",
        "\\listtable", "\\listoverridetable", "\\rsidtbl", "\\fldinst", "\\xmlnstbl"
    )

    private fun rtf(s: String): String {
        var cs = "windows-1252"
        val cp = Regex("\\\\ansicpg(\\d{3,4})").find(s)
        if (cp != null) cs = "windows-" + cp.groupValues[1]
        val charset: Charset = try {
            Charset.forName(cs)
        } catch (e: Exception) {
            Charset.forName("windows-1252")
        }
        val sb = StringBuilder()
        var i = 0
        val n = s.length
        var depth = 0
        var skipDepth = -1
        while (i < n) {
            val c = s[i]
            if (c == '{') {
                depth += 1
                i += 1
                if (skipDepth < 0) {
                    for (d in SKIP_DEST) {
                        if (s.startsWith(d, i)) {
                            skipDepth = depth
                            break
                        }
                    }
                }
            } else if (c == '}') {
                if (skipDepth == depth) skipDepth = -1
                depth -= 1
                i += 1
            } else if (c == '\\') {
                i += 1
                if (i >= n) break
                val d = s[i]
                if (d == '\\' || d == '{' || d == '}') {
                    if (skipDepth < 0) sb.append(d)
                    i += 1
                } else if (d == '\'') {
                    val hex = if (i + 3 <= n) s.substring(i + 1, i + 3) else ""
                    val v = hex.toIntOrNull(16)
                    if (v != null && skipDepth < 0) {
                        sb.append(String(byteArrayOf(v.toByte()), charset))
                    }
                    i += 3
                } else if (d == '~') {
                    if (skipDepth < 0) sb.append(' ')
                    i += 1
                } else if (d.isLetter()) {
                    var j = i
                    while (j < n && s[j].isLetter()) j += 1
                    val word = s.substring(i, j)
                    var k = j
                    if (k < n && (s[k] == '-' || s[k].isDigit())) {
                        k += 1
                        while (k < n && s[k].isDigit()) k += 1
                    }
                    val param = s.substring(j, k).toIntOrNull()
                    if (k < n && s[k] == ' ') k += 1
                    i = k
                    if (skipDepth < 0) {
                        if (word == "par" || word == "line" || word == "sect" || word == "page") {
                            sb.append('\n')
                        } else if (word == "tab") {
                            sb.append('\t')
                        } else if (word == "emdash") {
                            sb.append('—')
                        } else if (word == "endash") {
                            sb.append('–')
                        } else if (word == "u" && param != null) {
                            var code = param
                            if (code < 0) code += 65536
                            sb.append(code.toChar())
                            // keyingi "almashtiruvchi" belgini o'tkazib yuboramiz
                            if (i < n) {
                                if (s[i] == '\\' && i + 1 < n && s[i + 1] == '\'') i += 4 else i += 1
                            }
                        }
                    }
                } else {
                    i += 1
                }
            } else {
                if (c != '\n' && c != '\r' && skipDepth < 0) sb.append(c)
                i += 1
            }
        }
        return sb.toString()
    }
}
