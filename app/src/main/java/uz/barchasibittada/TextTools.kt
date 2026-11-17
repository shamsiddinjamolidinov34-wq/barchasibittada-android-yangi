package uz.barchasibittada

import java.io.File

/** Matnni tozalash, boʻlaklash (chunk) va joy belgilarini (sahifa/boʻlim) topish. */
object TextTools {
    private val CTRL = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F-\\u009F\\u00AD\\u200B-\\u200F\\u202A-\\u202E\\u2060\\uFEFF]")
    private val SPACES = Regex("[ \\t\\u00A0\\u1680\\u2000-\\u200A\\u202F\\u205F\\u3000]+")
    private val NL_SPACES = Regex(" *\\n *")
    private val MANY_NL = Regex("\\n{3,}")
    private val NUMLINE = Regex("(?m)^[ \\t]*\\d{1,4}[ \\t]*$")
    private val HYPH = Regex("(\\p{L})-\\n(?=\\p{Ll})")
    private val JOIN = Regex("([^.!?…:;»”\"\\n])\\n(?=\\p{Ll})")
    private val SYMS = Regex("[_=*~#•]{3,}")
    private val DASHES = Regex("-{3,}")
    private val DOTS = Regex("\\.{4,}")
    private val SYMLINE = Regex("(?m)^[ \\t\\p{Punct}\\p{S}•·]+$")

    // E-kutubxona belgilari (masalan "www.ziyouz.com kutubxonasi"): butun matn boʻylab olib tashlanadi.
    private val SITE_MARKS = Regex(
        "(?:https?://)?(?:www\\.)?(?:ziyouz\\.com|ziyonet\\.uz|kitob\\.uz)(?:/[^\\s]*)?(?:[ \\t]+kutubxonasi)?",
        RegexOption.IGNORE_CASE
    )

    // Faqat sahifa raqamidan iborat qatorlar: "12", "- 12 -", "Sahifa 12", "12 / 300", "page 12", "стр. 12".
    private val PAGE_LINE = Regex(
        "^[ \\t]*(?:(?:page|sahifa|bet|стр(?:аница)?\\.?)[ \\t]*[:#\\-]?[ \\t]*)?[-—–~_*\\[(]?[ \\t]*\\d{1,4}[ \\t]*(?:[/—–][ \\t]*\\d{1,4})?[ \\t]*[-—–~_*\\])]?[ \\t]*$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
    )

    // Model ba'zan javobni ``` bilan oʻrab yuboradi — bunday qatorlar olib tashlanadi.
    private val FENCE = Regex("(?m)^[ \\t]*```[A-Za-z]*[ \\t]*$")

    /** Koʻrinmas/boshqaruv belgilarini olib tashlaydi, boʻshliq va boʻsh qatorlarni tartiblaydi. */
    fun clean(raw: String): String {
        var s = raw.replace("\r\n", "\n").replace('\r', '\n')
        s = CTRL.replace(s, "")
        s = SPACES.replace(s, " ")
        s = NL_SPACES.replace(s, "\n")
        s = MANY_NL.replace(s, "\n\n")
        return s.trim()
    }

    /** E-kutubxona belgilari (ziyouz.com, ziyonet.uz, kitob.uz) olib tashlanadi. Boshqa hech narsa oʻzgarmaydi. */
    fun cleanWatermarks(raw: String): String {
        if (raw.isEmpty()) return raw
        return SITE_MARKS.replace(raw, "")
    }

    /** PDF sahifasi: belgilar va sahifa raqami qatorlari olib tashlanadi, soʻz koʻchirishlar va uzilgan qatorlar birlashtiriladi. */
    fun cleanPdfPage(raw: String): String {
        var s = clean(cleanWatermarks(raw))
        s = PAGE_LINE.replace(s, "")
        s = NUMLINE.replace(s, "")
        s = HYPH.replace(s, "\$1")
        s = JOIN.replace(s, "\$1 ")
        return clean(s)
    }

    /**
     * AI (OCR) bergan sahifa matni: sahifa belgilari (<<<PAGE n>>>), e-kutubxona belgilari va sahifa raqamlari olib tashlanadi.
     * Qatorlarni birlashtirish (JOIN) qilinmaydi: model abzaslarni oʻzi birlashtiradi, she'riy satrlar esa alohida qolishi kerak.
     */
    fun cleanOcrPage(raw: String): String {
        var s = clean(cleanWatermarks(OcrParse.stripMarkers(raw)))
        s = FENCE.replace(s, "")
        s = PAGE_LINE.replace(s, "")
        s = NUMLINE.replace(s, "")
        s = HYPH.replace(s, "\$1")
        return clean(s)
    }

    /** TTS ga yuboriladigan matn: ortiqcha belgi-chiziqlar (*****, _____, ....) olib tashlanadi. */
    fun forSpeech(raw: String): String {
        var s = SYMLINE.replace(raw, "")
        s = SYMS.replace(s, " ")
        s = DASHES.replace(s, " ")
        s = DOTS.replace(s, "…")
        s = MANY_NL.replace(s, "\n\n")
        return s.trim()
    }

    fun hasSpeakable(s: String): Boolean {
        for (c in s) if (c.isLetterOrDigit()) return true
        return false
    }

    /** Raqamlar ogʻzaki koʻp joy oladi — davomiylik tekshiruvi uchun raqamni 8 belgi deb hisoblaymiz. */
    fun effectiveLen(s: String): Int {
        var n = 0
        for (c in s) n += if (c in '0'..'9') 8 else 1
        return n
    }

    private fun isCloser(c: Char): Boolean =
        c == '"' || c == '»' || c == '”' || c == '’' || c == '\'' || c == ')' || c == ']'

    /**
     * start dan boshlab ko'pi bilan max belgilik boʻlak oxirini topadi.
     * Imkon boricha qator/gap oxirida, boʻlmasa vergul yoki boʻshliqda kesadi.
     */
    fun nextEnd(t: String, start: Int, max: Int): Int {
        val n = t.length
        if (n - start <= max) return n
        var hard = start + max
        if (hard > start + 1 && hard < n && Character.isLowSurrogate(t[hard])) hard--
        val minPos = start + max / 3
        var clause = -1
        var space = -1
        var p = hard - 1
        while (p >= minPos) {
            val c = t[p]
            if (c == '\n') return p + 1
            if (c == '.' || c == '!' || c == '?' || c == '…') {
                val nx = if (p + 1 < n) t[p + 1] else ' '
                if (nx == ' ' || nx == '\n' || isCloser(nx)) {
                    var e = p + 1
                    while (e < hard && isCloser(t[e])) e++
                    return e
                }
            } else if (clause < 0 && (c == ',' || c == ';' || c == ':')) {
                clause = p + 1
            } else if (space < 0 && c == ' ') {
                space = p + 1
            }
            p--
        }
        if (clause > 0) return clause
        if (space > 0) return space
        return hard
    }

    fun saveMarks(f: File, marks: List<Mark>) {
        val sb = StringBuilder()
        for (m in marks) {
            sb.append(m.offset).append('\t').append(m.label.replace('\t', ' ').replace('\n', ' ')).append('\n')
        }
        f.writeText(sb.toString(), Charsets.UTF_8)
    }

    fun loadMarks(f: File): List<Mark> {
        val res = ArrayList<Mark>()
        if (!f.exists()) return res
        try {
            f.forEachLine(Charsets.UTF_8) { ln ->
                val i = ln.indexOf('\t')
                if (i > 0) {
                    val o = ln.substring(0, i).toIntOrNull()
                    if (o != null) res.add(Mark(o, ln.substring(i + 1)))
                }
            }
        } catch (t: Throwable) {
            Journal.w("TextTools", "Joy belgilarini oʻqib boʻlmadi", t)
        }
        return res
    }

    /** pos joyi qaysi sahifa/boʻlimga tegishli ekanini qaytaradi (belgilar boʻlmasa — boʻsh). */
    fun labelAt(marks: List<Mark>, pos: Int): String {
        if (marks.isEmpty()) return ""
        var lo = 0
        var hi = marks.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (marks[mid].offset <= pos) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return if (ans >= 0) marks[ans].label else marks[0].label
    }
}
