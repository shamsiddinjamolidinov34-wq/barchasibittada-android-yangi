package uz.barchasibittada

class QuizQ(val savol: String, val variantlar: List<Pair<String, String>>, val javob: String)

/** uz-ai-bot dagi uzbek.py, bot.py va providers.py ning matn bilan ishlaydigan qismlari. */
object Uz {
    // ------------------------------------------------------------------
    // Yozuv almashtirish
    // ------------------------------------------------------------------
    private val APOSTROF_RE = Regex("['‘’ʻʼ`´]")

    fun birXil(s: String): String = APOSTROF_RE.replace(s, "'")

    private val KL: Map<Char, String> = mapOf(
        'ў' to "o'", 'ғ' to "g'", 'ҳ' to "h", 'қ' to "q",
        'ё' to "yo", 'ю' to "yu", 'я' to "ya", 'щ' to "shch",
        'ж' to "j", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh",
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d",
        'е' to "e", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k",
        'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p",
        'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f",
        'х' to "x", 'ы' to "i", 'э' to "e", 'ь' to "", 'ъ' to "'"
    )

    private val LK: List<Pair<String, String>> = listOf(
        "o'" to "ў", "g'" to "ғ", "sh" to "ш", "ch" to "ч", "ng" to "нг",
        "ya" to "я", "yo" to "ё", "yu" to "ю", "ye" to "е", "ts" to "ц",
        "a" to "а", "b" to "б", "d" to "д", "e" to "е", "f" to "ф",
        "g" to "г", "h" to "ҳ", "i" to "и", "j" to "ж", "k" to "к",
        "l" to "л", "m" to "м", "n" to "н", "o" to "о", "p" to "п",
        "q" to "қ", "r" to "р", "s" to "с", "t" to "т", "u" to "у",
        "v" to "в", "x" to "х", "y" to "й", "z" to "з", "c" to "к"
    )

    private const val UNLI = "aeiouo'"

    private fun cap(s: String): String = s.replaceFirstChar { it.uppercaseChar() }

    fun kirilToLotin(s: String): String {
        if (s.isEmpty()) return ""
        val t = birXil(s)
        val sb = StringBuilder()
        for (ch in t) {
            val rep = KL[ch.lowercaseChar()]
            if (rep == null) {
                sb.append(ch)
                continue
            }
            if (rep.isEmpty()) continue
            if (ch.isUpperCase()) sb.append(cap(rep)) else sb.append(rep)
        }
        return sb.toString()
    }

    fun lotinToKiril(s: String): String {
        if (s.isEmpty()) return ""
        val matn = birXil(s)
        var kichik = matn.lowercase()
        if (kichik.length != matn.length) {
            val arr = CharArray(matn.length)
            for (k in matn.indices) arr[k] = matn[k].lowercaseChar()
            kichik = String(arr)
        }
        val sb = StringBuilder()
        var i = 0
        while (i < matn.length) {
            var found = false
            for (pair in LK) {
                val lotin = pair.first
                if (kichik.startsWith(lotin, i)) {
                    val oldingi: Char? = if (i > 0) kichik[i - 1] else null
                    var yangi = pair.second
                    if (lotin == "e" && (oldingi == null || " .,!?;:(".indexOf(oldingi) >= 0)) {
                        yangi = "э"
                    } else if ((lotin == "ya" || lotin == "yo" || lotin == "yu") && oldingi != null && UNLI.indexOf(oldingi) >= 0) {
                        yangi = "ъ" + pair.second
                    }
                    if (matn[i].isUpperCase()) yangi = cap(yangi)
                    sb.append(yangi)
                    i += lotin.length
                    found = true
                    break
                }
            }
            if (!found) {
                sb.append(matn[i])
                i += 1
            }
        }
        return sb.toString()
    }

    private fun isKirilLetter(ch: Char): Boolean {
        return (ch in 'а'..'я') || (ch in 'А'..'Я') || ch == 'ё' || ch == 'Ё' ||
            ch == 'ў' || ch == 'Ў' || ch == 'ғ' || ch == 'Ғ' || ch == 'қ' || ch == 'Қ' || ch == 'ҳ' || ch == 'Ҳ'
    }

