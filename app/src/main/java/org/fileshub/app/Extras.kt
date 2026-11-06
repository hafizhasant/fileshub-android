package org.fileshub.app

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.CircleShape as Circle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Dest {
    object Home : Dest
    class Browse(val dir: File) : Dest
    class Category(val c: Cat) : Dest
    object Bin : Dest
    class Search(val q: String) : Dest
    object Remotes : Dest
    class Net(val s: Server) : Dest
    class Settings(val page: Int) : Dest
    class Archive(val f: File) : Dest
    object Analyzer : Dest
}

object S {
    var p: SharedPreferences? = null
    var dark by mutableStateOf(true)
    var hidden by mutableStateOf(false)
    var foldersFirst by mutableStateOf(true)
    var sort by mutableStateOf("date")
    var startBrowse by mutableStateOf(false)
    var confirmDel by mutableStateOf(true)
    var useBin by mutableStateOf(true)
    var haptics by mutableStateOf(true)
    var glass by mutableStateOf(true)
    var homeStyle by mutableStateOf(0)
    var view by mutableStateOf("compact")
    var asc by mutableStateOf(false)
    var zoom by mutableStateOf(0.5f)
    var sections by mutableStateOf(true)
    var catsOpen by mutableStateOf(true)
    var recentOpen by mutableStateOf(true)
    var actionFab by mutableStateOf(false)
    var accent by mutableStateOf(0)
    var thumbImg by mutableStateOf(true)
    var thumbVid by mutableStateOf(true)
    var thumbPdf by mutableStateOf(true)
    var thumbApk by mutableStateOf(true)
    var thumbAud by mutableStateOf(true)
    fun load(c: Context) {
        val s = c.getSharedPreferences("fileshub", 0); p = s
        if (!s.getBoolean("d4", false)) s.edit().putString("vw", "compact").putString("sort", "date").putBoolean("asc", false).putBoolean("d4", true).apply()
        dark = s.getBoolean("dark", true); hidden = s.getBoolean("hidden", false); foldersFirst = s.getBoolean("ff", true)
        sort = s.getString("sort", "name") ?: "name"; startBrowse = s.getBoolean("sb", false)
        confirmDel = s.getBoolean("cd", true); useBin = s.getBoolean("ub", true)
        haptics = s.getBoolean("hp", true); glass = s.getBoolean("gl", true); homeStyle = s.getInt("hs", 0)
        view = s.getString("vw", "list") ?: "list"; asc = s.getBoolean("asc", true); zoom = s.getFloat("zm", 0.5f)
        sections = s.getBoolean("sec", true); catsOpen = s.getBoolean("co", true); recentOpen = s.getBoolean("ro", true); actionFab = s.getBoolean("afab", false); accent = s.getInt("acc", 0)
        thumbImg = s.getBoolean("t1", true); thumbVid = s.getBoolean("t2", true); thumbPdf = s.getBoolean("t3", true); thumbApk = s.getBoolean("t4", true); thumbAud = s.getBoolean("t5", true)
    }
    fun save() {
        p?.edit()?.putBoolean("dark", dark)?.putBoolean("hidden", hidden)?.putBoolean("ff", foldersFirst)?.putString("sort", sort)
            ?.putBoolean("sb", startBrowse)?.putBoolean("cd", confirmDel)?.putBoolean("ub", useBin)
            ?.putBoolean("hp", haptics)?.putBoolean("gl", glass)?.putInt("hs", homeStyle)?.putString("vw", view)
            ?.putBoolean("asc", asc)?.putFloat("zm", zoom)?.putBoolean("sec", sections)?.putBoolean("co", catsOpen)?.putBoolean("ro", recentOpen)?.putBoolean("afab", actionFab)?.putInt("acc", accent)?.putBoolean("t1", thumbImg)?.putBoolean("t2", thumbVid)?.putBoolean("t3", thumbPdf)?.putBoolean("t4", thumbApk)?.putBoolean("t5", thumbAud)?.apply()
    }
}

object Clip {
    var files by mutableStateOf(listOf<File>())
    var cut by mutableStateOf(false)
    fun set(f: List<File>, c: Boolean) { files = f; cut = c }
}

class Act(val label: String, val icon: ImageVector, val run: () -> Unit)

fun uriOf(ctx: Context, f: File) = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
fun mimeOf(f: File) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"

fun openWith(ctx: Context, f: File) {
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uriOf(ctx, f), mimeOf(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, "Open with"))
}

fun share(ctx: Context, f: File) {
    val i = Intent(Intent.ACTION_SEND).setType(mimeOf(f)).putExtra(Intent.EXTRA_STREAM, uriOf(ctx, f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, "Share"))
}

