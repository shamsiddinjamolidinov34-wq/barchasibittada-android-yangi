package uz.barchasibittada

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Activity imkoniyatlariga ekranlardan chiqish ko'prigi. */
object Platform {
    var launchPick: (Array<String>) -> Unit = { }
    var launchPhoto: (Uri) -> Unit = { }
    var launchMic: () -> Unit = { }
    var launchSave: (String) -> Unit = { }

    private var pickCb: ((List<Picked>) -> Unit)? = null
    private var photoFile: File? = null
    private var photoCb: ((Picked?) -> Unit)? = null
    private var micCb: ((Boolean) -> Unit)? = null
    private var saveSource: File? = null

    fun pickFiles(mimes: Array<String>, cb: (List<Picked>) -> Unit) {
        pickCb = cb
        launchPick(mimes)
    }

    fun onPicked(uris: List<Uri>) {
        val cb = pickCb
        pickCb = null
        if (cb == null || uris.isEmpty()) return
        convert(uris, cb)
    }

    /** Boshqa ilovadan ulashilgan fayllar. */
    fun receive(uris: List<Uri>) {
        if (uris.isEmpty()) return
        convert(uris) { list -> Engine.onFiles(list) }
    }

    private fun convert(uris: List<Uri>, cb: (List<Picked>) -> Unit) {
        Engine.scope.launch {
            val list = withContext(Dispatchers.IO) {
                val out = ArrayList<Picked>()
                for (u in uris) {
                    val p = Files.fromUri(Store.app, u)
                    if (p != null) out.add(p)
                }
                out
            }
            if (list.isEmpty()) Ui.toast("Faylni o'qib bo'lmadi.") else cb(list)
        }
    }

    fun takePhoto(ctx: Context, cb: (Picked?) -> Unit) {
        try {
            val f = File(Store.inDir(), "foto-" + System.currentTimeMillis() + ".jpg")
            f.createNewFile()
            photoFile = f
            photoCb = cb
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            launchPhoto(uri)
        } catch (e: Exception) {
            Ui.toast("Kamerani ochib bo'lmadi.")
        }
    }

    fun onPhoto(ok: Boolean) {
        val f = photoFile
        val cb = photoCb
        photoFile = null
        photoCb = null
        if (cb == null) return
        if (ok && f != null && f.exists() && f.length() > 0L) {
            cb(Picked("foto.jpg", "image/jpeg", f))
        } else {
            cb(null)
        }
    }

    fun requestMic(ctx: Context, cb: (Boolean) -> Unit) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            cb(true)
            return
        }
        micCb = cb
        launchMic()
    }

    fun onMic(granted: Boolean) {
        val cb = micCb
        micCb = null
        cb?.invoke(granted)
    }

    fun saveAs(f: File) {
        if (!f.exists()) {
            Ui.toast("Fayl topilmadi.")
            return
        }
        saveSource = f
        launchSave(f.name)
    }

    fun onSaveUri(uri: Uri?) {
        val src = saveSource
        saveSource = null
        if (uri == null || src == null) return
        Engine.scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val out = Store.app.contentResolver.openOutputStream(uri)
                    if (out == null) {
                        false
                    } else {
                        out.use { o -> src.inputStream().use { i -> i.copyTo(o) } }
                        true
                    }
                } catch (e: Exception) {
                    false
                }
            }
            Ui.toast(if (ok) "Fayl saqlandi." else "Faylni saqlab bo'lmadi.")
        }
    }

    fun share(f: File, mime: String) {
        try {
            val ctx = Store.app
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            val i = Intent(Intent.ACTION_SEND)
            i.type = mime
            i.putExtra(Intent.EXTRA_STREAM, uri)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val chooser = Intent.createChooser(i, "Ulashish")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(chooser)
        } catch (e: Exception) {
            Ui.toast("Ulashib bo'lmadi.")
        }
    }

    fun shareText(text: String) {
        try {
            val ctx = Store.app
            val i = Intent(Intent.ACTION_SEND)
            i.type = "text/plain"
            i.putExtra(Intent.EXTRA_TEXT, text)
            val chooser = Intent.createChooser(i, "Ulashish")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(chooser)
        } catch (e: Exception) {
            Ui.toast("Ulashib bo'lmadi.")
        }
    }

    fun copy(text: String) {
        try {
            val cm = Store.app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("barchasibittada", text))
            Ui.toast("Nusxa olindi.")
        } catch (e: Exception) {
            Ui.toast("Nusxa olib bo'lmadi.")
        }
    }

    fun paste(): String {
        try {
            val cm = Store.app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                return clip.getItemAt(0).coerceToText(Store.app).toString()
            }
        } catch (e: Exception) {
            // e'tiborsiz
        }
        return ""
    }

    fun openUrl(url: String) {
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            Store.app.startActivity(i)
        } catch (e: Exception) {
            Ui.toast("Havolani ochib bo'lmadi.")
        }
    }

    /** Telegram ilovasida bot ochiladi; Telegram yo'q bo'lsa brauzerda. */
    fun openBot(name: String) {
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=" + name))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            Store.app.startActivity(i)
        } catch (e: Exception) {
            openUrl("https://t.me/" + name)
        }
    }
}

