package uz.barchasibittada

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class ToolDef(val id: String, val title: String, val desc: String)

private val TOOLS: List<ToolDef> = listOf(
    ToolDef("savol", "Savol tuzish", "Matndan o'quv savollari tuzadi."),
    ToolDef("konspekt", "Konspekt", "Matnni qisqa konspektga aylantiradi."),
    ToolDef("sodda", "Sodda tushuntirish", "Murakkab matnni oddiy tilda tushuntiradi."),
    ToolDef("tarjima", "Tarjima", "Matnni o'zbek, rus yoki ingliz tiliga tarjima qiladi."),
    ToolDef("test", "Test", "Mavzu yoki matn bo'yicha 10 savollik interaktiv test."),
    ToolDef("maqola", "Maqola yozish", "Mavzu bo'yicha reja tuzib, bo'lim-bo'lim maqola yozadi."),
    ToolDef("tahrir", "Imlo tuzatish", "Matn imlosini va tinish belgilarini to'g'rilaydi."),
    ToolDef("yozuv", "Lotin va kirill", "Matnni lotin yoki kirill yozuviga o'giradi. Kalit kerak emas."),
    ToolDef("rasm", "Rasm yaratish", "Tavsif bo'yicha rasm yaratadi (Gemini yoki Grok kaliti kerak)."),
    ToolDef("audio", "Matnni audiokitobga aylantirish", "Matn yoki fayldan (skanerlangan PDF ham) imlosi tuzatilgan ovozli kitob yaratadi."),
    ToolDef("kitob", "Kitob: sahifalarni o'qish", "Kamera yoki skanerlangan PDF sahifalarini matn va ovozga aylantiradi.")
)

