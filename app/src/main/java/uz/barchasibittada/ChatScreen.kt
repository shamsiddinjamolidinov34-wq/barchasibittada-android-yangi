package uz.barchasibittada

import android.content.Context
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private const val CHAT_SHOW_LIMIT = 6000

private fun chatShown(t: String): String {
    return if (t.length > CHAT_SHOW_LIMIT) {
        t.substring(0, CHAT_SHOW_LIMIT) + "\n\n… Matn uzun bo'lgani uchun boshi ko'rsatildi. To'liq matn faylda yoki «Amallar» orqali nusxa oling."
    } else {
        t
    }
}

@Composable
fun ChatScreen() {
    val ctx = LocalContext.current
    val listState = rememberLazyListState()
    val count = Ui.messages.size
    val lastLen = Ui.messages.lastOrNull()?.text?.length ?: 0
    // Telegram kabi: eng yangi xabar doim ekranning pastida; yangi xabar kelganda yoki oxirgi xabar uzayganda oxirigacha suriladi.
    LaunchedEffect(count, lastLen) {
        if (count > 0) {
            listState.scrollToItem(count - 1)
            listState.scrollBy(1000000f)
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        StatusBars()
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            // Xabarlar kam bo'lganda ham ro'yxat pastga (kiritish maydoni tomon) tekislanadi.
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom)
        ) {
            if (count == 0) {
                item { Welcome() }
            }
            items(Ui.messages, key = { it.id }) { m -> MessageItem(m) }
        }
        InputBar(ctx)
    }
}

@Composable
fun Welcome() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Heading("barchasibittada")
            Body("O'zbek tilida savol-javob, rasm, hujjat, audio va video tahlili, ovoz, kitob, maqola va test — hammasi bir joyda.")
            if (!Keys.any()) {
                Hint(
                    "Boshlash uchun Sozlamalar bo'limida API kalit qo'shing (Gemini, Claude, ChatGPT yoki Grok). " +
                        "Kalitsiz ham hujjat o'qish va lotin–kirill almashtirish ishlaydi."
                )
            }
            SubHeading(T.BOTS_TITLE)
            Hint(T.BOTS_TEXT)
            OBtn("@" + T.BOT_MAIN + " ni ochish", { Platform.openBot(T.BOT_MAIN) })
            OBtn("@" + T.BOT_SECOND + " ni ochish", { Platform.openBot(T.BOT_SECOND) })
        }
    }
}

@Composable
fun MessageItem(m: Msg) {
    val mine = m.role == "user"
    val who = if (m.role == "user") "Siz" else if (m.role == "system") "Ilova" else "Yordamchi"
    var menu by remember { mutableStateOf(false) }
    val container = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Card(colors = CardDefaults.cardColors(containerColor = container), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(who, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(chatShown(m.text), style = MaterialTheme.typography.bodyLarge)
            if (m.image != null) {
                Spacer(Modifier.height(8.dp))
                FileImage(m.image, "Yaratilgan rasm")
            }
            for (f in m.files) FileRow(f)
            if (m.role != "system" && m.text.isNotBlank()) {
                Box {
                    TextButton(onClick = { menu = true }) { Text("Amallar") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("O'qib berish") }, onClick = {
                            menu = false
                            Engine.readMessage(m.text)
                        })
                        DropdownMenuItem(text = { Text("Nusxa olish") }, onClick = {
                            menu = false
                            Platform.copy(m.text)
                        })
                        DropdownMenuItem(text = { Text("Ulashish") }, onClick = {
                            menu = false
                            Platform.shareText(m.text)
                        })
                        DropdownMenuItem(text = { Text("Fayl qilib saqlash") }, onClick = {
                            menu = false
                            Platform.saveAs(Files.saveText("xabar", m.text))
                        })
                        DropdownMenuItem(text = { Text("Lotin yozuviga o'girish") }, onClick = {
                            menu = false
                            Engine.messageAction("lotin", m.text)
                        })
                        DropdownMenuItem(text = { Text("Kirill yozuviga o'girish") }, onClick = {
                            menu = false
                            Engine.messageAction("kiril", m.text)
                        })
                        DropdownMenuItem(text = { Text("Tarjima qilish") }, onClick = {
                            menu = false
                            Engine.messageAction("tarjima", m.text)
                        })
                        DropdownMenuItem(text = { Text("Imlosini tuzatish") }, onClick = {
                            menu = false
                            Engine.messageAction("tahrir", m.text)
                        })
                        DropdownMenuItem(text = { Text("Konspekt qilish") }, onClick = {
                            menu = false
                            Engine.messageAction("konspekt", m.text)
                        })
                        DropdownMenuItem(text = { Text("Sodda tushuntirish") }, onClick = {
                            menu = false
                            Engine.messageAction("sodda", m.text)
                        })
                        DropdownMenuItem(text = { Text("Savollar tuzish") }, onClick = {
                            menu = false
                            Engine.messageAction("savol", m.text)
                        })
                    }
                }
            }
        }
    }
}