    fun asosanKiril(s: String, chegara: Double = 0.3): Boolean {
        if (s.isEmpty()) return false
        var letters = 0
        var kiril = 0
        for (ch in s) {
            if (ch.isLetter()) {
                letters += 1
                if (isKirilLetter(ch)) kiril += 1
            }
        }
        if (letters == 0) return false
        return kiril.toDouble() / letters.toDouble() > chegara
    }

    /** Ovoz sintezi uchun matn: kirill bo'lsa lotinga o'giriladi (Microsoft ovozlari kirillni o'qimaydi). */
    fun ovozgaTayyor(s: String): Pair<String, Boolean> {
        if (asosanKiril(s)) return Pair(kirilToLotin(s), true)
        return Pair(birXil(s), false)
    }

    // ------------------------------------------------------------------
    // Niyatni aniqlash («o'qib ber», «lotinchaga o'gir», «imlosini to'g'irla» ...)
    // ------------------------------------------------------------------
    private val NIYAT: List<Pair<String, List<String>>> = listOf(
        "ovoz" to listOf("ovoz", "audio", "mp3", "tingla", "o'qib ber", "o'qib bering", "o'qi bering"),
        "kiril" to listOf("kiril"),
        "lotin" to listOf("lotin"),
        "tahrir" to listOf("imlo", "tahrir", "to'g'irla", "tuzat", "tahrirla"),
        "maqola" to listOf("maqola", "maqala", "artikl"),
        "tarjima" to listOf(
            "tarjima", "tarjimon", "o'gir", "ogir", "o'girib", "aylantir",
            "ruscha", "ruschaga", "rus tiliga", "inglizcha", "inglizchaga",
            "ingliz tiliga", "uzbekcha", "o'zbekchaga", "o'zbek tiliga",
            "nemischa", "fransuzcha", "arabcha", "turkcha", "tarjima qil"
        )
    )

    private val TIL_SOZLARI: Set<String> = setOf(
        "ruscha", "ruschaga", "rus tiliga", "inglizcha", "inglizchaga", "ingliz tiliga",
        "uzbekcha", "o'zbekchaga", "o'zbek tiliga", "nemischa", "fransuzcha", "arabcha", "turkcha"
    )

    private val STRIP_CHARS = charArrayOf('.', ',', '!', '?', ';', ':', '(', ')', '«', '»', '"', '\'')
    private val WS = Regex("\\s+")

    private val TIL_BOSH_RE: Regex = Regex(
        "^\\s*(?:" + TIL_SOZLARI.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")" +
            "\\s*(?:o[’']?gir\\w*|aylantir\\w*|tarjima\\w*|qil\\w*)?\\s*[:\\-–—]?\\s*",
        RegexOption.IGNORE_CASE
    )
    private val FEIL_BOSH_RE: Regex = Regex(
        "^\\s*(?:o[’']?gir\\w*|aylantir\\w*|tarjima\\w*|tarjimon|qil\\w*)\\s*[:\\-–—,]?\\s*",
        RegexOption.IGNORE_CASE
    )
    private val AJRATGICH_RE = Regex("[,;:]")

    private fun sozlarOf(past: String): Set<String> {
        val out = HashSet<String>()
        for (w in past.split(WS)) {
            if (w.isEmpty()) continue
            out.add(w.trim(*STRIP_CHARS))
        }
        return out
    }

    fun niyatQidir(qisqa: String): String {
        val past = qisqa.lowercase()
        val sozlar = sozlarOf(past)
        for (entry in NIYAT) {
            for (belgi in entry.second) {
                if (belgi.contains(" ")) {
                    if (past.contains(belgi)) return entry.first
                } else if (sozlar.any { it.startsWith(belgi) }) {
                    return entry.first
                }
            }
        }
        return ""
    }

    fun niyatSozi(matn: String): String {
        val past = matn.lowercase()
        val sozlar = sozlarOf(past)
        var engUzun = ""
        for (entry in NIYAT) {
            for (belgi in entry.second) {
                if (belgi.contains(" ")) {
                    if (past.contains(belgi) && belgi.length > engUzun.length) engUzun = belgi
                } else if (sozlar.any { it.startsWith(belgi) }) {
                    for (soz in sozlar) {
                        if (soz.startsWith(belgi) && soz.length > engUzun.length) engUzun = soz
                    }
                }
            }
        }
        return engUzun
    }

