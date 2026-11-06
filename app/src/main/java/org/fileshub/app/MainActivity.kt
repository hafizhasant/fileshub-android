package org.fileshub.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import kotlinx.coroutines.delay
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

val ROOT: File = Environment.getExternalStorageDirectory()
val BIN = File(ROOT, ".fileshub-bin")

enum class Cat(val label: String, val icon: ImageVector, val color: Color, val exts: List<String> = emptyList()) {
    Images("Images", Icons.Rounded.Image, Color(0xFFE040FB), "jpg jpeg png gif webp heic bmp".split(" ")),
    Videos("Videos", Icons.Rounded.Movie, Color(0xFFFF5252), "mp4 mkv mov avi webm 3gp".split(" ")),
    Audio("Audio", Icons.Rounded.MusicNote, Color(0xFFFFA726), "mp3 flac wav m4a ogg aac opus".split(" ")),
    Documents("Documents", Icons.Rounded.Description, Color(0xFF448AFF), "pdf doc docx txt md xls xlsx ppt pptx csv epub".split(" ")),
    Archives("Archives", Icons.Rounded.Archive, Color(0xFF64FFDA), "zip rar 7z tar gz xz".split(" ")),
    Downloads("Downloads", Icons.Rounded.Download, Color(0xFF69F0AE)),
    Apks("APKs", Icons.Rounded.Android, Color(0xFFFFD600), "apk apks xapk".split(" ")),
    Screenshots("Screenshots", Icons.Rounded.PhotoCamera, Color(0xFFFF4081)),
    Bin("Recycle Bin", Icons.Rounded.Delete, Color(0xFFFF5252));

    fun matches(f: File): Boolean = when (this) {
        Downloads -> f.path.startsWith("${ROOT.path}/Download/")
        Screenshots -> f.path.contains("/Screenshots/") && f.extension.lowercase() in Images.exts
        Bin -> false
        else -> f.extension.lowercase() in exts
    }
}

class Scan(val counts: Map<Cat, Int>, val sizes: Map<Cat, Long>, val recent: List<File>)

fun walk() = ROOT.walkTopDown()
    .onEnter { it != BIN && !(it.parentFile == ROOT && it.name == "Android") }
    .filter { it.isFile && !it.name.startsWith(".") }

fun doScan(): Scan {
    val counts = Cat.entries.associateWith { 0 }.toMutableMap()
    val sizes = Cat.entries.associateWith { 0L }.toMutableMap()
    val all = ArrayList<File>()
    for (f in walk()) {
        all.add(f)
        for (c in Cat.entries) if (c.matches(f)) { counts[c] = counts.getValue(c) + 1; sizes[c] = sizes.getValue(c) + f.length() }
    }
    counts[Cat.Bin] = BIN.list()?.count { it != ".nomedia" } ?: 0
    return Scan(counts, sizes, all.sortedByDescending { it.lastModified() }.take(10))
}

fun fmt(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f GB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
    b >= 1024 -> "${b / 1024} KB"
    else -> "$b B"
}

val dirCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()
fun sizeText(f: File) = if (f.isDirectory) (if (f is RootFile) "Folder" else "${dirCounts.getOrPut(f.path + f.lastModified()) { f.list()?.size ?: 0 }} items") else fmt(f.length())
private val DTF = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")
fun dateText(f: File): String = DTF.format(java.time.Instant.ofEpochMilli(f.lastModified()).atZone(java.time.ZoneId.systemDefault()))
fun sub(f: File) = sizeText(f) + " · " + dateText(f)