@Composable
fun ToolsScreen() {
    BackHandler(enabled = Ui.sub.startsWith("t_")) { Ui.sub = "" }
    if (Ui.sub.startsWith("t_")) {
        val id = Ui.sub.removePrefix("t_")
        val def = TOOLS.firstOrNull { it.id == id }
        if (def != null) {
            ToolDetail(def)
            return
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Heading("Asboblar")
        StatusBars()
        for (t in TOOLS) {
            OutlinedButton(
                onClick = { Ui.sub = "t_" + t.id },
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                    Text(t.title, fontWeight = FontWeight.Bold)
                    Text(t.desc, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun pickTextFile(onResult: (String, String) -> Unit) {
    Platform.pickFiles(arrayOf("*/*")) { list ->
        val p = list[0]
        Engine.scope.launch {
            try {
                val t = withContext(Dispatchers.IO) { Docs.extract(p) }
                onResult(p.name, t)
            } catch (e: ScannedPdfException) {
                Ui.toast("Bu PDF skanerlangan. Kitob asbobidan foydalaning.")
            } catch (e: Throwable) {
                Ui.toast(Engine.friendly(e))
            }
        }
    }
}

@Composable
private fun ToolDetail(def: ToolDef) {
    var text by rememberSaveable(def.id) { mutableStateOf("") }
    var fileText by remember(def.id) { mutableStateOf("") }
    var fileName by remember(def.id) { mutableStateOf("") }
    var til by rememberSaveable(def.id) { mutableStateOf("") }
    val busy = Ui.jobStatus.isNotEmpty()
    val src = if (fileText.isNotEmpty()) fileText else text

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TextButton(onClick = { Ui.sub = "" }) { Text("Orqaga: asboblar ro'yxati") }
        Heading(def.title)
        Hint(def.desc)
        StatusBars()

        val usesFile = def.id == "savol" || def.id == "konspekt" || def.id == "sodda" || def.id == "tarjima" ||
            def.id == "tahrir" || def.id == "yozuv" || def.id == "test"
        val quiz = Ui.quiz

        if (def.id == "kitob") {
            BookTool()
        } else if (def.id == "test" && quiz != null) {
            QuizView(quiz)
        } else {
            val label = when (def.id) {
                "maqola" -> "Maqola mavzusi (masalan: Ona tili haqida 10 sahifali maqola)"
                "rasm" -> "Rasm tavsifi"
                "test" -> "Test mavzusi yoki matni"
                else -> "Matn yoki mavzu"
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 12
            )
            if (usesFile) {
                OBtn("Fayldan matn olish (PDF, Word, kitob, txt...)", {
                    pickTextFile { name, t ->
                        fileName = name
                        fileText = t
                    }
                })
                if (fileText.isNotEmpty()) {
                    Body("Fayl tanlandi: " + fileName + " (" + fileText.length + " belgi). Amal shu fayl matni ustida bajariladi.")
                    OBtn("Fayl tanlovini olib tashlash", {
                        fileName = ""
                        fileText = ""
                    })
                }
            }
            when (def.id) {
                "savol", "konspekt", "sodda" -> Btn("Bajarish", { Tools.study(def.id, src, "") }, enabled = !busy)
                "tarjima" -> {
                    SubHeading("Qaysi tilga")
                    Choice("Avtomatik (o'zbekcha bo'lsa ruschaga, boshqa tilda bo'lsa o'zbekchaga)", til == "", { til = "" })
                    Choice("O'zbek tiliga", til == "uz", { til = "uz" })
                    Choice("Rus tiliga", til == "ru", { til = "ru" })
                    Choice("Ingliz tiliga", til == "en", { til = "en" })
                    Btn("Tarjima qilish", { Tools.study("tarjima", src, til) }, enabled = !busy)
                }
                "test" -> Btn("Test tuzish", { Tools.quizFrom(src) }, enabled = !busy)
                "maqola" -> Btn("Maqola yozish", { Tools.maqola(text, "maqola") }, enabled = !busy)
                "tahrir" -> Btn("Imlosini tuzatish", { Tools.tahrir(src, "tahrir") }, enabled = !busy)
                "yozuv" -> {
                    Btn("Lotin yozuviga o'girish", { Tools.yozuv(src, true) })
                    OBtn("Kirill yozuviga o'girish", { Tools.yozuv(src, false) })
                }
                "rasm" -> Btn("Rasm yaratish", { Tools.rasm(text) }, enabled = !busy)
                "audio" -> {
                    Hint(
                        "Ovoz: Gemini (" + VPrefs.voiceLabel(VPrefs.voice(Store.app)) + "). Ovoz, o'qish uslubi va imlo tuzatish " +
                            "sozlamalari «Ovoz» bo'limida. Ish fonda bajariladi, jarayon va natija «Ovoz» bo'limida ko'rinadi."
                    )
                    Btn("Matnni ovozga aylantirish", { Tools.audioJob(text, "audio", "audio") }, enabled = text.isNotBlank())
                    SubHeading("Fayldan")
                    Hint(
                        "PDF (skanerlangan ham), Word, EPUB, FB2, TXT. Skanerlangan sahifalar AI bilan o'qiladi, " +
                            "imlosi kitob mazmuniga tayanib tuzatiladi va audiolashtiriladi."
                    )
                    OBtn("Fayl tanlab audiolashtirish", {
                        Platform.pickFiles(arrayOf("*/*")) { list -> Tools.audioFile(list[0], "audio") }
                    })
                }
            }
        }

        val r = Ui.toolResults[def.id]
        if (r != null) {
            Column(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                ResultCard(r, "Natija")
            }
        }
    }
}

@Composable
private fun QuizView(q: QuizState) {
    if (q.finished) {
        SubHeading("Test tugadi")
        Body("Natijangiz: " + q.correct + " / " + q.questions.size + " to'g'ri javob.")
        Btn("Yangi test", { Tools.quizReset() })
        return
    }
    val cur = q.questions[q.index]
    SubHeading("Savol " + (q.index + 1) + " / " + q.questions.size)
    Body(cur.savol)
    for (v in cur.variantlar) {
        OBtn(v.first + ") " + v.second, { Tools.quizPick(v.first) }, enabled = q.picked.isEmpty())
    }
    if (q.picked.isNotEmpty()) {
        val ok = q.picked == cur.javob
        Column(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) {
            Text(
                if (ok) "To'g'ri!" else "Noto'g'ri. To'g'ri javob: " + cur.javob,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        val last = q.index + 1 >= q.questions.size
        Btn(if (last) "Natijani ko'rish" else "Keyingi savol", { Tools.quizNext() })
    }
    OBtn("Testni to'xtatish", { Tools.quizReset() })
}

@Composable
private fun BookTool() {
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    val busy = Ui.jobStatus.isNotEmpty()
    Body("Kitobda hozir " + Ui.bookPages + " sahifa bor. Sahifalar telefonda saqlanadi.")
    Btn(
        "Kameradan sahifa olish",
        {
            Platform.takePhoto(ctx) { p ->
                if (p != null) Tools.bookAddImages(listOf(p)) else Ui.toast("Rasm olinmadi.")
            }
        },
        enabled = !busy
    )
    OBtn(
        "Galereyadan sahifalar tanlash",
        { Platform.pickFiles(arrayOf("image/*")) { list -> Tools.bookAddImages(list) } },
        enabled = !busy
    )
    OBtn(
        "Skanerlangan PDF dan o'qish",
        { Platform.pickFiles(arrayOf("application/pdf")) { list -> Tools.bookAddPdf(list[0]) } },
        enabled = !busy
    )
    SubHeading("Kitob bilan ishlash")
    OBtn("Matn faylini olish", { Tools.bookText() })
    OBtn("Imlosini tuzatish", { Tools.bookClean() }, enabled = !busy)
    OBtn("Ovozga aylantirish (audiokitob)", { Tools.bookAudio() }, enabled = !busy)
    OBtn("Oxirgi sahifani o'chirish", { Book.undo() })
    OBtn("Kitobni butunlay tozalash", { confirm = true })
    if (confirm) {
        ConfirmDialog(
            "Kitobni tozalash",
            "Kitobdagi hamma sahifa o'chiriladi. Davom etasizmi?",
            "Ha, tozalansin",
            {
                Book.clear()
                confirm = false
            },
            { confirm = false }
        )
    }
}