    private fun trimTail(s: String): String = s.trim().trimEnd(',', ';', ':', '-', '–', '—').trim()
    private fun trimHead(s: String): String = s.trimStart(':', '-', '–', '—', ' ').trim()

    private fun matnAjrat(matn: String): String {
        val past = matn.lowercase()
        val sozi = niyatSozi(matn)
        if (sozi.isEmpty()) return matn
        val joy = past.lastIndexOf(sozi)
        if (joy < 0) return ""
        val oxiri = minOf(joy + sozi.length, matn.length)
        val keyin = trimHead(FEIL_BOSH_RE.replaceFirst(matn.substring(oxiri), ""))
        if (keyin.isNotEmpty()) return keyin
        val oldin = trimTail(matn.substring(0, minOf(joy, matn.length)))
        if (TIL_SOZLARI.contains(sozi)) {
            val feil = niyatSozi(oldin)
            if (feil.isNotEmpty()) {
                val feJoy = oldin.lowercase().lastIndexOf(feil)
                if (feJoy > 0) return trimTail(oldin.substring(0, feJoy))
            }
        }
        val bosh = trimHead(TIL_BOSH_RE.replaceFirst(oldin, ""))
        if (bosh.isNotEmpty()) return bosh
        return oldin
    }

    /** Qaytaradi: (niyat, ishlanadigan matn). Niyat: ovoz, kiril, lotin, tahrir, maqola, tarjima yoki "". */
    fun niyatAniqla(matn: String, chegara: Int = 120): Pair<String, String> {
        val toza = birXil(matn.trim())
        if (toza.isEmpty()) return Pair("", "")
        val qatorlar = toza.split("\n")
        val oxiri = qatorlar[qatorlar.size - 1].trim()
        if (oxiri.length <= chegara) {
            val topilgan = niyatQidir(oxiri)
            if (topilgan.isNotEmpty()) {
                var qolgan = if (qatorlar.size > 1) qatorlar.subList(0, qatorlar.size - 1).joinToString("\n").trim() else ""
                if (qolgan.isEmpty() && AJRATGICH_RE.containsMatchIn(oxiri)) qolgan = matnAjrat(oxiri)
                return Pair(topilgan, qolgan)
            }
        }
        if (qatorlar.size > 1) {
            val boshi = qatorlar[0].trim()
            if (boshi.length <= chegara) {
                val topilgan = niyatQidir(boshi)
                if (topilgan.isNotEmpty()) {
                    return Pair(topilgan, qatorlar.subList(1, qatorlar.size).joinToString("\n").trim())
                }
            }
        }
        if (toza.length <= chegara) return Pair(niyatQidir(toza), "")
        return Pair("", "")
    }

    // ------------------------------------------------------------------
    // Til nomi va buyruq so'zlarini matndan ajratish
    // ------------------------------------------------------------------
    val TARJIMA_TILLAR: Map<String, String> = mapOf("uz" to "o'zbek", "ru" to "rus", "en" to "ingliz")

    private val TIL_KORINISH: List<Pair<String, List<String>>> = listOf(
        "uz" to listOf("o'zbek", "ozbek", "uzbek", "o'zbekcha", "uzbekcha", "o'zbek tiliga", "ozb"),
        "ru" to listOf("rus", "russian", "ruscha", "ruschaga", "rus tiliga", "рус"),
        "en" to listOf("ingliz", "inglizcha", "inglizchaga", "ingliz tiliga", "english", "англ")
    )

    private val TIL_BOLAKLARI: List<Pair<String, String>> = run {
        val all = ArrayList<Pair<String, String>>()
        for (e in TIL_KORINISH) for (b in e.second) all.add(Pair(b, e.first))
        all.sortedByDescending { it.first.length }
    }

    fun tilAjrat(matn: String): String {
        val past = matn.lowercase().replace("’", "'")
        for (p in TIL_BOLAKLARI) {
            if (past.contains(p.first)) return p.second
        }
        return ""
    }

