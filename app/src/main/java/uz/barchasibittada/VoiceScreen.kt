package uz.barchasibittada

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Ovoz» bo'limi: audiolashtirish ishlari (kitob, matn), ovoz sozlamalari va xato jurnali.
 * Audiolashtirish Audiokitob'dagi usulda ishlaydi: Gemini ovozi, kalitlar navbati, skanerlangan sahifalarni AI bilan o'qish,
 * imlo tuzatish, to'xtagan joydan davom etish.
 */
@Composable
fun VoiceScreen() {
    BackHandler(enabled = Ui.sub.startsWith("v_")) { Ui.sub = "" }
    if (Ui.sub == "v_journal") {
        VoiceJournalScreen()
        return
    }
    if (Ui.sub == "v_set") {
        VoiceSettingsScreen()
        return
    }
    VoiceHome()
}

// ---------------------------------------------------------------------- bosh ekran: ishlar ro'yxati

@Composable
private fun VoiceHome() {
    val tick = JobStore.stamp.intValue
    val jobs = remember(tick) { JobStore.all() }
    var actionId by remember { mutableStateOf("") }
    var deleteId by remember { mutableStateOf("") }
    var textDialog by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    val keyCount = VPrefs.keys(Store.app).size

    LaunchedEffect(Unit) {
        delay(1500L)
        try {
            JobStore.fixStale()
            VoiceJobs.resumeDue(Store.app)
        } catch (t: Throwable) {
            Journal.w("Ovoz", "Ishlarni tekshirib bo'lmadi", t)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Heading("Ovoz (audiolashtirish)")
        Hint(
            "Matn va kitoblar tanlangan ovoz (Gemini, Microsoft, ChatGPT, Grok yoki qurilma ovozi) bilan audioga (MP3) aylantiriladi. " +
                "Skanerlangan PDF butun fayl sifatida AI bilan o'qiladi, " +
                "imlosi kitob mazmuniga tayanib tuzatiladi. Ish fonda davom etadi; to'xtab qolsa, to'xtagan joyidan davom ettiriladi."
        )
        StatusBars()

        val homeEngine = VEngine.effective()
        val homeChosen = VEngine.configured()
        if (homeEngine != homeChosen) {
            Body(
                VEngine.name(homeChosen) + " ovozi uchun kalit kiritilmagan, shuning uchun kalitsiz Microsoft ovozi ishlatiladi. " +
                    "Kalit qo'shish: Sozlamalar → API kalitlar (har bir xizmat uchun kamida " + Keys.MIN_SLOTS + " ta, eng ko'pi " + Keys.MAX + " ta)."
            )
            OBtn("Sozlamalarni ochish", {
                Ui.tab = 3
                Ui.sub = ""
            })
        } else if (VEngine.keyless(homeEngine)) {
            Body("Ovoz dvigateli: " + VEngine.name(homeEngine) + " — kalit kerak emas.")
        } else {
            Body(
                VEngine.name(homeEngine) + " kalitlari: " + VEngine.keyCount(homeEngine) + " ta (eng ko'pi " + Keys.MAX +
                    " ta). Biri limitga yetsa, keyingisiga o'tiladi."
            )
        }
        if (VEngine.keyCount(VEngine.GEMINI) == 0) {
            Hint("Skanerlangan (rasmli) PDF sahifalarini matnga aylantirish uchun Gemini kaliti kerak. Oddiy matnli fayllar kalitsiz ham o'qiladi.")
        }

        Btn("Fayl tanlab audiolashtirish", {
            Platform.pickFiles(arrayOf("*/*")) { list -> voiceStartFile(list[0]) }
        })
        Hint("PDF (skanerlangan ham), Word, EPUB, FB2, TXT va boshqa matn fayllari.")
        OBtn("Matn kiritib audiolashtirish", { textDialog = true })
        OBtn("Ovoz sozlamalari", { Ui.sub = "v_set" })
        OBtn("Xato jurnali (" + Journal.errorCount() + " ta xato)", { Ui.sub = "v_journal" })

        SubHeading("Ishlar")
        if (jobs.isEmpty()) {
            Body("Hozircha ish yo'q. Yuqoridagi tugma bilan fayl tanlang yoki «Asboblar» bo'limidagi «Matnni audiokitobga aylantirish» dan foydalaning.")
        }
        for (j in jobs) {
            VoiceJobCard(j) { actionId = j.id }
        }
        if (Player.current.isNotEmpty()) {
            OBtn("Ijroni to'xtatish", { Player.stop() })
        }
    }

    val aj = jobs.firstOrNull { it.id == actionId }
    if (aj != null) {
        VoiceJobActions(aj, { actionId = "" }, {
            deleteId = aj.id
            actionId = ""
        })
    }

    if (deleteId.isNotEmpty()) {
        ConfirmDialog(
            "Ishni o'chirish",
            "Ish, uning audiosi va matni telefondan o'chiriladi. Kerakli fayllarni oldin saqlab oling. Davom etasizmi?",
            "Ha, o'chirilsin",
            {
                val id = deleteId
                deleteId = ""
                voiceDeleteJob(id)
            },
            { deleteId = "" }
        )
    }

    if (textDialog) {
        AlertDialog(
            onDismissRequest = { textDialog = false },
            title = { Text("Matnni audiolashtirish") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = pasted,
                        onValueChange = { pasted = it },
                        label = { Text("Matn") },
                        minLines = 4,
                        maxLines = 10,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = { pasted = Platform.paste() }) { Text("Buferdan qo'yish") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = pasted
                    pasted = ""
                    textDialog = false
                    voiceStartText(t)
                }) { Text("Audiolashtirish") }
            },
            dismissButton = { TextButton(onClick = { textDialog = false }) { Text("Bekor qilish") } }
        )
    }
}