fun open(ctx: Context, f: File) {
    if (f.isFile && f.extension.equals("apk", true)) { Pending.apk = f; return }
    try {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (e: Exception) {
        Toast.makeText(ctx, "No app can open this file", Toast.LENGTH_SHORT).show()
    }
}

fun toBin(ctx: Context, f: File) {
    BIN.mkdirs(); File(BIN, ".nomedia").createNewFile()
    val id = "${System.currentTimeMillis()}_${f.name}"
    if (f.renameTo(File(BIN, id))) ctx.getSharedPreferences("fileshub", 0).edit().putString(id, f.path).apply()
}

fun restore(ctx: Context, f: File) {
    val p = ctx.getSharedPreferences("fileshub", 0)
    var dest = File(p.getString(f.name, null) ?: File(ROOT, "Restored/${f.name.substringAfter('_')}").path)
    dest.parentFile?.mkdirs()
    if (dest.exists()) dest = File(dest.parent ?: ROOT.path, "restored_" + dest.name)
    f.renameTo(dest); p.edit().remove(f.name).apply()
}

class MainActivity : ComponentActivity() {
    private val tick = mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        runCatching {
            val best = display?.supportedModes?.maxByOrNull { it.refreshRate }
            if (best != null) window.attributes = window.attributes.also { it.preferredRefreshRate = best.refreshRate }
        }
        runCatching { val old = File(ROOT, ".cairn-bin"); if (old.exists() && !BIN.exists()) old.renameTo(BIN) }
        val crash = File(filesDir, "crash.txt")
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            runCatching { crash.writeText(e.stackTraceToString()) }
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        val prev = if (crash.exists()) runCatching { crash.readText() }.getOrNull() else null
        setContent {
            var shown by remember { mutableStateOf(prev) }
            val t = shown
            if (t != null) CrashScreen(t) { crash.delete(); shown = null } else App(tick.intValue, intent?.getStringExtra("path"))
        }
    }
    override fun onResume() { super.onResume(); tick.intValue++ }

    private var dx = 0f
    private var dy = 0f
    private var dt = 0L
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (S.haptics) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) { dx = e.x; dy = e.y; dt = e.eventTime }
            else if (e.actionMasked == MotionEvent.ACTION_UP && e.eventTime - dt < 250 && Math.abs(e.x - dx) < 24 && Math.abs(e.y - dy) < 24)
                window.decorView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
        return super.dispatchTouchEvent(e)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(tick: Int, startDir: String? = null) {
    val ctx = LocalContext.current
    remember { S.load(ctx) }
    val granted = remember(tick) { Environment.isExternalStorageManager() }
    var rev by remember { mutableIntStateOf(0) }
    var stack by remember { mutableStateOf(if (startDir != null) listOf<Dest>(Dest.Home, Dest.Browse(File(startDir))) else listOf<Dest>(if (S.startBrowse) Dest.Browse(ROOT) else Dest.Home)) }
    val scan by produceState<Scan?>(null, granted, rev) {
        if (granted) value = withContext(Dispatchers.IO) { doScan() }
    }
    val ds = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    fun go(d: Dest) { stack = stack + d }
    fun back() { stack = stack.dropLast(1) }
    fun nav(d: Dest) {
        stack = if (d is Dest.Home || (d is Dest.Browse && d.dir == ROOT)) listOf(d) else listOf(Dest.Home, d)
        scope.launch { ds.close() }
    }
    BackHandler(enabled = stack.size > 1) {
        if (System.currentTimeMillis() < UiState.backGuard) { UiState.backGuard = 0L; toast(ctx, "Swipe back again to go up a folder") } else back()
    }
    BackHandler(enabled = ds.isOpen) { scope.launch { ds.close() } }
    val top = stack.last()
    var refreshing by remember { mutableStateOf(false) }
    (ctx as? android.app.Activity)?.window?.let { w ->
        SideEffect {
            val c = androidx.core.view.WindowCompat.getInsetsController(w, w.decorView)
            c.isAppearanceLightStatusBars = !S.dark; c.isAppearanceLightNavigationBars = !S.dark
        }
    }
    val scheme = appScheme(ctx)
    MaterialTheme(colorScheme = scheme) {
        Pending.apk?.let { InstallDialog(it) { Pending.apk = null } }
        Box(Modifier.fillMaxSize().background(bgBrush())) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        ModalNavigationDrawer(drawerState = ds, drawerContent = { DrawerContent(top) { nav(it) } }) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().statusBarsPadding()) {
                    if (!granted) Perm() else when (val d = top) {
                        is Dest.Home -> PullToRefreshBox(refreshing, { scope.launch { refreshing = true; rev++; delay(700); refreshing = false } }, Modifier.fillMaxSize()) {
                            Home(scan, { scope.launch { ds.open() } }, { rev++ }, { go(Dest.Search(it)) }, { go(Dest.Browse(it)) }, { go(Dest.Analyzer) }) { go(Dest.Category(it)) }
                        }
                        is Dest.Category -> if (d.c == Cat.Bin) BinScreen(rev, { rev++ }) { back() } else CatScreen(d.c) { back() }
                        is Dest.Browse -> Browse(d.dir, stack.getOrNull(stack.size - 2) is Dest.Browse, rev, { rev++ }, { go(Dest.Browse(it)) }, { back() }, { scope.launch { ds.open() } }, { go(Dest.Archive(it)) }, { nav(Dest.Browse(it)) })
                        is Dest.Archive -> ArchiveScreen(d.f) { back() }
                        is Dest.Analyzer -> AnalyzerScreen(scan, { back() }) { go(Dest.Browse(it)) }
                        is Dest.Bin -> BinScreen(rev, { rev++ }) { back() }
                        is Dest.Search -> SearchScreen(d.q, { back() }) { go(Dest.Browse(it)) }
                        is Dest.Remotes -> ServersScreen({ back() }) { go(Dest.Net(it)) }
                        is Dest.Net -> RemoteScreen(d.s) { back() }
                        is Dest.Settings -> SettingsScreen(d.page, { go(Dest.Settings(it)) }) { back() }
                    }
                }
                if (!UiState.hideNav) FloatingNav(top, { nav(Dest.Home) }, { nav(Dest.Browse(ROOT)) }, { nav(Dest.Search("")) }, { rev++ }, { scope.launch { ds.open() } }, Modifier.align(Alignment.BottomCenter))
            }
        }
        }
        }
    }
}