    private val TIL_QOLDIQ_RE = Regex(
        "\\b(tili\\w*|til|tarjima\\w*|tarjimon|o[’']?gir\\w*|aylantir\\w*|qil\\w*)\\b",
        RegexOption.IGNORE_CASE
    )

    fun tilniOlibTashla(matn: String): String {
        val past = matn.lowercase().replace("’", "'")
        var topilgan = ""
        for (p in TIL_BOLAKLARI) {
            if (past.contains(p.first)) {
                topilgan = p.first
                break
            }
        }
        var qolgan: String
        if (topilgan.isNotEmpty()) {
            qolgan = Regex(Regex.escape(topilgan), RegexOption.IGNORE_CASE).replaceFirst(matn, " ")
        } else {
            val bolaklar = matn.trim().split(WS, 2)
            if (bolaklar.size < 2 || !TARJIMA_TILLAR.containsKey(bolaklar[0].lowercase())) return matn
            qolgan = bolaklar[1]
        }
        qolgan = TIL_QOLDIQ_RE.replace(qolgan, " ")
        return trimHead(qolgan.trim())
    }

    private val BUYRUQ_BOSH_RE = Regex(
        "^(o[’']?qib ber\\w*|o[’']?qi bering|tingla\\w*|ovoz\\w*|audio|mp3)\\b\\s*[:\\-–—,]?\\s*",
        RegexOption.IGNORE_CASE
    )
    private val BUYRUQ_OXIR_RE = Regex(
        "[\\s,:\\-–—]+(o[’']?qib ber(?:ing)?|o[’']?qi bering|tingla|ovoz|audio|mp3)\\s*[.!?]?\\s*$",
        RegexOption.IGNORE_CASE
    )

    fun buyruqSoziniOlibTashla(matn: String): String {
        var t = matn.trim()
        for (k in 0 until 2) {
            if (tilAjrat(t).isNotEmpty()) t = tilniOlibTashla(t)
            t = BUYRUQ_BOSH_RE.replaceFirst(t, "")
        }
        t = BUYRUQ_OXIR_RE.replaceFirst(t, "")
        return t.trim().trimStart(':', '-', '–', '—', ',', ' ').trim()
    }

    fun translatePrompt(til: String): String {
        val nom = TARJIMA_TILLAR[til.lowercase()]
        val qoida = if (nom != null) {
            "Matnni " + nom + " tiliga tarjima qil."
        } else {
            "Matn o'zbek tilida bo'lsa — rus tiliga, boshqa tilda bo'lsa — o'zbek tiliga tarjima qil."
        }
        return qoida + " Qoidalar: faqat tarjimani yoz, izoh va sharh qo'shma; " +
            "ilmiy atamani to'g'ri tarjima qil, kerak bo'lsa qavs ichida aslini qoldir; " +
            "jadval va yulduzcha ishlatma."
    }

    /** Fayl izohidagi o'quv rejimi so'zi: konspekt, sodda, savol. */
    fun studyFromCaption(caption: String): String {
        val low = caption.lowercase()
        if (low.contains("konspekt")) return "konspekt"
        if (low.contains("sodda")) return "sodda"
        if (low.contains("savol")) return "savol"
        return ""
    }

    // ------------------------------------------------------------------
    // Maqola
    // ------------------------------------------------------------------
    private val YIL_SOZI = listOf("yil", "yilda", "yildagi", "yillar", "yillarda", "yillik", "asr", "asrda")
    private val YIL_BOSH_RE = Regex("^\\d+\\s+(?:" + YIL_SOZI.joinToString("|") + ")\\b", RegexOption.IGNORE_CASE)
    private val RAQAM_AJR_RE = Regex("^(\\d{1,2})[.)\\-–:]\\s*(.+)$")
    private val RAQAM_BOSH_RE = Regex("^(\\d{1,2})\\s+(.+)$")
    private val SAHIFA_RE = Regex("(\\d{1,15})\\s*(?:ta\\s+)?sahifa")

    fun maqolaSahifaSoni(matn: String): Int {
        val m = SAHIFA_RE.find(matn.lowercase()) ?: return 0
        val v = m.groupValues[1].toLongOrNull() ?: return 0
        return if (v > 1000000L) 1000000 else v.toInt()
    }

