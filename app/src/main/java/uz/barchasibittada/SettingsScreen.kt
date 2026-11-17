package uz.barchasibittada

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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

@Composable
fun SettingsScreen() {
    BackHandler(enabled = Ui.sub.startsWith("s_")) { Ui.sub = "" }
    if (Ui.sub == "s_models") {
        ModelsScreen()
        return
    }
    if (Ui.sub == "s_help") {
        HelpScreen()
        return
    }
    var keyFor by remember { mutableStateOf("") }
    var keySlot by remember { mutableStateOf(1) }
    var keyText by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Heading("Sozlamalar")

        SubHeading("API kalitlar")
        Hint(
            "O'z kalitlaringizni qo'shing. Kalit faqat shu telefonda, shifrlangan holda saqlanadi. " +
                "Har bir xizmat uchun kamida " + Keys.MIN_SLOTS + " ta (eng ko'pi " + Keys.MAX + " ta) kalit qo'shish va saqlash mumkin: " +
                    "biri limitga yetsa yoki ishlamasa, ilova keyingisiga o'tadi."
        )
        for (p in Prov.all) {
            KeyCard(p) { slot ->
                keyFor = p
                keySlot = slot
                keyText = ""
            }
        }

        SubHeading("Model")
        val cur = Ai.chooseModel()
        Body("Hozirgi model: " + (cur?.name ?: "tanlanmagan (avval kalit qo'shing)"))
        OBtn("Modelni tanlash", { Ui.sub = "s_models" })

        SubHeading("Javob sozlamalari")
        Toggle(
            "Internetdan qidirish",
            Settings.searchOn,
            { Settings.searchOn = it },
            hint = "Javobga eng yangi ma'lumotlar va manbalar qo'shiladi. Grok internet va X (Twitter) dan qidiradi."
        )
        Toggle(
            "Batafsil javob",
            Settings.detailOn,
            { Settings.detailOn = it },
            hint = "Yoqilsa, javoblar keng va to'liq bo'ladi. O'chirilsa, qisqa va aniq."
        )
        Dropdown(
            "Suhbat xotirasi", Settings.historyTurns.toString() + " juft xabar",
            listOf("0 juft xabar", "3 juft xabar", "6 juft xabar", "10 juft xabar")
        ) { Settings.historyTurns = it.substringBefore(' ').toIntOrNull() ?: 6 }
        Toggle(
            "18+ va diniy so'zlar filtri",
            Settings.taqiqOn,
            { Settings.taqiqOn = it },
            hint = "Telegram botidagi cheklov. Yoqilsa, bunday so'zlar bor savollarga javob berilmaydi. Ilovada sukut bo'yicha o'chiq."
        )

        SubHeading("Hisob")
        Body(
            "Bugun: " + Usage.today("savol") + " savol, " + Usage.today("fayl") + " fayl tahlili, " +
                Usage.today("ovoz") + " ovoz, " + Usage.today("rasm") + " rasm."
        )
        Hint("Ilovada kunlik chegara yo'q. To'lov faqat provayderning o'z tarifiga ko'ra olinadi.")
        val keys = Keys.withKeys().joinToString(", ") { Prov.name(it) + " (" + Keys.count(it) + " ta)" }
        Body("Ulangan kalitlar: " + (if (keys.isEmpty()) "yo'q" else keys))

        SubHeading(T.BOTS_TITLE)
        Hint(T.BOTS_TEXT)
        OBtn("@" + T.BOT_MAIN + " ni ochish", { Platform.openBot(T.BOT_MAIN) })
        OBtn("@" + T.BOT_SECOND + " ni ochish", { Platform.openBot(T.BOT_SECOND) })
        OBtn("Fikr va shikoyat (Telegram boti orqali)", { Platform.openBot(T.BOT_MAIN) })

        SubHeading("Yordam")
        OBtn("Yordam va qo'llanma", { Ui.sub = "s_help" })

        SubHeading("Ma'lumotlar")
        OBtn("Xato jurnali (" + Journal.errorCount() + " ta xato)", {
            Ui.tab = 2
            Ui.sub = "v_journal"
        })
        OBtn("Suhbatni tozalash", { confirm = "chat" })
        OBtn("Yaratilgan fayllarni tozalash", { confirm = "files" })
        OBtn("Kitob sahifalarini tozalash", { confirm = "book" })
        Hint("barchasibittada 1.0")
    }

    if (keyFor.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = {
                keyFor = ""
                keyText = ""
            },
            title = { Text(Prov.name(keyFor) + " kaliti, " + keySlot + "-o'rin") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Hint("Kalitni kiriting yoki buferdan qo'ying. Kalit olish: " + Prov.siteShort(keyFor))
                    OutlinedTextField(
                        value = keyText,
                        onValueChange = { keyText = it },
                        label = { Text("API kalit") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = { keyText = Platform.paste() }) { Text("Buferdan qo'yish") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    Engine.saveKey(keyFor, keySlot, keyText)
                    keyFor = ""
                    keyText = ""
                }) { Text("Tekshirish va saqlash") }
            },
            dismissButton = {
                TextButton(onClick = {
                    keyFor = ""
                    keyText = ""
                }) { Text("Bekor qilish") }
            }
        )
    }

    if (confirm.isNotEmpty()) {
        val what = when (confirm) {
            "chat" -> "Suhbat tarixi o'chiriladi."
            "files" -> "Yaratilgan audio, matn va rasm fayllari o'chiriladi."
            else -> "Kitobdagi hamma sahifa o'chiriladi."
        }
        ConfirmDialog(
            "Tasdiqlang", what + " Davom etasizmi?", "Ha",
            {
                when (confirm) {
                    "chat" -> Engine.clearChat()
                    "files" -> {
                        Player.stop()
                        Store.clearGenerated()
                        Ui.toolResults.clear()
                        Ui.toast("Fayllar tozalandi.")
                    }
                    else -> Book.clear()
                }
                confirm = ""
            },
            { confirm = "" }
        )
    }
}