fun doDelete(ctx: Context, l: List<File>) = l.forEach { if (S.useBin) toBin(ctx, it) else it.deleteRecursively() }

fun pasteInto(dir: File, src: List<File>, cut: Boolean) {
    for (f in src) {
        var d = File(dir, f.name); var n = 1
        while (d.exists()) {
            d = File(dir, "${f.nameWithoutExtension} ($n)" + if (f.extension.isEmpty()) "" else "." + f.extension); n++
        }
        if (f.isDirectory && d.canonicalPath.startsWith(f.canonicalPath)) continue
        if (cut) { if (!f.renameTo(d)) { f.copyRecursively(d); f.deleteRecursively() } } else f.copyRecursively(d)
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, mod: Modifier = Modifier, onSubmit: () -> Unit = {}) {
    OutlinedTextField(
        value, onChange, mod.fillMaxWidth().glass(shape = Circle), singleLine = true, shape = Circle,
        placeholder = { Text("Search files") },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton({ onChange("") }) { Icon(Icons.Rounded.Close, "Clear") } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent, focusedBorderColor = MaterialTheme.colorScheme.primary.copy(.6f)
        )
    )
}

@Composable
fun SearchScreen(q0: String, onBack: () -> Unit, onDir: (File) -> Unit) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf(q0) }
    val res by produceState<List<File>?>(null, q) {
        if (q.isBlank()) value = emptyList() else {
            value = null; delay(300)
            value = withContext(Dispatchers.IO) {
                ROOT.walkTopDown().onEnter { it != BIN && !(it.parentFile == ROOT && it.name == "Android") }
                    .filter { it != ROOT && it.name.contains(q, true) && (S.hidden || !it.name.startsWith(".")) }.take(200).toList()
            }
        }
    }
    val list = res
    Column(Modifier.fillMaxSize()) {
        TopRow("Global search", onBack)
        SearchField(q, { q = it }, Modifier.padding(horizontal = 12.dp))
        if (list == null) Text("Searching…", Modifier.padding(24.dp).alpha(.6f))
        else if (list.isEmpty()) Text(if (q.isBlank()) "Type to search all storage" else "No matches", Modifier.padding(24.dp).alpha(.6f))
        else LazyColumn(contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 100.dp)) {
            items(list, key = { it.path }) { f -> FileRow(f, false, { if (f.isDirectory) onDir(f) else open(ctx, f) }, {}, where = (f.parent ?: "").removePrefix(ROOT.path).ifEmpty { "/" }) }
        }
    }
}

class Opt(val title: String, val value: () -> String, val tap: () -> Unit, val sw: Boolean = false)
class Sec(val title: String, val sub: String, val icon: ImageVector, val opts: List<Opt>)

fun sw(t: String, g: () -> Boolean, s: (Boolean) -> Unit) = Opt(t, { g().toString() }, { s(!g()); S.save() }, true)

fun sections() = listOf(
    Sec("General & Behavior", "Start screen and feedback", Icons.Rounded.Settings, listOf(
        sw("Open Browse on start", { S.startBrowse }, { S.startBrowse = it }),
        sw("Vibrate on tap", { S.haptics }, { S.haptics = it }))),
    Sec("Appearance & Themes", "Theme, home style and glass", Icons.Rounded.Palette, listOf(
        sw("Dark theme", { S.dark }, { S.dark = it }),
        Opt("Accent colour", { ACC[S.accent.coerceIn(0, ACC.lastIndex)].first }, { S.accent = (S.accent + 1) % ACC.size; S.save() }),
        Opt("Home style", { listOf("Minimal dark", "Enhanced detail", "List focus")[S.homeStyle] }, { S.homeStyle = (S.homeStyle + 1) % 3; S.save() }))),
    Sec("File Explorer Options", "Hidden files and browsing", Icons.Rounded.FolderOpen, listOf(
        sw("Show hidden files", { S.hidden }, { S.hidden = it }))),
    Sec("List & Layout Styling", "Default view and sorting", Icons.Rounded.TextFields, listOf(
        Opt("Default view", { S.view }, { S.view = when (S.view) { "list" -> "grid"; "grid" -> "gallery"; "gallery" -> "compact"; else -> "list" }; S.save() }),
        Opt("Default sort", { S.sort }, { S.sort = when (S.sort) { "name" -> "date"; "date" -> "size"; "size" -> "type"; else -> "name" }; S.asc = S.sort == "name" || S.sort == "type"; S.save() }),
        sw("Folders first", { S.foldersFirst }, { S.foldersFirst = it }))),
    Sec("Media Preferences", "Thumbnails and previews", Icons.Rounded.PermMedia, listOf(
        sw("Image thumbnails", { S.thumbImg }, { S.thumbImg = it }),
        sw("Video thumbnails", { S.thumbVid }, { S.thumbVid = it }),
        sw("PDF page previews", { S.thumbPdf }, { S.thumbPdf = it }),
        sw("App icons for APK files", { S.thumbApk }, { S.thumbApk = it }),
        sw("Audio cover art", { S.thumbAud }, { S.thumbAud = it }),
        Opt("Clear thumbnail cache", { "Tap to clear" }, { thumbs.evictAll() }))),
    Sec("File Actions & Viewers", "Confirmations when changing files", Icons.Rounded.Tune, listOf(
        sw("Collapse actions into a button", { S.actionFab }, { S.actionFab = it }),
        sw("Confirm before deleting", { S.confirmDel }, { S.confirmDel = it }))),
    Sec("Recycle Bin (Trash)", "What happens when you delete", Icons.Rounded.Delete, listOf(
        sw("Move deleted files to the bin", { S.useBin }, { S.useBin = it })))
)