    fun maqolaMavzusi(matn: String, sahifa: Int): String {
        var t = matn.trim()
        if (sahifa > 0) {
            t = Regex("\\b" + sahifa.toString() + "\\s*(?:ta\\s+)?sahifa(?:li|lik)?\\b", RegexOption.IGNORE_CASE).replace(t, " ")
        }
        t = Regex("\\bmaqola\\b", RegexOption.IGNORE_CASE).replace(t, " ")
        t = Regex("\\s{2,}").replace(t, " ")
        return t.trim(' ', '.', ',', '-', '—', ':')
    }

    fun maqolaBolaklari(matn: String, limit: Int = 8): List<String> {
        val bolaklar = ArrayList<String>()
        for (qator in matn.split("\n")) {
            val tozalangan = qator.trim().trimStart('-', '*', '•', '·', ' ', '\t')
            if (tozalangan.isEmpty()) continue
            val raqamli = tozalangan[0].isDigit()
            val yilBosh = YIL_BOSH_RE.containsMatchIn(tozalangan)
            var sarlavha: String
            if (yilBosh) {
                sarlavha = tozalangan
            } else if (raqamli) {
                var mos = RAQAM_AJR_RE.find(tozalangan)
                if (mos == null) {
                    mos = RAQAM_BOSH_RE.find(tozalangan)
                    if (mos != null) {
                        val birinchi = tozalangan.split(WS)[0]
                        if (birinchi.length > 2) mos = null
                    }
                }
                if (mos != null) sarlavha = mos.groupValues[2].trim() else continue
            } else {
                sarlavha = tozalangan
            }
            sarlavha = sarlavha.trim(' ', '.', ':', '-', '—')
            if (sarlavha.isEmpty() || sarlavha.length > 120) continue
            if (!bolaklar.contains(sarlavha)) bolaklar.add(sarlavha)
            if (bolaklar.size >= limit) break
        }
        return bolaklar
    }

    // ------------------------------------------------------------------
    // Matnni bo'laklash
    // ------------------------------------------------------------------

    /** Ovoz sintezi uchun: paragraf, gap, so'z chegarasida bo'ladi. */
    fun bolaklarga(matn: String, chegara: Int): List<String> {
        val t = matn.trim()
        if (t.isEmpty()) return emptyList()
        if (t.length <= chegara) return listOf(t)
        val out = ArrayList<String>()
        var qolgan = t
        while (qolgan.length > chegara) {
            var kesim = -1
            var joy = qolgan.lastIndexOf("\n\n", chegara - 2)
            if (joy > chegara / 3) kesim = joy + 2
            if (kesim < 0) {
                for (belgi in listOf(". ", "! ", "? ", ".\n", "!\n", "?\n")) {
                    joy = qolgan.lastIndexOf(belgi, chegara - belgi.length)
                    if (joy > kesim) kesim = joy + belgi.length
                }
            }
            if (kesim < 0) {
                joy = qolgan.lastIndexOf(" ", chegara - 1)
                kesim = if (joy > 0) joy + 1 else chegara
            }
            out.add(qolgan.substring(0, kesim).trim())
            qolgan = qolgan.substring(kesim).trimStart()
        }
        if (qolgan.isNotEmpty()) out.add(qolgan)
        return out.filter { it.isNotEmpty() }
    }

    /** Imlo tuzatish uchun: abzats chegarasida bo'ladi. */
    fun tahrirBolaklari(matn: String, limit: Int = 3000): List<String> {
        val out = ArrayList<String>()
        var joriy = ""
        for (qator in matn.split("\n")) {
            if (joriy.isNotEmpty() && joriy.length + qator.length + 1 > limit) {
                out.add(joriy)
                joriy = ""
            }
            joriy = if (joriy.isNotEmpty()) joriy + "\n" + qator else qator
            while (joriy.length > limit) {
                out.add(joriy.substring(0, limit))
                joriy = joriy.substring(limit)
            }
        }
        if (joriy.isNotBlank()) out.add(joriy)
        return out.filter { it.isNotBlank() }
    }