@Composable
private fun KeyCard(p: String, onEdit: (Int) -> Unit) {
    val count = Keys.count(p)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(Prov.name(p), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(if (count > 0) "Ulangan kalitlar: " + count + " ta (eng ko'pi " + Keys.MAX + " ta)" else "Kalit qo'shilmagan")
            if (p == Prov.GEMINI) {
                Hint(
                    "Gemini kalitlari audiolashtirish va skanerlangan kitoblarni o'qish uchun ham ishlatiladi. " +
                        "Bir kalitning kunlik limiti tugasa, keyingi kalitga o'tiladi."
                )
            }
            if (p == Prov.XAI) {
                Hint(
                    "Grok kaliti qo'shilganda ochiladi: Grok 4.7 va boshqa modellar bilan savol-javob, internet va X qidiruvi, " +
                        "rasm tahlili, rasm yaratish va ovozni matnga aylantirish. Grok API kaliti «xai-» bilan boshlanadi."
                )
            }
            for (slot in 1..Keys.MAX) {
                if (Keys.getSlot(p, slot).isNotEmpty()) {
                    Text("Kalit " + slot + ": ulangan (" + Keys.hint(p, slot) + ")")
                    OBtn("Kalit " + slot + " ni almashtirish", { onEdit(slot) })
                    OBtn("Kalit " + slot + " ni o'chirish", { Engine.removeKey(p, slot) })
                }
            }
            val free = Keys.freeSlot(p)
            if (free > 0) {
                Btn(if (count == 0) "Kalit qo'shish" else "Yana kalit qo'shish (" + free + "-kalit)", { onEdit(free) })
            }
            val st = Ui.keyStatus[p]
            if (st != null) {
                Text(st, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            TextButton(onClick = { Platform.openUrl(Prov.site(p)) }) { Text("Kalit olish sahifasini ochish") }
        }
    }
}

@Composable
private fun ModelsScreen() {
    BackHandler { Ui.sub = "" }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TextButton(onClick = { Ui.sub = "" }) { Text("Orqaga: sozlamalar") }
        Heading("Modelni tanlash")
        Hint("Faqat kaliti qo'shilgan provayderlarning modellari tanlanadi.")
        Choice(
            "Avtomatik", Settings.modelId.isEmpty(), { Settings.modelId = "" },
            hint = "Kaliti bor birinchi provayderning asosiy modeli ishlatiladi."
        )
        for (p in Prov.all) {
            val has = Keys.has(p)
            SubHeading(Prov.name(p) + (if (has) "" else " (kalit kerak)"))
            for (m in Models.ofProvider(p)) {
                Choice(m.name, Settings.modelId == m.id, { Settings.modelId = m.id }, hint = m.note, enabled = has)
            }
        }
    }
}

@Composable
private fun HelpScreen() {
    BackHandler { Ui.sub = "" }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TextButton(onClick = { Ui.sub = "" }) { Text("Orqaga: sozlamalar") }
        Heading("Yordam")
        Body(T.HELP)
        SubHeading(T.BOTS_TITLE)
        OBtn("@" + T.BOT_MAIN + " ni ochish", { Platform.openBot(T.BOT_MAIN) })
        OBtn("@" + T.BOT_SECOND + " ni ochish", { Platform.openBot(T.BOT_SECOND) })
    }
}