@Composable
private fun VoiceJobCard(j: VoiceJob, onActions: () -> Unit) {
    val running = j.state in JobStore.ACTIVE
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(j.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(VoiceJobs.statusText(j))
            if (j.state != JobState.DONE) {
                if (running) {
                    Btn("Pauza: " + j.title, { VoiceService.pause(Store.app, j.id) })
                } else {
                    Btn("Davom ettirish: " + j.title, { voiceResume(j) })
                }
            }
            OBtn("Amallar: " + j.title, onActions)
        }
    }
}

@Composable
private fun VoiceActionItem(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

@Composable
private fun VoiceJobActions(j: VoiceJob, onClose: () -> Unit, onDelete: () -> Unit) {
    val audio = JobStore.audio(j)
    val hasAudio = j.audioBytes > 0L && audio.exists()
    val playing = Player.current == audio.absolutePath
    val canRest = j.total > 0 && j.offset < j.total && JobStore.source(j.id).exists()
    val partial = JobStore.partial(j.id)
    val hasPartial = partial.exists() && partial.length() > 0L
    val corr = JobStore.corrected(j.id)
    val hasCorr = corr.exists() && corr.length() > 0L
    val hasSkipped = j.skipped > 0 && JobStore.skipped(j.id).exists()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(j.title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (hasAudio) {
                    VoiceActionItem(if (playing) "Ijroni to'xtatish" else "Audioni ijro etish") {
                        Player.toggle(audio.absolutePath)
                        onClose()
                    }
                    VoiceActionItem("Audioni saqlash (" + j.format.uppercase() + ")") {
                        Platform.saveAs(audio)
                        onClose()
                    }
                    VoiceActionItem("Audioni ulashish") {
                        Platform.share(audio, VoiceJobs.mimeOf(j))
                        onClose()
                    }
                }
                if (canRest) {
                    VoiceActionItem("Qolgan matnni saqlash (TXT)") {
                        voiceExport("rest", j.id, false)
                        onClose()
                    }
                    VoiceActionItem("Qolgan matnni ulashish (TXT)") {
                        voiceExport("rest", j.id, true)
                        onClose()
                    }
                }
                if (hasPartial) {
                    VoiceActionItem("Skanerdan hozirgacha o'qilgan matnni saqlash (TXT)") {
                        voiceExport("partial", j.id, false)
                        onClose()
                    }
                }
                if (hasCorr) {
                    VoiceActionItem("Imlosi tuzatilgan matnni saqlash (TXT)") {
                        voiceExport("corrected", j.id, false)
                        onClose()
                    }
                }
                if (hasSkipped) {
                    VoiceActionItem("O'tkazib yuborilgan qismlarni saqlash (TXT)") {
                        voiceExport("skipped", j.id, false)
                        onClose()
                    }
                }
                VoiceActionItem("Ishni o'chirish") { onDelete() }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Yopish") } }
    )
}

// ---------------------------------------------------------------------- amallar (fon oqimida)

private fun voiceStartText(text: String) {
    if (text.isBlank()) {
        Ui.toast("Avval matn kiriting.")
        return
    }
    Engine.scope.launch {
        val st = withContext(Dispatchers.IO) { VoiceJobs.createFromText("audio", text) }
        Ui.toast(st.message)
    }
}

private fun voiceStartFile(p: Picked) {
    Engine.scope.launch {
        val st = withContext(Dispatchers.IO) { VoiceJobs.createFromPicked(p) }
        Ui.toast(st.message)
    }
}

private fun voiceResume(j: VoiceJob) {
    // Qo'lda davom ettirish: limit/xatodan keyin kalit holatlari tiklanadi va darhol qayta uriniladi.
    val reset = j.state == JobState.LIMIT || j.state == JobState.FAILED
    VoiceService.start(Store.app, j.id, reset)
}

private fun voiceDeleteJob(id: String) {
    Engine.scope.launch {
        withContext(Dispatchers.IO) {
            VoiceService.pause(Store.app, id)
            JobStore.delete(id)
        }
        Ui.toast("Ish o'chirildi.")
    }
}

private fun voiceExport(kind: String, id: String, share: Boolean) {
    Engine.scope.launch {
        val f = withContext(Dispatchers.IO) {
            when (kind) {
                "rest" -> VoiceJobs.remainderFile(id)
                "partial" -> VoiceJobs.partialFile(id)
                "corrected" -> VoiceJobs.correctedFile(id)
                else -> VoiceJobs.skippedFile(id)
            }
        }
        if (f == null) {
            Ui.toast("Saqlanadigan matn topilmadi.")
        } else if (share) {
            Platform.share(f, "text/plain")
        } else {
            Platform.saveAs(f)
        }
    }
}

private fun voiceShareLog(save: Boolean) {
    Engine.scope.launch {
        val f = withContext(Dispatchers.IO) { Journal.exportFile() }
        if (f == null) {
            Ui.toast("Jurnal topilmadi.")
        } else if (save) {
            Platform.saveAs(f)
        } else {
            Platform.share(f, "text/plain")
        }
    }
}

private fun voiceCheckKeys(onDone: (String) -> Unit) {
    Engine.scope.launch {
        val list = VPrefs.keys(Store.app)
        if (list.isEmpty()) {
            onDone("Gemini kaliti kiritilmagan. Sozlamalar bo'limida kalit qo'shing.")
            return@launch
        }
        val sb = StringBuilder()
        withContext(Dispatchers.IO) {
            for (k in list) {
                val r = Gemini.checkKey(k.key)
                sb.append("Kalit ").append(k.slot).append(": ")
                if (r.ok) {
                    sb.append("ishlaydi. Audiolashtirish modellari: ")
                        .append(if (r.ttsModels.isEmpty()) "topilmadi" else r.ttsModels.take(4).joinToString(", "))
                        .append(". Rasm o'qish (OCR) modellari: ")
                        .append(if (r.ocrModels.isEmpty()) "topilmadi" else r.ocrModels.take(3).joinToString(", "))
                        .append(".\n")
                } else {
                    sb.append("ishlamadi (").append(r.code).append("): ").append(Journal.clean(r.message)).append("\n")
                }
            }
        }
        onDone(sb.toString().trim())
    }
}

private fun voiceOpenBatterySettings() {
    try {
        val i = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Store.app.startActivity(i)
    } catch (t: Throwable) {
        Ui.toast("Sozlamalarni ochib bo'lmadi. Telefon sozlamalarida ilovaning batareya cheklovini o'zingiz o'chiring.")
    }
}

// ---------------------------------------------------------------------- ovoz sozlamalari

@Composable
private fun VoiceSettingsScreen() {
    var checkText by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var chunkText by remember { mutableStateOf(Settings.vChunk.toString()) }
    var pagesText by remember { mutableStateOf(Settings.vOcrPages.toString()) }
    var windowText by remember { mutableStateOf(if (Settings.vOcrWindow > 0) Settings.vOcrWindow.toString() else "") }
    val keyCount = VPrefs.keys(Store.app).size
    val eng = VEngine.configured()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        TextButton(onClick = { Ui.sub = "" }) { Text("Orqaga: Ovoz bo'limi") }
        Heading("Ovoz sozlamalari")
        Hint("Audiolashtirish tanlangan ovoz dvigateli bilan bajariladi. O'zgarishlar keyingi boshlangan yoki davom ettirilgan ishlarga qo'llanadi.")

        SubHeading("Ovoz dvigateli")
        for (e in VEngine.ALL) {
            val title = when (e) {
                VEngine.GEMINI -> "Gemini ovozi (kalit kerak)"
                VEngine.MICROSOFT -> "Microsoft ovozi (kalitsiz)"
                VEngine.OPENAI -> "ChatGPT ovozi (OpenAI kaliti)"
                VEngine.XAI -> "Grok ovozi (xAI kaliti)"
                else -> "Qurilma ovozi (kalitsiz, internetsiz)"
            }
            Choice(title, eng == e, { Settings.vEngine = e })
        }
        Hint(VEngine.about(eng))
        if (VEngine.effective() != eng) {
            Hint(
                VEngine.name(eng) + " kaliti kiritilmagan: hozircha kalitsiz Microsoft ovozi ishlatiladi. " +
                    "Kalit qo'shish: Sozlamalar → API kalitlar."
            )
        }

        SubHeading("Ovoz")
        if (eng == VEngine.GEMINI) {
            Dropdown(
                "Ovoz",
                VPrefs.voiceLabel(VPrefs.voice(Store.app)),
                VPrefs.VOICE_NAMES.map { VPrefs.voiceLabel(it) }
            ) { picked -> Settings.vVoice = picked.substringBefore(" — ") }
        } else if (eng == VEngine.MICROSOFT) {
            Dropdown(
                "Ovoz",
                VEngine.voiceLabel(eng, VEngine.currentVoice(eng)),
                VEngine.MS_VOICES.map { it.label }
            ) { picked ->
                val v = VEngine.MS_VOICES.firstOrNull { it.label == picked }
                if (v != null) Settings.vMsVoice = v.id
            }
            Hint("Sardor va Madina — o'zbekcha ovozlar (kirill yozuvidagi matn avtomatik lotinga o'giriladi). Boshqa ovozlar o'sha tildagi matnlar uchun.")
        } else if (eng == VEngine.OPENAI) {
            Dropdown("Ovoz", VEngine.currentVoice(eng), VEngine.OPENAI_VOICES) { picked -> Settings.vOaiVoice = picked }
            Dropdown(
                "Model",
                if (Settings.vOaiModel.isEmpty()) "Avtomatik" else Settings.vOaiModel,
                listOf("Avtomatik", "gpt-4o-mini-tts", "tts-1", "tts-1-hd")
            ) { picked -> Settings.vOaiModel = if (picked == "Avtomatik") "" else picked }
            Hint("«Avtomatik» — avval gpt-4o-mini-tts, ishlamasa tts-1 ishlatiladi.")
        } else if (eng == VEngine.XAI) {
            Dropdown("Ovoz", VEngine.currentVoice(eng), VEngine.XAI_VOICES) { picked -> Settings.vXaiVoice = picked }
        } else {
            Hint("Telefonning o'z ovozi ishlatiladi. O'zbekcha ovoz kerak bo'lsa: telefon sozlamalari → matnni ovozga aylantirish (TTS) dvigateli va tilini tekshiring.")
        }
        if (eng == VEngine.GEMINI || eng == VEngine.OPENAI) {
            OutlinedTextField(
                value = Settings.vStyle,
                onValueChange = { Settings.vStyle = it },
                label = { Text("O'qish uslubi (ixtiyoriy)") },
                minLines = 1,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
            Hint(
                "Masalan: ravon, jiddiy, badiiy kitob ohangida. Bo'sh qoldirsangiz, standart uslub ishlatiladi." +
                    (if (eng == VEngine.OPENAI) " ChatGPT da faqat gpt-4o-mini-tts modelida ishlaydi." else "")
            )
        }
        if (eng != VEngine.GEMINI) {
            Dropdown(
                "O'qish tezligi",
                VEngine.rate().toString() + "%",
                VEngine.RATES.map { it.toString() + "%" }
            ) { picked -> Settings.vRate = picked.substringBefore("%").toIntOrNull() ?: 100 }
            if (eng == VEngine.OPENAI) {
                Hint("100% — oddiy tezlik. ChatGPT da tezlik tts-1 va tts-1-hd modellarida ishlaydi.")
            } else if (eng == VEngine.XAI) {
                Hint("Grok ovozida tezlik sozlanmaydi.")
            } else {
                Hint("100% — oddiy tezlik.")
            }
        }
        Btn("Ovozni sinab ko'rish", { Engine.testVoice() })
        Hint("Sinov ovozi shu yerda eshitiladi va «Suhbat» bo'limida ham ko'rinadi.")
        if (Player.current.isNotEmpty()) {
            OBtn("Ijroni to'xtatish", { Player.stop() })
        }

        SubHeading(if (eng == VEngine.GEMINI) "Modellar" else "Bo'lak hajmi")
        if (eng == VEngine.GEMINI) {
        OutlinedTextField(
            value = Settings.vModel,
            onValueChange = { Settings.vModel = it },
            label = { Text("Asosiy ovoz modeli") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Hint("Bo'sh qoldirsangiz, standart model ishlatiladi: " + VPrefs.DEFAULT_MODEL)
        OutlinedTextField(
            value = Settings.vBackup,
            onValueChange = { Settings.vBackup = it },
            label = { Text("Zaxira ovoz modeli (bo'sh = o'chiq)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        }
        OutlinedTextField(
            value = chunkText,
            onValueChange = { t ->
                val d = t.filter { it.isDigit() }.take(4)
                chunkText = d
                val n = d.toIntOrNull()
                if (n != null) Settings.vChunk = n
            },
            label = { Text("Bir so'rovdagi belgilar soni (800–6000)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        SubHeading("Skanerlangan kitoblar")
        Toggle(
            "Skanerlangan PDF sahifalarini AI bilan o'qish",
            Settings.vOcr,
            { Settings.vOcr = it },
            hint = "Matn qatlami yo'q (rasmli) sahifalar Gemini yordamida matnga aylantiriladi. Matnli sahifalar AI siz o'qiladi."
        )
        Toggle(
            "Butun fayl usuli (tavsiya)",
            Settings.vOcrWhole,
            { Settings.vOcrWhole = it },
            hint = "PDF Gemini'ga bir marta yuklanadi va har bir so'rovda ko'p sahifa o'qiladi: sahifa-sahifa so'rov yuborilmaydi, " +
                "kalit limiti tejaladi. Ishlamasa, avtomatik sahifa-rasm usuliga o'tiladi."
        )
        OutlinedTextField(
            value = windowText,
            onValueChange = { t ->
                val d = t.filter { it.isDigit() }.take(3)
                windowText = d
                Settings.vOcrWindow = d.toIntOrNull() ?: 0
            },
            label = { Text("Butun fayl usulida bir so'rovdagi sahifalar (bo'sh = avtomatik)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Hint("Avtomatik rejimda oraliq modelning javob hajmiga va sahifalardagi matn zichligiga qarab o'zi tanlanadi (eng ko'pi " + VPrefs.OCR_AUTO_MAX + " sahifa).")
        OutlinedTextField(
            value = Settings.vOcrModel,
            onValueChange = { Settings.vOcrModel = it },
            label = { Text("Sahifa o'qish modeli") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = Settings.vOcrBackup,
            onValueChange = { Settings.vOcrBackup = it },
            label = { Text("Zaxira sahifa o'qish modeli (bo'sh = o'chiq)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = pagesText,
            onValueChange = { t ->
                val d = t.filter { it.isDigit() }.take(2)
                pagesText = d
                val n = d.toIntOrNull()
                if (n != null) Settings.vOcrPages = n
            },
            label = { Text("Rasm usulida bir so'rovdagi sahifalar soni (1–20)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        SubHeading("Imlo tuzatish")
        Hint(
            "Audiolashtirishdan oldin matndagi aniq imlo va skaner xatolari AI yordamida tuzatiladi. Kitob mazmuni, so'zlari va uslubi " +
                "o'zgartirilmaydi: kitobdan chiqib ketgan yoki juda o'zgargan javob rad etilib, asl matn o'qiladi."
        )
        Choice("O'chiq", Settings.vSpell == VPrefs.SPELL_OFF, { Settings.vSpell = VPrefs.SPELL_OFF })
        Choice(
            "Faqat skaner qilingan kitoblarda (tavsiya)", Settings.vSpell == VPrefs.SPELL_SCANNED,
            { Settings.vSpell = VPrefs.SPELL_SCANNED }
        )
        Choice("Hamma matnda", Settings.vSpell == VPrefs.SPELL_ALL, { Settings.vSpell = VPrefs.SPELL_ALL })

        SubHeading("Kalitlar")
        Body(
            "Gemini kalitlari: " + keyCount + " ta; ChatGPT: " + VEngine.keyCount(VEngine.OPENAI) + " ta; Grok: " + VEngine.keyCount(VEngine.XAI) +
                " ta. Har bir xizmat uchun kamida " + Keys.MIN_SLOTS + " ta, eng ko'pi " + Keys.MAX + " ta kalit qo'shish va saqlash mumkin: Sozlamalar → API kalitlar."
        )
        Hint("Limit kalit bo'yicha emas, Google loyihasi bo'yicha hisoblanadi: bir loyihadagi kalitlar limitni bo'lishadi.")
        Btn("Kalitlarni tekshirish", {
            checking = true
            checkText = "Tekshirilmoqda..."
            voiceCheckKeys { r ->
                checkText = r
                checking = false
            }
        }, enabled = !checking)
        if (checkText.isNotEmpty()) {
            Text(checkText, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }

        SubHeading("Suhbatda ovoz")
        Toggle(
            "Har javobga ovoz qo'shish",
            Settings.ttsAuto,
            { Settings.ttsAuto = it },
            hint = "Suhbatdagi har bir javob avtomatik o'qib beriladi. Ekran o'quvchi bilan birga ishlatsangiz, ovozlar qo'shilib ketishi mumkin."
        )
        Toggle(
            "Ovoz rejimi",
            Settings.audioMode,
            { Settings.audioMode = it },
            hint = "Yoqilsa, suhbatga yozgan matningiz javob berilmasdan, to'g'ridan-to'g'ri o'qib beriladi."
        )

        SubHeading("Uzoq ishlar uchun")
        Hint("Katta kitobni audiolashtirish soatlab davom etadi. Ilova fonda uzilmasligi uchun batareya cheklovini o'chirib qo'ying.")
        OBtn("Batareya tejash sozlamalarini ochish", { voiceOpenBatterySettings() })
    }
}

// ---------------------------------------------------------------------- xato jurnali

@Composable
private fun VoiceJournalScreen() {
    var tick by remember { mutableStateOf(0) }
    var errs by remember { mutableStateOf("") }
    var all by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(tick) {
        val pair = withContext(Dispatchers.IO) {
            Pair(Journal.lastErrors(15).joinToString("\n"), Journal.readTail(20000))
        }
        errs = pair.first
        all = pair.second
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        TextButton(onClick = { Ui.sub = "" }) { Text("Orqaga: Ovoz bo'limi") }
        Heading("Xato jurnali")
        Hint(
            "Ilovadagi xatolar, ogohlantirishlar va audiolashtirish hodisalari shu yerga yoziladi. API kalitlar avtomatik yashiriladi. " +
                "Xatoni aniqlash uchun jurnalni ulashing yoki nusxa oling."
        )
        Body("Yozilgan xatolar soni: " + Journal.errorCount())
        Btn("Jurnalni ulashish", { voiceShareLog(false) })
        OBtn("Jurnalni saqlash (TXT)", { voiceShareLog(true) })
        OBtn("Ko'rsatilgan jurnalni nusxalash", { Platform.copy(all) })
        OBtn("Yangilash", { tick += 1 })
        OBtn("Sinov yozuvi qo'shish", {
            Journal.i("Sinov", "Sinov yozuvi: jurnal ishlayapti")
            tick += 1
        })
        OBtn("Jurnalni tozalash", { confirmClear = true })

        SubHeading("Oxirgi xatolar")
        Text(if (errs.isEmpty()) "Xato yozilmagan." else errs)
        SubHeading("Jurnalning oxirgi qismi")
        Text(if (all.isEmpty()) "Jurnal bo'sh." else all)
    }

    if (confirmClear) {
        ConfirmDialog(
            "Jurnalni tozalash",
            "Barcha jurnal yozuvlari o'chiriladi. Davom etasizmi?",
            "Ha, tozalansin",
            {
                Journal.clear()
                confirmClear = false
                tick += 1
            },
            { confirmClear = false }
        )
    }
}