@Composable
fun SettingsScreen(page: Int, onPage: (Int) -> Unit, onBack: () -> Unit) {
    val secs = sections()
    Column(Modifier.fillMaxSize()) {
        if (page < 0) {
            TopRow("More Settings", onBack)
            LazyColumn(contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(secs.size) { i ->
                    val s = secs[i]
                    Card(
                        Modifier.fillMaxWidth().glass(24.dp).clickable { onPage(i) }, RoundedCornerShape(24.dp),
                        CardDefaults.cardColors(containerColor = Color.Transparent)
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary.copy(.14f)), contentAlignment = Alignment.Center) {
                                Icon(s.icon, null, tint = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                Text(s.sub, fontSize = 14.sp, modifier = Modifier.alpha(.65f))
                            }
                            Text("›", fontSize = 24.sp, modifier = Modifier.alpha(.6f))
                        }
                    }
                }
            }
        } else {
            val s = secs[page]
            TopRow(s.title, onBack)
            if (s.opts.isEmpty()) Text("No options here yet.", Modifier.padding(24.dp).alpha(.6f))
            s.opts.forEach { o ->
                Row(Modifier.fillMaxWidth().clickable { o.tap() }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(o.title, Modifier.weight(1f), fontSize = 17.sp)
                    if (o.sw) Switch(o.value() == "true", { o.tap() })
                    else Text(o.value(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun DrawerItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) =
    NavigationDrawerItem({ Text(label) }, selected, onClick, Modifier.padding(vertical = 2.dp), icon = { Icon(icon, null) })

@Composable
fun DrawerContent(top: Dest, nav: (Dest) -> Unit) {
    val pri = MaterialTheme.colorScheme.primary
    ModalDrawerSheet(drawerContainerColor = if (S.dark) Color(0xF20E0E10) else Color(0xF2F3F8F7)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
            Surface(shape = RoundedCornerShape(28.dp), color = pri.copy(.15f), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(56.dp).clip(CircleShape).background(pri), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("FilesHub", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Text("Fast, private file manager", fontSize = 14.sp, modifier = Modifier.alpha(.7f))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            DrawerItem("Home", Icons.Rounded.Home, top is Dest.Home) { nav(Dest.Home) }
            DrawerItem("Internal Storage", Icons.Rounded.FolderOpen, (top as? Dest.Browse)?.dir == ROOT) { nav(Dest.Browse(ROOT)) }
            DrawerItem("System Root", Icons.Rounded.Storage, (top as? Dest.Browse)?.dir?.path == "/") { nav(Dest.Browse(File("/"))) }
            DrawerItem("Global Search", Icons.Rounded.Search, top is Dest.Search) { nav(Dest.Search("")) }
            DrawerItem("Recycle Bin", Icons.Rounded.Delete, top is Dest.Bin) { nav(Dest.Bin) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Servers & tools", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = pri, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            DrawerItem("FTP & SMB servers", Icons.Rounded.Cloud, top is Dest.Remotes || top is Dest.Net) { nav(Dest.Remotes) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Customization & settings", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = pri, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.LightMode, null)
                Spacer(Modifier.width(12.dp))
                Text("Light mode", Modifier.weight(1f))
                Switch(!S.dark, { S.dark = !it; S.save() })
            }
            DrawerItem("More Settings", Icons.Rounded.Settings, top is Dest.Settings) { nav(Dest.Settings(-1)) }
            Text("FilesHub v0.7.0", Modifier.fillMaxWidth().padding(16.dp).alpha(.5f), fontSize = 13.sp)
        }
    }
}

@Composable
fun AddServerDialog(onDismiss: () -> Unit, onSave: (Server) -> Unit) {
    var proto by remember { mutableStateOf("SMB") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var share by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Add server") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("SMB", "FTP").forEach { p -> FilterChip(proto == p, { proto = p }, { Text(p) }) }
                }
                OutlinedTextField(host, { host = it }, label = { Text("Host or IP") }, singleLine = true)
                OutlinedTextField(port, { port = it }, label = { Text("Port (optional)") }, singleLine = true)
                if (proto == "SMB") OutlinedTextField(share, { share = it }, label = { Text("Share name") }, singleLine = true)
                OutlinedTextField(user, { user = it }, label = { Text("Username (blank = anonymous)") }, singleLine = true)
                OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            }
        },
        confirmButton = {
            TextButton({
                if (host.isNotBlank() && (proto == "FTP" || share.isNotBlank()))
                    onSave(Server(System.currentTimeMillis().toString(), proto, host.trim(), port.toIntOrNull() ?: if (proto == "FTP") 21 else 445, user, pass, share.trim()))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ServersScreen(onBack: () -> Unit, onOpen: (Server) -> Unit) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(Servers.load(ctx)) }
    var adding by remember { mutableStateOf(false) }
    val pri = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxSize()) {
        TopRow("FTP & SMB servers", onBack) { IconButton({ adding = true }) { Icon(Icons.Rounded.Add, "Add server") } }
        if (list.isEmpty()) Text("No servers yet. Tap + to add an SMB share or FTP server.", Modifier.padding(24.dp).alpha(.6f))
        LazyColumn(contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, 100.dp)) {
            items(list, key = { it.id }) { s ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onOpen(s) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(pri.copy(.18f)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Cloud, null, tint = pri) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.host, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.proto + if (s.share.isNotEmpty()) " · " + s.share else "", fontSize = 12.sp, modifier = Modifier.alpha(.6f))
                    }
                    IconButton({ list = list - s; Servers.save(ctx, list) }) { Icon(Icons.Rounded.Delete, "Remove") }
                }
            }
        }
    }
    if (adding) AddServerDialog({ adding = false }) { list = list + it; Servers.save(ctx, list); adding = false }
}