@Composable
fun CrashScreen(text: String, onDone: () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.statusBarsPadding().padding(16.dp)) {
                Text("FilesHub crashed last time", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(text.take(3500), fontSize = 10.sp, lineHeight = 13.sp, modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp))
                Button(onDone) { Text("Continue") }
            }
        }
    }
}

@Composable
fun Perm() {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.Folder, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Allow access to all files", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("FilesHub needs this to browse and manage your storage. FilesHub only uses the network when you open an FTP or SMB server that you added yourself.", textAlign = TextAlign.Center, modifier = Modifier.alpha(.7f))
        Spacer(Modifier.height(24.dp))
        Button({ ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))) }) { Text("Open settings") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(f: File, sel: Boolean, onClick: () -> Unit, onLong: () -> Unit, name: String = f.name, menu: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (sel) MaterialTheme.colorScheme.primary.copy(.22f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLong).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FileIcon(f, 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Row(Modifier.fillMaxWidth()) {
                Text(sizeText(f), fontSize = 12.sp, color = LocalContentColor.current.copy(alpha = .6f), modifier = Modifier.weight(1f))
                Text(dateText(f), fontSize = 12.sp, color = LocalContentColor.current.copy(alpha = .6f))
            }
        }
        menu?.invoke()
    }
}

@Composable
fun TopRow(title: String, onBack: (() -> Unit)?, icon: ImageVector = Icons.AutoMirrored.Rounded.ArrowBack, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onBack) { Icon(icon, "Back") } else Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
fun CatScreen(c: Cat, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val files by produceState<List<File>?>(null, c) {
        value = withContext(Dispatchers.IO) { walk().filter { c.matches(it) }.sortedByDescending { it.lastModified() }.take(500).toList() }
    }
    val list = files
    Column(Modifier.fillMaxSize()) {
        TopRow(c.label, onBack)
        if (list == null) Text("Loading…", modifier = Modifier.padding(24.dp).alpha(.6f))
        else if (list.isEmpty()) Text("Nothing here yet", modifier = Modifier.padding(24.dp).alpha(.6f))
        else LazyColumn(contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, 100.dp)) { items(list, key = { it.path }) { f -> FileRow(f, false, { open(ctx, f) }, {}) } }
    }
}

@Composable
fun BinScreen(rev: Int, bump: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val list = remember(rev) { BIN.listFiles()?.filter { it.name != ".nomedia" }?.sortedByDescending { it.lastModified() } ?: emptyList() }
    Column(Modifier.fillMaxSize()) {
        TopRow("Recycle Bin", onBack) {
            if (list.isNotEmpty()) TextButton({ list.forEach { it.deleteRecursively() }; bump() }) { Text("Empty") }
        }
        if (list.isEmpty()) Text("The bin is empty", modifier = Modifier.padding(24.dp).alpha(.6f))
        LazyColumn(contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, 100.dp)) {
            items(list, key = { it.path }) { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { FileRow(f, false, {}, {}, f.name.substringAfter('_')) }
                    IconButton({ restore(ctx, f); bump() }) { Icon(Icons.Rounded.Restore, "Restore") }
                    IconButton({ f.deleteRecursively(); bump() }) { Icon(Icons.Rounded.DeleteForever, "Delete forever") }
                }
            }
        }
    }
}