@Composable
fun InputBar(ctx: Context) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Toggle(
            "Internetdan qidirish",
            Settings.searchOn,
            { Settings.searchOn = it },
            hint = "Yangi ma'lumot kerak bo'lsa yoqing. Javobda manbalar ko'rsatiladi."
        )
        OutlinedTextField(
            value = Ui.input,
            onValueChange = { Ui.input = it },
            label = { Text("Savolingizni yozing") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 6
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        ) {
            Button(
                onClick = { Engine.send(Ui.input) },
                modifier = Modifier.weight(1f).heightIn(min = 52.dp)
            ) { Text("Yuborish") }
            OutlinedButton(
                onClick = {
                    if (Ui.recording) {
                        Engine.stopRecording()
                    } else {
                        Platform.requestMic(ctx) { granted ->
                            if (granted) Engine.startRecording() else Ui.toast("Mikrofon ruxsati berilmadi.")
                        }
                    }
                },
                modifier = Modifier.weight(1f).heightIn(min = 52.dp)
            ) { Text(if (Ui.recording) "Yozuvni tugatish" else "Ovoz yozish") }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        ) {
            OutlinedButton(
                onClick = { Platform.pickFiles(arrayOf("*/*")) { list -> Engine.onFiles(list) } },
                modifier = Modifier.weight(1f).heightIn(min = 52.dp)
            ) { Text("Biriktirish") }
            OutlinedButton(
                onClick = {
                    Platform.takePhoto(ctx) { p ->
                        if (p != null) Engine.onFiles(listOf(p)) else Ui.toast("Rasm olinmadi.")
                    }
                },
                modifier = Modifier.weight(1f).heightIn(min = 52.dp)
            ) { Text("Kamera") }
            TextButton(
                onClick = { Engine.clearChat() },
                modifier = Modifier.weight(1f).heightIn(min = 52.dp)
            ) { Text("Tozalash") }
        }
    }
}

/** Fayl tanlangach nima qilish kerakligini so'raydi. */
@Composable
fun PendingDialog() {
    val files = Ui.pending
    if (files.isEmpty()) return
    val p = files[0]
    val allImages = files.size > 1 && files.all { Kinds.of(it) == Kinds.IMAGE }
    AlertDialog(
        onDismissRequest = { Ui.pending = emptyList() },
        title = { Text(if (files.size > 1) "Fayllar: " + files.size + " ta. Hozir: " + p.name else "Fayl: " + p.name) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Nima qilamiz?")
                if (allImages) {
                    OutlinedButton(
                        onClick = {
                            val all = files
                            Ui.pending = emptyList()
                            Tools.bookAddImages(all)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Hammasini kitobga qo'shish") }
                }
                for (a in Engine.actionsFor(p)) {
                    OutlinedButton(
                        onClick = {
                            // Qolgan fayllar navbatda qoladi: birinchisi ishga tushgach, keyingisi uchun amal tanlanadi.
                            val rest = files.drop(1)
                            Engine.fileAction(p, a, "")
                            if (rest.isNotEmpty()) Ui.pending = rest
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(Engine.actionLabel(a).replaceFirstChar { it.uppercaseChar() }) }
                }
                if (files.size > 1) {
                    TextButton(onClick = { Ui.pending = files.drop(1) }) { Text("Bu faylni o'tkazib yuborish") }
                }
            }
        },
        confirmButton = { },
        dismissButton = { TextButton(onClick = { Ui.pending = emptyList() }) { Text("Bekor qilish") } }
    )
}