@Composable
fun RemoteScreen(s: Server, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    val res by produceState<Result<List<RItem>>?>(null, path, tick) {
        value = null
        value = withContext(Dispatchers.IO) { runCatching { connect(s).use { it.list(path) } } }
    }
    BackHandler(enabled = path.isNotEmpty()) { path = path.substringBeforeLast('/', "") }
    val r = res
    val pri = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxSize()) {
        TopRow(if (path.isEmpty()) "${s.proto} · ${s.host}" else path.substringAfterLast('/'),
            { if (path.isEmpty()) onBack() else path = path.substringBeforeLast('/', "") }) {
            IconButton({ tick++ }) { Icon(Icons.Rounded.Refresh, "Refresh") }
        }
        if (r == null) Text("Connecting…", Modifier.padding(24.dp).alpha(.6f))
        else if (r.isFailure) Text("Couldn't connect: ${r.exceptionOrNull()?.message ?: "unknown error"}", Modifier.padding(24.dp))
        else {
            val items = r.getOrThrow().sortedWith(compareBy({ !it.dir }, { it.name.lowercase() }))
            if (items.isEmpty()) Text("This folder is empty", Modifier.padding(24.dp).alpha(.6f))
            LazyColumn(contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, 100.dp)) {
                items(items, key = { it.name }) { x ->
                    val full = if (path.isEmpty()) x.name else "$path/${x.name}"
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable {
                        if (x.dir) path = full else scope.launch {
                            val out = File(ROOT, "Download/${x.name}")
                            val e = withContext(Dispatchers.IO) { runCatching { out.parentFile?.mkdirs(); connect(s).use { it.download(full, out) } }.exceptionOrNull() }
                            if (e == null) { Toast.makeText(ctx, "Saved to Download", Toast.LENGTH_SHORT).show(); open(ctx, out) }
                            else Toast.makeText(ctx, e.message ?: "Download failed", Toast.LENGTH_LONG).show()
                        }
                    }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(pri.copy(.18f)), contentAlignment = Alignment.Center) {
                            Icon(if (x.dir) Icons.Rounded.Folder else Icons.Rounded.Description, null, tint = pri)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(x.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            if (!x.dir) Text(fmt(x.size), fontSize = 12.sp, modifier = Modifier.alpha(.6f))
                        }
                    }
                }
            }
        }
    }
}
