package uz.barchasibittada

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B5E4B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEBDD),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF3F6B5C),
    background = Color(0xFFFBFDF9),
    onBackground = Color(0xFF191C1A),
    surface = Color(0xFFFBFDF9),
    onSurface = Color(0xFF191C1A),
    surfaceVariant = Color(0xFFE1EAE3),
    onSurfaceVariant = Color(0xFF2F3A34),
    error = Color(0xFFBA1A1A)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD6B8),
    onPrimary = Color(0xFF00382B),
    primaryContainer = Color(0xFF0B4F3E),
    onPrimaryContainer = Color(0xFFCDEBDD),
    secondary = Color(0xFFA6D0C0),
    background = Color(0xFF101412),
    onBackground = Color(0xFFE1E3DF),
    surface = Color(0xFF101412),
    onSurface = Color(0xFFE1E3DF),
    surfaceVariant = Color(0xFF26302B),
    onSurfaceVariant = Color(0xFFC6D0C9),
    error = Color(0xFFFFB4AB)
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = modifier.semantics { heading() }
    )
}

@Composable
fun SubHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = 8.dp).semantics { heading() }
    )
}

@Composable
fun Body(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = modifier)
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

@Composable
fun Btn(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp)
    ) { Text(label) }
}

@Composable
fun OBtn(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp)
    ) { Text(label) }
}

@Composable
fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit, hint: String = "") {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (hint.isNotEmpty()) Hint(hint)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
fun Choice(label: String, selected: Boolean, onClick: () -> Unit, hint: String = "", enabled: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (hint.isNotEmpty()) Hint(hint)
        }
    }
}

/** Ochiladigan ro'yxat: joriy qiymat tugmada ko'rinadi. */
@Composable
fun Dropdown(label: String, current: String, options: List<String>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        OBtn(label + ": " + current, { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (o in options) {
                DropdownMenuItem(text = { Text(o) }, onClick = {
                    open = false
                    onSelect(o)
                })
            }
        }
    }
}

/** Suhbat va asboblar tepasidagi holat satrlari (bekor qilish tugmasi bilan). */
@Composable
fun StatusBars() {
    if (Ui.chatStatus.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            Text(
                Ui.chatStatus,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp).semantics { liveRegion = LiveRegionMode.Polite }
            )
            TextButton(onClick = { Engine.cancelChat() }) { Text("Bekor qilish") }
        }
    }
    if (Ui.jobStatus.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            Text(
                Ui.jobStatus,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp).semantics { liveRegion = LiveRegionMode.Polite }
            )
            TextButton(onClick = { Engine.cancelJob() }) { Text("Ishni bekor qilish") }
        }
    }
}

@Composable
fun FileImage(path: String, description: String) {
    val bmp = remember(path) {
        try {
            val o = BitmapFactory.Options()
            o.inSampleSize = 2
            BitmapFactory.decodeFile(path, o)
        } catch (e: Throwable) {
            null
        }
    }
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = description,
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
fun FileRow(f: OutFile) {
    val isAudio = f.mime.startsWith("audio/")
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text("Fayl: " + f.name, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (isAudio) {
                val playing = Player.current == f.path
                OutlinedButton(onClick = { Player.toggle(f.path) }) { Text(if (playing) "To'xtatish" else "Eshitish") }
            }
            OutlinedButton(onClick = { Platform.share(File(f.path), f.mime) }) { Text("Ulashish") }
            OutlinedButton(onClick = { Platform.saveAs(File(f.path)) }) { Text("Saqlash") }
        }
    }
}

private const val SHOW_LIMIT = 6000

/** Natija kartochkasi: matn, rasm, fayllar va amallar (suhbat va asboblarda bir xil). */
@Composable
fun ResultCard(m: Msg, label: String = "Natija") {
    val shown = if (m.text.length > SHOW_LIMIT) {
        m.text.substring(0, SHOW_LIMIT) + "\n\n… Matn uzun bo'lgani uchun boshi ko'rsatildi. To'liq matn faylda."
    } else {
        m.text
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(shown, style = MaterialTheme.typography.bodyLarge)
            if (m.image != null) {
                Spacer(Modifier.height(8.dp))
                FileImage(m.image, "Yaratilgan rasm")
            }
            for (f in m.files) FileRow(f)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedButton(onClick = { Platform.copy(m.text) }) { Text("Nusxa olish") }
                OutlinedButton(onClick = { Platform.shareText(m.text) }) { Text("Ulashish") }
            }
        }
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, yes: String, onYes: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onYes) { Text(yes) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Bekor qilish") } }
    )
}

@Composable
fun Divider2() {
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
}