    // ------------------------------------------------------------------
    // Test (quiz)
    // ------------------------------------------------------------------
    private val SAVOL_RE = Regex("^\\s*(\\d{1,2})\\s*[.)]\\s*(.+?)\\s*$")
    private val VARIANT_RE = Regex("^\\s*([A-Da-d])\\s*[).\\-–—]\\s*(.+?)\\s*$")
    private val JAVOB_RE = Regex("^\\s*(?:to'g'ri\\s+)?javob\\s*[:\\-]\\s*([A-Da-d])\\b", RegexOption.IGNORE_CASE)

    private class QB(var savol: String) {
        val variantlar = ArrayList<Pair<String, String>>()
        var javob = ""
    }

    fun parseQuiz(matn: String, limit: Int = 20): List<QuizQ> {
        val out = ArrayList<QuizQ>()
        var joriy: QB? = null
        for (xom in matn.split("\n")) {
            val qator = xom.trim().trimStart('*', '#', '-').trim()
            if (qator.isEmpty()) continue

            val javob = JAVOB_RE.find(qator)
            if (javob != null && joriy != null) {
                joriy.javob = javob.groupValues[1].uppercase()
                if (joriy.variantlar.size >= 2) out.add(QuizQ(joriy.savol, joriy.variantlar.toList(), joriy.javob))
                joriy = null
                continue
            }
            val variant = VARIANT_RE.find(qator)
            if (variant != null && joriy != null) {
                joriy.variantlar.add(Pair(variant.groupValues[1].uppercase(), variant.groupValues[2]))
                continue
            }
            val savol = SAVOL_RE.find(qator)
            if (savol != null) {
                joriy = QB(savol.groupValues[2])
                continue
            }
            if (joriy != null && joriy.variantlar.isEmpty()) {
                joriy.savol = (joriy.savol + " " + qator).trim()
            }
        }
        return if (out.size > limit) out.subList(0, limit).toList() else out
    }

    // ------------------------------------------------------------------
    // Botdagi 18+ va diniy so'zlar filtri (ilovada ixtiyoriy)
    // ------------------------------------------------------------------
    private val TAQIQ_18 = listOf(
        "pornografiya", "pornografik", "porno", "18+", "18 +",
        "erotika", "erotik", "seks", "sikish", "yalang'och",
        "noznoj", "intim"
    )
    private val TAQIQ_DIN = listOf(
        "islom", "musulmon", "din", "diniy", "dindan", "dinda", "dinim",
        "qur'on", "quron", "hadis", "fiqh", "fiqhiy", "shariat",
        "namoz", "ro'za", "roza", "zakot", "hajj", "umra",
        "alloh", "xudo", "payg'ambar", "rasul", "sahoba", "imom",
        "fatvo", "fatva", "halol", "harom", "haram", "sunniy", "shia",
        "mazhab", "nikoh", "tavba", "ibodat", "sadaqa", "qibla",
        "janoza", "adhan", "azon", "qurbonlik", "ramazon", "mavlid"
    )

    /** "18", "din" yoki "" */
    fun taqiqTuri(savol: String): String {
        val matn = " " + savol.lowercase().replace("‘", "'").replace("ʻ", "'") + " "
        for (s in TAQIQ_18) if (matn.contains(s)) return "18"
        for (s in TAQIQ_DIN) if (matn.contains(s)) return "din"
        return ""
    }

    // ------------------------------------------------------------------
    // Javob matnini ekran o'quvchi uchun tozalash
    // ------------------------------------------------------------------
    private val HEADING_RE = Regex("^\\s{0,3}#{1,6}\\s+")

    /** **qalin** belgilari va # sarlavha belgilarini olib tashlaydi (kod bloklariga tegmaydi). */
    fun plain(s: String): String {
        val out = StringBuilder()
        var inCode = false
        for (line in s.split("\n")) {
            if (line.trim().startsWith("```")) {
                inCode = !inCode
                out.append(line).append("\n")
                continue
            }
            if (inCode) {
                out.append(line).append("\n")
                continue
            }
            var l = HEADING_RE.replaceFirst(line, "")
            l = l.replace("**", "").replace("__", "")
            out.append(l).append("\n")
        }
        return out.toString().trimEnd()
    }
}