class MainActivity : ComponentActivity() {
    private val pickLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        Platform.onPicked(uris)
    }
    private val photoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok: Boolean ->
        Platform.onPhoto(ok)
    }
    private val micLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted: Boolean ->
        Platform.onMic(granted)
    }
    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _: Boolean -> }
    private val saveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        Platform.onSaveUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Platform.launchPick = { mimes -> pickLauncher.launch(mimes) }
        Platform.launchPhoto = { uri -> photoLauncher.launch(uri) }
        Platform.launchMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
        Platform.launchSave = { name -> saveLauncher.launch(name) }
        setContent {
            AppTheme { Root() }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    @Suppress("DEPRECATION")
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action == Intent.ACTION_SEND) {
            var stream: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            // Ba'zi ilovalar faylni faqat ClipData orqali beradi.
            if (stream == null) stream = clipUris(intent).firstOrNull()
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (stream != null) {
                Platform.receive(listOf(stream))
            } else if (!text.isNullOrBlank()) {
                Ui.input = text
                Ui.tab = 0
                Ui.sub = ""
            }
        } else if (action == Intent.ACTION_SEND_MULTIPLE) {
            val list: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            if (list != null && list.isNotEmpty()) {
                Platform.receive(list.toList())
            } else {
                Platform.receive(clipUris(intent))
            }
        } else if (action == Intent.ACTION_VIEW) {
            // «Ochish» (Open with): fayl menejeri yoki boshqa ilovadan tanlangan har qanday fayl shu ilovada ochiladi.
            val data = intent.data
            if (data != null) {
                Platform.receive(listOf(data))
            } else {
                Platform.receive(clipUris(intent))
            }
        }
        // Bir marta ishlangan ulashish qayta ishlanmasligi uchun
        setIntent(Intent(this, MainActivity::class.java))
    }

    private fun clipUris(intent: Intent): List<Uri> {
        val out = ArrayList<Uri>()
        val cd = intent.clipData ?: return out
        for (i in 0 until cd.itemCount) {
            val u = cd.getItemAt(i).uri
            if (u != null) out.add(u)
        }
        return out
    }
}

@Composable
fun Root() {
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Ui.toastText) {
        if (Ui.toastText.isNotEmpty()) {
            val msg = Ui.toastText
            snack.showSnackbar(msg)
            if (Ui.toastText == msg) Ui.toastText = ""
        }
    }
    val tabs = listOf("Suhbat", "Asboblar", "Ovoz", "Sozlamalar")
    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar {
                for (i in tabs.indices) {
                    NavigationBarItem(
                        selected = Ui.tab == i,
                        onClick = {
                            Ui.tab = i
                            Ui.sub = ""
                        },
                        icon = { },
                        label = { Text(tabs[i]) },
                        alwaysShowLabel = true
                    )
                }
            }
        }
    ) { pad ->
        Box(modifier = Modifier.padding(pad).fillMaxSize()) {
            when (Ui.tab) {
                0 -> ChatScreen()
                1 -> ToolsScreen()
                2 -> VoiceScreen()
                else -> SettingsScreen()
            }
            PendingDialog()
        }
    }
}
