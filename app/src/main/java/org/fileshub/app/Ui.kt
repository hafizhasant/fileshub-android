package org.fileshub.app

import android.content.Context
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import kotlinx.coroutines.delay
import android.view.HapticFeedbackConstants
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.activity.compose.BackHandler
import android.graphics.BitmapFactory
import android.os.StatFs
import android.text.format.DateUtils
import android.util.LruCache
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// ---------- frosted glass look ----------
fun Modifier.glass(r: Dp = 24.dp, shape: Shape = RoundedCornerShape(r)): Modifier =
    clip(shape).background(if (S.dark) Color(0xFF1E1E1E) else Color(0xFFFFFFFF))

fun bgBrush(): Brush = if (S.dark) Brush.verticalGradient(listOf(Color(0xFF151515), Color(0xFF151515)))
else Brush.verticalGradient(listOf(Color(0xFFF2F4F6), Color(0xFFF2F4F6)))

object UiState { var hideNav by mutableStateOf(false); var backGuard = 0L }

@Composable
fun Modifier.blurBar(shape: Shape, blur: Boolean = true): Modifier {
    val dark = S.dark
    return clip(shape).border(1.dp, (if (dark) Color.White else Color.Black).copy(.08f), shape)
        .background(if (dark) Color(0xF01E1E1E) else Color(0xF0FFFFFF))
}

@Composable
fun FloatingNav(top: Dest, onHome: () -> Unit, onBrowse: () -> Unit, onSearch: () -> Unit, onRefresh: () -> Unit, onMenu: () -> Unit, mod: Modifier) {
    val pri = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.onSurface.copy(.8f)
    Row(mod.navigationBarsPadding().padding(bottom = 10.dp).blurBar(CircleShape).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        listOf(
            Triple(Icons.Rounded.Home, "Home", top is Dest.Home) to onHome,
            Triple(Icons.Rounded.Folder, "Browse", top is Dest.Browse) to onBrowse,
            Triple(Icons.Rounded.Search, "Search", top is Dest.Search) to onSearch,
            Triple(Icons.Rounded.Refresh, "Refresh", false) to onRefresh,
            Triple(Icons.Rounded.Menu, "Menu", false) to onMenu
        ).forEach { (t, click) ->
            val (icon, label, on) = t
            Column(
                Modifier.clip(CircleShape).background(if (on) pri.copy(.2f) else Color.Transparent).clickable { click() }.padding(horizontal = 13.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(icon, label, tint = if (on) pri else off)
                Text(label, fontSize = 11.sp, color = if (on) pri else off)
            }
        }
    }
}

fun toast(c: Context, s: String) = Toast.makeText(c, s, Toast.LENGTH_SHORT).show()

// ---------- per-folder view and sort ----------
data class VM(val view: String, val sort: String, val asc: Boolean, val zoom: Float, val sections: Boolean)

fun vmFor(dir: File): VM {
    val v = S.p?.getString("vm:" + dir.path, null)?.split("|")
    return if (v != null && v.size == 5) VM(v[0], v[1], v[2] == "1", v[3].toFloatOrNull() ?: .5f, v[4] == "1")
    else VM(S.view, S.sort, S.asc, S.zoom, S.sections)
}

fun saveVm(dir: File, m: VM, only: Boolean) {
    val e = S.p?.edit() ?: return
    if (only) e.putString("vm:" + dir.path, "${m.view}|${m.sort}|${if (m.asc) 1 else 0}|${m.zoom}|${if (m.sections) 1 else 0}").apply()
    else { e.remove("vm:" + dir.path).apply(); S.view = m.view; S.sort = m.sort; S.asc = m.asc; S.zoom = m.zoom; S.sections = m.sections; S.save() }
}

fun listDir(dir: File, q: String, m: VM): List<File> {
    val base: Comparator<File> = when (m.sort) {
        "date" -> compareBy<File> { it.lastModified() }
        "size" -> compareBy<File> { if (it.isDirectory) 0L else it.length() }
        "type" -> compareBy<File> { it.extension.lowercase() }.thenBy { it.name.lowercase() }
        else -> compareBy<File> { it.name.lowercase() }
    }
    val cmp = if (m.asc) base else base.reversed()
    var raw: List<File>? = dir.listFiles()?.toList()
    if (raw.isNullOrEmpty() && isRestricted(dir)) raw = rootList(dir) ?: raw
    val l = (raw ?: emptyList()).filter { (S.hidden || !it.name.startsWith(".")) && it.name.contains(q, true) }
    return if (S.foldersFirst) l.sortedWith(compareBy<File> { !it.isDirectory }.then(cmp)) else l.sortedWith(cmp)
}

fun sectionOf(f: File, m: VM): String? = when (m.sort) {
    "date" -> when {
        DateUtils.isToday(f.lastModified()) -> "Today"
        DateUtils.isToday(f.lastModified() + 86400000L) -> "Yesterday"
        else -> SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(Date(f.lastModified()))
    }
    "name" -> f.name.firstOrNull()?.uppercaseChar()?.let { if (it.isLetter()) it.toString() else "#" } ?: "#"
    "type" -> if (f.isDirectory) "Folders" else f.extension.lowercase().ifEmpty { "no extension" }.uppercase()
    else -> null
}

// ---------- archives ----------
val ARCH = listOf(".tar.gz", ".tgz", ".zip", ".tar", ".7z")
fun archExt(f: File) = ARCH.firstOrNull { f.name.lowercase().endsWith(it) }
fun isArchive(f: File) = f.isFile && archExt(f) != null

fun uniq(f: File): File {
    if (!f.exists()) return f
    var n = 1; var d: File
    do { d = File(f.parentFile, "${f.nameWithoutExtension} ($n)" + (if (f.extension.isEmpty()) "" else "." + f.extension)); n++ } while (d.exists())
    return d
}

fun zipTo(files: List<File>, out: File) {
    ZipOutputStream(out.outputStream().buffered()).use { z ->
        fun add(f: File, path: String) {
            if (f.isDirectory) {
                z.putNextEntry(ZipEntry("$path/")); z.closeEntry()
                f.listFiles()?.forEach { add(it, "$path/${it.name}") }
            } else { z.putNextEntry(ZipEntry(path)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
        }
        files.forEach { add(it, it.name) }
    }
}

fun extract(f: File, dest: File) {
    dest.mkdirs()
    val root = dest.canonicalPath + File.separator
    fun put(name: String, dir: Boolean, write: (java.io.OutputStream) -> Unit) {
        val o = File(dest, name)
        require(o.canonicalPath.startsWith(root)) { "Unsafe path in archive" }
        if (dir) o.mkdirs() else { o.parentFile?.mkdirs(); o.outputStream().use(write) }
    }
    when (archExt(f)) {
        ".zip" -> ZipInputStream(f.inputStream().buffered()).use { z ->
            generateSequence { z.nextEntry }.forEach { e -> put(e.name, e.isDirectory) { z.copyTo(it) } }
        }
        ".tar", ".tgz", ".tar.gz" -> {
            val raw = f.inputStream().buffered()
            val src = if (archExt(f) == ".tar") raw else GzipCompressorInputStream(raw)
            TarArchiveInputStream(src).use { t -> generateSequence { t.nextEntry }.forEach { e -> put(e.name, e.isDirectory) { t.copyTo(it) } } }
        }
        ".7z" -> SevenZFile.builder().setFile(f).get().use { s ->
            generateSequence { s.nextEntry }.forEach { e ->
                put(e.name, e.isDirectory) { out -> val b = ByteArray(8192); while (true) { val r = s.read(b); if (r < 0) break; out.write(b, 0, r) } }
            }
        }
        else -> error("Unsupported archive")
    }
}

// ---------- thumbnails ----------
val thumbs = LruCache<String, ImageBitmap>(300)

fun decodeThumb(f: File): ImageBitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    var s = 1
    while (o.outWidth / s > 256 || o.outHeight / s > 256) s *= 2
    return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
}

@Composable
fun Thumb(f: File, mod: Modifier, fallback: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val key = f.path + f.lastModified()
    val bmp by produceState<ImageBitmap?>(thumbs.get(key), key) {
        if (value == null) value = withContext(Dispatchers.IO) { thumbSem.acquire(); try { runCatching { makeThumb(ctx, f) }.getOrNull()?.also { thumbs.put(key, it) } } finally { thumbSem.release() } }
    }
    val b = bmp
    if (b != null) Image(b, null, mod, contentScale = ContentScale.Crop) else fallback()
}

val EXTL: Map<String, Pair<ImageVector, Color>> = run {
    fun ex(vararg e: String, v: ImageVector, c: Color) = e.map { it to (v to c) }
    (ex("pdf", v = Icons.Rounded.PictureAsPdf, c = Color(0xFFE53935)) +
        ex("doc", "docx", "odt", "rtf", v = Icons.Rounded.Description, c = Color(0xFF2B7CD3)) +
        ex("xls", "xlsx", "csv", "ods", v = Icons.Rounded.TableChart, c = Color(0xFF1E9E5A)) +
        ex("ppt", "pptx", "odp", v = Icons.Rounded.Slideshow, c = Color(0xFFE8710A)) +
        ex("txt", "md", "log", v = Icons.Rounded.Description, c = Color(0xFF9AA0A6)) +
        ex("js", "ts", "py", "html", "css", "json", "sh", "kt", "java", "xml", v = Icons.Rounded.Code, c = Color(0xFF26A69A)) +
        ex("apk", "apks", "xapk", v = Icons.Rounded.Android, c = Color(0xFF3DDC84)) +
        ex("zip", "rar", "7z", "tar", "gz", "tgz", "xz", v = Icons.Rounded.FolderZip, c = Color(0xFFFFB300))).toMap()
}

@Composable
fun look(f: File): Pair<ImageVector, Color> {
    if (f.isDirectory) return Icons.Rounded.Folder to MaterialTheme.colorScheme.primary
    EXTL[f.extension.lowercase()]?.let { return it }
    val c = Cat.entries.firstOrNull { it.exts.isNotEmpty() && it.matches(f) }
    return (c?.icon ?: Icons.Rounded.Description) to (c?.color ?: MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun FileIcon(f: File, size: Dp) {
    val (icon, color) = look(f)
    val th = hasThumb(f)
    Box(Modifier.size(size).clip(if (th) RoundedCornerShape(size / 4) else CircleShape).background(color.copy(.18f)), contentAlignment = Alignment.Center) {
        if (th) Thumb(f, Modifier.fillMaxSize()) { Icon(icon, null, tint = color, modifier = Modifier.size(size * .55f)) }
        else Icon(icon, null, tint = color, modifier = Modifier.size(size * .55f))
    }
}

// ---------- browse building blocks ----------
@Composable
fun SecHeader(t: String) = Text(t, Modifier.padding(horizontal = 8.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowseRow(f: File, sel: Boolean, m: VM, check: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val compact = m.view == "compact"
    val s = if (compact) 34.dp else (38 + 24 * m.zoom).dp
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (sel) MaterialTheme.colorScheme.primary.copy(.25f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(horizontal = 8.dp, vertical = if (compact) 5.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (check) { Checkbox(sel, null); Spacer(Modifier.width(6.dp)) }
        FileIcon(f, s)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, fontSize = if (compact) 15.sp else (14 + 3 * m.zoom).sp)
            Row(Modifier.fillMaxWidth()) {
                Text(sizeText(f), fontSize = 11.sp, color = LocalContentColor.current.copy(alpha = .6f), modifier = Modifier.weight(1f))
                Text(dateText(f), fontSize = 11.sp, color = LocalContentColor.current.copy(alpha = .6f))
            }
        }
    }
}

@Composable
fun PathBar(dir: File, onJump: (File) -> Unit) {
    val parts = remember(dir) { generateSequence(dir) { it.parentFile }.toList().reversed() }
    val scroll = rememberScrollState()
    LaunchedEffect(dir, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        parts.forEach { p ->
            Text(
                if (p.parent == null) "/" else p.name,
                Modifier.clip(RoundedCornerShape(8.dp)).clickable { onJump(p) }.padding(horizontal = 6.dp, vertical = 6.dp),
                fontSize = 13.sp, color = if (p == dir) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.primary,
                fontWeight = if (p == dir) FontWeight.SemiBold else FontWeight.Normal
            )
            if (p != dir && p.parent != null) Text("›", fontSize = 13.sp, modifier = Modifier.alpha(.5f))
        }
    }
}

// ---------- browse inside archives ----------
class AEntry(val name: String, val dir: Boolean, val size: Long, val time: Long)

fun tarStream(f: File): TarArchiveInputStream {
    val raw = f.inputStream().buffered()
    return TarArchiveInputStream(if (archExt(f) == ".tar") raw else GzipCompressorInputStream(raw))
}

fun listArchive(f: File): List<AEntry> {
    val out = ArrayList<AEntry>()
    when (archExt(f)) {
        ".zip" -> java.util.zip.ZipFile(f).use { z -> z.entries().asSequence().forEach { out.add(AEntry(it.name.trimEnd('/'), it.isDirectory, it.size, it.time)) } }
        ".tar", ".tgz", ".tar.gz" -> tarStream(f).use { t ->
            generateSequence { t.nextEntry }.forEach { out.add(AEntry(it.name.trimEnd('/'), it.isDirectory, it.size, runCatching { it.lastModifiedDate.time }.getOrDefault(0L))) }
        }
        ".7z" -> SevenZFile.builder().setFile(f).get().use { s ->
            generateSequence { s.nextEntry }.forEach { out.add(AEntry(it.name.trimEnd('/'), it.isDirectory, it.size, runCatching { it.lastModifiedDate.time }.getOrDefault(0L))) }
        }
        else -> error("Unsupported archive")
    }
    return out
}

fun extractEntry(f: File, name: String, out: File) {
    out.parentFile?.mkdirs()
    when (archExt(f)) {
        ".zip" -> java.util.zip.ZipFile(f).use { z ->
            val e = z.getEntry(name) ?: error("Not found in archive")
            z.getInputStream(e).use { i -> out.outputStream().use { i.copyTo(it) } }
        }
        ".tar", ".tgz", ".tar.gz" -> tarStream(f).use { t ->
            while (true) { val e = t.nextEntry ?: break; if (e.name.trimEnd('/') == name) { out.outputStream().use { t.copyTo(it) }; break } }
        }
        ".7z" -> SevenZFile.builder().setFile(f).get().use { s ->
            while (true) {
                val e = s.nextEntry ?: break
                if (e.name.trimEnd('/') == name) {
                    out.outputStream().use { o -> val b = ByteArray(8192); while (true) { val r = s.read(b); if (r < 0) break; o.write(b, 0, r) } }
                    break
                }
            }
        }
        else -> error("Unsupported archive")
    }
}

@Composable
fun ArchiveScreen(f: File, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("") }
    val res by produceState<Result<List<AEntry>>?>(null, f) { value = withContext(Dispatchers.IO) { runCatching { listArchive(f) } } }
    BackHandler(enabled = path.isNotEmpty()) { path = path.substringBeforeLast('/', "") }
    val r = res
    val pri = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxSize()) {
        TopRow(if (path.isEmpty()) f.name else path.substringAfterLast('/'), { if (path.isEmpty()) onBack() else path = path.substringBeforeLast('/', "") }) {
            IconButton({
                val d = uniq(File(f.parentFile, f.name.dropLast((archExt(f) ?: "").length)))
                toast(ctx, "Extracting…")
                scope.launch {
                    val x = withContext(Dispatchers.IO) { runCatching { extract(f, d) } }
                    toast(ctx, if (x.isSuccess) "Extracted to ${d.name}" else x.exceptionOrNull()?.message ?: "Extract failed")
                }
            }) { Icon(Icons.Rounded.Unarchive, "Extract all") }
        }
        Text(f.path + if (path.isEmpty()) "" else "  ›  $path", fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).alpha(.6f))
        if (r == null) Text("Reading archive…", Modifier.padding(24.dp).alpha(.6f))
        else if (r.isFailure) Text("Couldn't read archive: ${r.exceptionOrNull()?.message ?: "unknown error"}", Modifier.padding(24.dp))
        else {
            val prefix = if (path.isEmpty()) "" else "$path/"
            val kids = LinkedHashMap<String, AEntry>()
            r.getOrThrow().forEach { e ->
                if (e.name.startsWith(prefix) && e.name.length > prefix.length) {
                    val rest = e.name.substring(prefix.length)
                    val first = rest.substringBefore('/')
                    val isDir = e.dir || rest.contains('/')
                    if (first !in kids) kids[first] = if (isDir) AEntry(prefix + first, true, 0, e.time) else e
                }
            }
            val list = kids.values.sortedWith(compareBy({ !it.dir }, { it.name.lowercase() }))
            if (list.isEmpty()) Text("Empty", Modifier.padding(24.dp).alpha(.6f))
            LazyColumn(contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, 100.dp)) {
                items(list) { e ->
                    val cat = if (e.dir) null else Cat.entries.firstOrNull { it.exts.isNotEmpty() && e.name.substringAfterLast('.', "").lowercase() in it.exts }
                    val color = if (e.dir) pri else cat?.color ?: MaterialTheme.colorScheme.onSurfaceVariant
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable {
                        if (e.dir) path = e.name else scope.launch {
                            val out = File(ctx.cacheDir, "arc/${f.name.hashCode()}/${e.name.substringAfterLast('/')}")
                            val x = withContext(Dispatchers.IO) { runCatching { extractEntry(f, e.name, out) } }
                            if (x.isSuccess) open(ctx, out) else toast(ctx, x.exceptionOrNull()?.message ?: "Couldn't open")
                        }
                    }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).clip(CircleShape).background(color.copy(.18f)), contentAlignment = Alignment.Center) {
                            Icon(if (e.dir) Icons.Rounded.Folder else cat?.icon ?: Icons.Rounded.Description, null, tint = color)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.name.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            Row(Modifier.fillMaxWidth()) {
                                Text(if (e.dir) "Folder" else fmt(e.size), fontSize = 11.sp, modifier = Modifier.alpha(.6f).weight(1f))
                                if (e.time > 0) Text(SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(e.time)), fontSize = 11.sp, modifier = Modifier.alpha(.6f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tile(f: File, big: Boolean, sel: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val (icon, color) = look(f)
    val img = hasThumb(f)
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).background(if (sel) MaterialTheme.colorScheme.primary.copy(.25f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLong).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).glass(16.dp), contentAlignment = Alignment.Center) {
            if (img) Thumb(f, Modifier.fillMaxSize()) { Icon(icon, null, tint = color, modifier = Modifier.fillMaxSize(.5f)) }
            else Icon(icon, null, tint = color, modifier = Modifier.fillMaxSize(.5f))
        }
        if (!big || !img) Text(f.name, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun OptIcon(icon: ImageVector, label: String, on: Boolean, suffix: String = "", onClick: () -> Unit) {
    val c = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(.7f)
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable { onClick() }.padding(horizontal = 8.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = c)
        Text(label + suffix, fontSize = 12.sp, color = c, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
fun ViewPanel(m: VM, only: Boolean, setM: (VM, Boolean) -> Unit, onFinish: () -> Unit, setOnly: (Boolean) -> Unit) {
    fun pick(s: String) = if (m.sort == s) setM(m.copy(asc = !m.asc), true) else setM(m.copy(sort = s, asc = s == "name" || s == "type"), true)
    Column(Modifier.width(300.dp).padding(16.dp)) {
        Text("View mode", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OptIcon(Icons.Rounded.ViewAgenda, "List", m.view == "list") { setM(m.copy(view = "list"), true) }
            OptIcon(Icons.Rounded.GridView, "Grid", m.view == "grid") { setM(m.copy(view = "grid"), true) }
            OptIcon(Icons.Rounded.Photo, "Gallery", m.view == "gallery") { setM(m.copy(view = "gallery"), true) }
            OptIcon(Icons.Rounded.Reorder, "Compact", m.view == "compact") { setM(m.copy(view = "compact"), true) }
        }
        Spacer(Modifier.height(8.dp))
        Text("Sort mode", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val arrow = if (m.asc) " ↑" else " ↓"
            OptIcon(Icons.Rounded.SortByAlpha, "Name", m.sort == "name", if (m.sort == "name") arrow else "") { pick("name") }
            OptIcon(Icons.Rounded.CalendarToday, "Date", m.sort == "date", if (m.sort == "date") arrow else "") { pick("date") }
            OptIcon(Icons.Rounded.FormatSize, "Size", m.sort == "size", if (m.sort == "size") arrow else "") { pick("size") }
            OptIcon(Icons.Rounded.Description, "Type", m.sort == "type", if (m.sort == "type") arrow else "") { pick("type") }
        }
        Spacer(Modifier.height(8.dp))
        Text("List zooming", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Slider(m.zoom, { setM(m.copy(zoom = it), false) }, onValueChangeFinished = onFinish)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(m.sections, { setM(m.copy(sections = it), true) }); Text("Show sections")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(only, { setOnly(it) }); Text("Only this folder")
        }
    }
}

fun folderBadge(f: File): ImageVector? = when (f.name.lowercase()) {
    "android" -> Icons.Rounded.Settings
    "dcim", "camera" -> Icons.Rounded.PhotoCamera
    "download", "downloads" -> Icons.Rounded.Download
    "documents" -> Icons.Rounded.Description
    "pictures", "screenshots" -> Icons.Rounded.Image
    "music" -> Icons.Rounded.MusicNote
    "movies", "videos" -> Icons.Rounded.Movie
    "backup", "backups" -> Icons.Rounded.Backup
    "bluetooth" -> Icons.Rounded.Bluetooth
    else -> null
}

@Composable
fun FolderGlyph(mod: Modifier, badge: ImageVector?) {
    Box(mod.aspectRatio(1.25f)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val r = CornerRadius(w * .07f)
            drawRoundRect(Color(0xFF2F8FD6), Offset(0f, h * .1f), Size(w, h * .9f), r)
            drawRoundRect(Color(0xFF2F8FD6), Offset(0f, 0f), Size(w * .45f, h * .3f), r)
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF63C4F7), Color(0xFF3CA6EA))), Offset(0f, h * .2f), Size(w, h * .8f), r)
        }
        if (badge != null) Icon(badge, null, tint = Color.White.copy(.92f), modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 8.dp).fillMaxWidth(.28f).aspectRatio(1f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GridTile(f: File, sel: Boolean, check: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val (icon, color) = look(f)
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).background(if (sel) MaterialTheme.colorScheme.primary.copy(.25f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLong).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.fillMaxWidth()) {
            if (f.isDirectory) FolderGlyph(Modifier.fillMaxWidth(.9f).align(Alignment.Center), folderBadge(f))
            else Box(Modifier.fillMaxWidth(.8f).aspectRatio(1.25f).align(Alignment.Center).clip(RoundedCornerShape(10.dp)).background(color.copy(.18f)), contentAlignment = Alignment.Center) {
                if (hasThumb(f)) Thumb(f, Modifier.fillMaxSize()) { Icon(icon, null, tint = color, modifier = Modifier.fillMaxSize(.55f)) }
                else Icon(icon, null, tint = color, modifier = Modifier.fillMaxSize(.55f))
            }
            if (check) Checkbox(sel, null, Modifier.align(Alignment.TopStart))
        }
        Text(f.name, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryTile(f: File, sel: Boolean, check: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val (icon, color) = look(f)
    Box(
        Modifier.clip(RoundedCornerShape(14.dp)).aspectRatio(1f).background(color.copy(.16f))
            .combinedClickable(onClick = onClick, onLongClick = onLong)
    ) {
        if (f.isDirectory) FolderGlyph(Modifier.align(Alignment.Center).fillMaxWidth(.7f), folderBadge(f))
        else if (hasThumb(f)) Thumb(f, Modifier.fillMaxSize()) { Icon(icon, null, tint = color, modifier = Modifier.align(Alignment.Center).fillMaxSize(.4f)) }
        else Icon(icon, null, tint = color, modifier = Modifier.align(Alignment.Center).fillMaxSize(.4f))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.7f)))).padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(f.name, fontSize = 12.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (sel) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(.3f)))
        if (check) Checkbox(sel, null, Modifier.align(Alignment.TopStart).padding(2.dp))
    }
}

@Composable
fun SelTopBar(count: Int, size: String, onClose: () -> Unit, onAll: () -> Unit, onInvert: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(12.dp).glass(shape = CircleShape).padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClose) { Icon(Icons.Rounded.Close, "Exit selection") }
        Column(Modifier.weight(1f)) {
            Text("$count item${if (count == 1) "" else "s"} selected", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(size, fontSize = 12.sp, modifier = Modifier.alpha(.65f))
        }
        IconButton(onAll) { Icon(Icons.Rounded.SelectAll, "Select all") }
        IconButton(onInvert) { Icon(Icons.Rounded.FlipToBack, "Invert selection") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Browse(dir: File, canUp: Boolean, rev: Int, bump: () -> Unit, onEnter: (File) -> Unit, onUp: () -> Unit, onMenu: () -> Unit, onArchive: (File) -> Unit, onJump: (File) -> Unit, onSearch: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sel by remember(dir) { mutableStateOf(setOf<File>()) }
    var selMode by remember(dir) { mutableStateOf(false) }
    var ask by remember { mutableStateOf<String?>(null) }
    var q by remember(dir) { mutableStateOf("") }
    var sheet by remember { mutableStateOf<File?>(null) }
    var menuFor by remember { mutableStateOf<File?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var pw by remember { mutableStateOf<Pair<File, Boolean>?>(null) }
    val view = LocalView.current
    var confirm by remember { mutableStateOf<List<File>?>(null) }
    var info by remember { mutableStateOf<List<File>?>(null) }
    var vmenu by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var bmore by remember { mutableStateOf(false) }
    var m by remember(dir) { mutableStateOf(vmFor(dir)) }
    var only by remember(dir) { mutableStateOf(S.p?.contains("vm:" + dir.path) == true) }
    val local = !isRestricted(dir)
    var listN by remember(dir) { mutableStateOf<List<File>?>(null) }
    LaunchedEffect(dir, rev, q, m, S.hidden, S.foldersFirst) { listN = withContext(Dispatchers.IO) { listDir(dir, q, m) } }
    val list: List<File> = listN ?: emptyList()
    val rows: List<Any> = remember(list, m) {
        if (m.sections) buildList<Any> {
            var last: String? = null
            list.forEach { f -> val s = sectionOf(f, m); if (s != null && s != last) { add(s); last = s }; add(f) }
        } else list
    }
    val selecting = selMode || sel.isNotEmpty()
    DisposableEffect(selecting) { UiState.hideNav = selecting; onDispose { UiState.hideNav = false } }
    fun setM(n: VM, save: Boolean) { m = n; if (save) saveVm(dir, n, only) }
    fun exit() { sel = emptySet(); selMode = false }
    BackHandler(enabled = selecting) { exit(); UiState.backGuard = System.currentTimeMillis() + 2500 }
    fun toggle(f: File) { sel = if (f in sel) sel - f else sel + f }
    fun tap(f: File) { if (selecting) toggle(f) else if (f.isDirectory) onEnter(f) else if (isArchive(f)) onArchive(f) else open(ctx, f) }
    fun hold(f: File) {
        if (S.haptics) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (selecting) toggle(f) else { selMode = true; sel = setOf(f); menuFor = f }
    }
    fun del(l: List<File>) { doDelete(ctx, l); exit(); bump() }
    fun askDel(l: List<File>) { if (S.confirmDel) confirm = l else del(l) }
    fun paste() {
        val src = Clip.files; val cut = Clip.cut
        scope.launch { withContext(Dispatchers.IO) { pasteInto(dir, src, cut) }; if (cut) Clip.files = emptyList(); bump() }
    }
    fun compress(l: List<File>) {
        val out = uniq(File(dir, if (l.size == 1) l[0].nameWithoutExtension + ".zip" else "Archive.zip"))
        toast(ctx, "Compressing…")
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { zipTo(l, out) } }
            toast(ctx, if (r.isSuccess) "Created ${out.name}" else r.exceptionOrNull()?.message ?: "Compress failed")
            exit(); bump()
        }
    }
    fun extractIt(f: File) {
        val d = uniq(File(f.parentFile, f.name.dropLast((archExt(f) ?: "").length)))
        toast(ctx, "Extracting…")
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { extract(f, d) } }
            toast(ctx, if (r.isSuccess) "Extracted to ${d.name}" else r.exceptionOrNull()?.message ?: "Extract failed")
            exit(); bump()
        }
    }
    val itemMenu: @Composable (File) -> Unit = { f ->
        DropdownMenu(menuFor == f, { menuFor = null }) {
            DropdownMenuItem(text = { Text("Archive") }, leadingIcon = { Icon(Icons.Rounded.Archive, null) }, onClick = { menuFor = null; compress(listOf(f)) })
            if (isArchive(f)) DropdownMenuItem(text = { Text("Extract") }, leadingIcon = { Icon(Icons.Rounded.Unarchive, null) }, onClick = { menuFor = null; extractIt(f) })
            if (f.isFile) DropdownMenuItem(
                text = { Text(if (f.extension.equals("fhe", true)) "Decrypt" else "Encrypt") }, leadingIcon = { Icon(Icons.Rounded.Lock, null) },
                onClick = { menuFor = null; pw = f to !f.extension.equals("fhe", true) })
            if (f.isFile) DropdownMenuItem(text = { Text("Open with") }, leadingIcon = { Icon(Icons.Rounded.Apps, null) }, onClick = { menuFor = null; openWith(ctx, f) })
            if (f.isDirectory) DropdownMenuItem(text = { Text("Open directory") }, leadingIcon = { Icon(Icons.Rounded.FolderOpen, null) }, onClick = { menuFor = null; exit(); onEnter(f) })
            DropdownMenuItem(text = { Text("Create shortcut") }, leadingIcon = { Icon(Icons.Rounded.PushPin, null) }, onClick = { menuFor = null; pinShortcut(ctx, f) })
            DropdownMenuItem(text = { Text("Properties") }, leadingIcon = { Icon(Icons.Rounded.Info, null) }, onClick = { menuFor = null; info = listOf(f) })
        }
    }
    val bottomPad = 100.dp
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (selecting) {
                SelTopBar(sel.size, fmt(sel.filter { it.isFile }.sumOf { it.length() }), { exit() }, { sel = list.toSet() }, { sel = list.toSet() - sel })
            } else {
                TopRow(if (dir == ROOT) "Internal storage" else dir.name.ifEmpty { "/" }, if (canUp) onUp else onMenu,
                    if (canUp) Icons.AutoMirrored.Rounded.ArrowBack else Icons.Rounded.Menu) {
                    if (Clip.files.isNotEmpty()) IconButton({ paste() }) { Icon(Icons.Rounded.ContentPaste, "Paste") }
                    Box {
                        IconButton({ vmenu = true }) { Icon(Icons.Rounded.ViewModule, "View and sort") }
                        DropdownMenu(vmenu, { vmenu = false }) {
                            ViewPanel(m, only, ::setM, { saveVm(dir, m, only) }) { only = it; saveVm(dir, m, it) }
                        }
                    }
                    Box {
                        IconButton({ more = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                        DropdownMenu(more, { more = false }) {
                            DropdownMenuItem(text = { Text("Select files") }, leadingIcon = { Icon(Icons.Rounded.CheckCircle, null) }, onClick = { more = false; selMode = true })
                            DropdownMenuItem(text = { Text("Select all") }, leadingIcon = { Icon(Icons.Rounded.SelectAll, null) }, onClick = { more = false; selMode = true; sel = list.toSet() })
                            DropdownMenuItem(text = { Text("New folder") }, leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, null) }, onClick = { more = false; ask = "new" })
                            DropdownMenuItem(text = { Text("New file") }, leadingIcon = { Icon(Icons.Rounded.Description, null) }, onClick = { more = false; ask = "newfile" })
                            if (Clip.files.isNotEmpty()) DropdownMenuItem(text = { Text("Paste here") }, leadingIcon = { Icon(Icons.Rounded.ContentPaste, null) }, onClick = { more = false; paste() })
                            DropdownMenuItem(text = { Text(if (S.hidden) "Hide hidden files" else "Show hidden files") }, leadingIcon = { Icon(Icons.Rounded.FolderOpen, null) }, onClick = { more = false; S.hidden = !S.hidden; S.save() })
                            DropdownMenuItem(text = { Text("Refresh") }, leadingIcon = { Icon(Icons.Rounded.Refresh, null) }, onClick = { more = false; bump() })
                        }
                    }
                }
            }
            SearchField(q, { q = it }, Modifier.padding(horizontal = 12.dp)) { if (q.isNotBlank()) onSearch(q) }
            if (q.isNotBlank()) TextButton({ onSearch(q) }, Modifier.padding(horizontal = 8.dp)) {
                Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Search everywhere for “$q”")
            }
            PathBar(dir, onJump)
            if (listN != null && list.isEmpty()) { if (local) Text("Nothing here", modifier = Modifier.padding(24.dp).alpha(.6f)) else RestrictedBox { bump() } }
            PullToRefreshBox(refreshing, { scope.launch { refreshing = true; bump(); delay(600); refreshing = false } }, Modifier.fillMaxSize()) {
            if (m.view == "grid" || m.view == "gallery") {
                val gal = m.view == "gallery"
                val min = if (gal) 140 + 80 * m.zoom else 72 + 36 * m.zoom
                LazyVerticalGrid(
                    GridCells.Adaptive(min.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, bottomPad),
                    horizontalArrangement = Arrangement.spacedBy(if (gal) 8.dp else 4.dp), verticalArrangement = Arrangement.spacedBy(if (gal) 8.dp else 4.dp)
                ) {
                    rows.forEach { r ->
                        if (r is String) item(key = "h:$r", span = { GridItemSpan(maxLineSpan) }) { SecHeader(r) }
                        else if (r is File) item(key = r.path) {
                            Box {
                                if (gal) GalleryTile(r, r in sel, selecting, { tap(r) }, { hold(r) })
                                else GridTile(r, r in sel, selecting, { tap(r) }, { hold(r) })
                                if (menuFor == r) itemMenu(r)
                            }
                        }
                    }
                }
            } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, bottomPad)) {
                items(rows, key = { if (it is File) it.path else "h:$it" }, contentType = { if (it is File) 1 else 0 }) { r ->
                    if (r is String) SecHeader(r) else if (r is File) Box { BrowseRow(r, r in sel, m, selecting, { tap(r) }, { hold(r) }); if (menuFor == r) itemMenu(r) }
                }
            }
            }
        }
        if (selecting) {
            val one = sel.size == 1
            val first = sel.firstOrNull()
            var expanded by remember { mutableStateOf(!S.actionFab) }
            if (!expanded) {
                FloatingActionButton({ expanded = true }, Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 16.dp, bottom = 24.dp), containerColor = MaterialTheme.colorScheme.primary) { Icon(Icons.Rounded.Apps, "Actions") }
            } else Column(
                Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 12.dp, bottom = 24.dp).glass(shape = CircleShape).padding(vertical = 6.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (S.actionFab) IconButton({ expanded = false }) { Icon(Icons.Rounded.Close, "Collapse") }
                IconButton({ Clip.set(sel.toList(), true); exit() }, enabled = sel.isNotEmpty()) { Icon(Icons.Rounded.ContentCut, "Cut") }
                IconButton({ Clip.set(sel.toList(), false); exit() }, enabled = sel.isNotEmpty()) { Icon(Icons.Rounded.ContentCopy, "Copy") }
                IconButton({ askDel(sel.toList()) }, enabled = sel.isNotEmpty()) { Icon(Icons.Rounded.Delete, "Delete") }
                IconButton({ if (one && first != null) share(ctx, first) else shareMany(ctx, sel.filter { it.isFile }) }, enabled = sel.any { it.isFile }) { Icon(Icons.Rounded.Share, "Share") }
                if (one) IconButton({ ask = "rename" }) { Icon(Icons.Rounded.Edit, "Rename") }
                Box {
                    IconButton({ bmore = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                    DropdownMenu(bmore, { bmore = false }) {
                        DropdownMenuItem(text = { Text("Archive (ZIP)") }, leadingIcon = { Icon(Icons.Rounded.Archive, null) }, enabled = sel.isNotEmpty(), onClick = { bmore = false; compress(sel.toList()) })
                        if (one && first != null && isArchive(first)) DropdownMenuItem(text = { Text("Extract") }, leadingIcon = { Icon(Icons.Rounded.Unarchive, null) }, onClick = { bmore = false; extractIt(first) })
                        if (one && first != null && first.isFile) DropdownMenuItem(text = { Text("Open with…") }, leadingIcon = { Icon(Icons.Rounded.Apps, null) }, onClick = { bmore = false; openWith(ctx, first) })
                        if (one && first != null && first.isDirectory) DropdownMenuItem(text = { Text("Open directory") }, leadingIcon = { Icon(Icons.Rounded.FolderOpen, null) }, onClick = { bmore = false; exit(); onEnter(first) })
                        DropdownMenuItem(text = { Text("Properties") }, leadingIcon = { Icon(Icons.Rounded.Info, null) }, enabled = sel.isNotEmpty(), onClick = { bmore = false; info = sel.toList() })
                    }
                }
            }
        }
    }
    pw?.let { (f, enc) ->
        var pass by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pw = null },
            title = { Text(if (enc) "Encrypt ${f.name}" else "Decrypt ${f.name}") },
            text = { OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation()) },
            confirmButton = {
                TextButton({
                    if (pass.isNotEmpty()) {
                        pw = null
                        val out = uniq(File(f.parentFile, if (enc) f.name + ".fhe" else f.name.removeSuffix(".fhe")))
                        toast(ctx, if (enc) "Encrypting…" else "Decrypting…")
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { runCatching { if (enc) encryptFile(f, out, pass.toCharArray()) else decryptFile(f, out, pass.toCharArray()) } }
                            toast(ctx, if (r.isSuccess) "Created ${out.name}" else r.exceptionOrNull()?.message ?: "Failed")
                            exit(); bump()
                        }
                    }
                }) { Text(if (enc) "Encrypt" else "Decrypt") }
            },
            dismissButton = { TextButton({ pw = null }) { Text("Cancel") } }
        )
    }
    sheet?.let { f ->
        ModalBottomSheet(onDismissRequest = { sheet = null }) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(f.name, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val acts = buildList {
                    add(Act("Open", Icons.Rounded.Description) { if (f.isDirectory) onEnter(f) else if (isArchive(f)) onArchive(f) else open(ctx, f) })
                    if (f.isFile) {
                        add(Act("Open with…", Icons.Rounded.Apps) { openWith(ctx, f) })
                        add(Act("Share", Icons.Rounded.Share) { share(ctx, f) })
                    }
                    add(Act("Cut (move)", Icons.Rounded.ContentCut) { Clip.set(listOf(f), true) })
                    add(Act("Copy", Icons.Rounded.ContentCopy) { Clip.set(listOf(f), false) })
                    add(Act("Compress to ZIP", Icons.Rounded.Archive) { compress(listOf(f)) })
                    if (isArchive(f)) add(Act("Extract", Icons.Rounded.Unarchive) { extractIt(f) })
                    add(Act("Rename", Icons.Rounded.Edit) { sel = setOf(f); ask = "rename" })
                    add(Act("Delete", Icons.Rounded.Delete) { askDel(listOf(f)) })
                    add(Act("Select", Icons.Rounded.CheckCircle) { sel = setOf(f); selMode = true })
                    add(Act("Details", Icons.Rounded.Info) { info = listOf(f) })
                }
                acts.forEach { a ->
                    ListItem(headlineContent = { Text(a.label) }, leadingContent = { Icon(a.icon, null) }, modifier = Modifier.clickable { sheet = null; a.run() })
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    confirm?.let { l ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (S.useBin) "Move to bin?" else "Delete permanently?") },
            text = { Text(if (l.size == 1) l[0].name else "${l.size} items") },
            confirmButton = { TextButton({ confirm = null; del(l) }) { Text("Delete") } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } }
        )
    }
    info?.let { l ->
        AlertDialog(
            onDismissRequest = { info = null },
            title = { Text(if (l.size == 1) l[0].name else "${l.size} items") },
            text = {
                Text(
                    if (l.size == 1) "${l[0].path}\n\n${sub(l[0])}"
                    else "${l.count { it.isDirectory }} folders, ${l.count { it.isFile }} files\nTotal: ${fmt(l.filter { it.isFile }.sumOf { it.length() })}"
                )
            },
            confirmButton = { TextButton({ info = null }) { Text("Close") } }
        )
    }
    ask?.let { mode ->
        var t by remember { mutableStateOf(if (mode == "rename") sel.first().name else if (mode == "newfile") "note.txt" else "") }
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(when (mode) { "rename" -> "Rename"; "newfile" -> "New file"; else -> "New folder" }) },
            text = { OutlinedTextField(t, { t = it }, singleLine = true) },
            confirmButton = {
                TextButton({
                    if (t.isNotBlank()) {
                        if (mode == "rename") sel.first().renameTo(File(dir, t))
                        else if (mode == "newfile") runCatching { File(dir, t).createNewFile() }
                        else File(dir, t).mkdirs()
                        exit(); ask = null; bump()
                    }
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ ask = null }) { Text("Cancel") } }
        )
    }
}

// ---------- home ----------
@Composable
fun SectionHeader(title: String, open: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onToggle() }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (open) "Collapse" else "Expand")
    }
}

@Composable
fun CatTile(c: Cat, count: String, mod: Modifier, onClick: () -> Unit) {
    Column(mod.clip(RoundedCornerShape(16.dp)).clickable { onClick() }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(c.color.copy(.18f)), contentAlignment = Alignment.Center) { Icon(c.icon, null, tint = c.color) }
        Spacer(Modifier.height(6.dp))
        Text(c.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(count, fontSize = 12.sp, modifier = Modifier.alpha(.6f))
    }
}

@Composable
fun StorageCard(used: Float, free: Long, total: Long, compact: Boolean, mod: Modifier, onAnalyze: (() -> Unit)? = null, onClick: () -> Unit) {
    val pri = MaterialTheme.colorScheme.primary
    Column(mod.glass(28.dp).clickable { onClick() }.padding(if (compact) 14.dp else 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(if (compact) 40.dp else 56.dp).clip(RoundedCornerShape(14.dp)).background(pri.copy(.18f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Folder, null, tint = pri)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Internal Storage", fontSize = if (compact) 15.sp else 20.sp, fontWeight = FontWeight.SemiBold)
                Text(ROOT.path, fontSize = 12.sp, modifier = Modifier.alpha(.65f))
            }
            if (onAnalyze != null) {
                var mm by remember { mutableStateOf(false) }
                Box {
                    IconButton({ mm = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                    DropdownMenu(mm, { mm = false }) {
                        DropdownMenuItem(text = { Text("Storage analyzer") }, leadingIcon = { Icon(Icons.Rounded.PieChart, null) }, onClick = { mm = false; onAnalyze() })
                        DropdownMenuItem(text = { Text("Open storage") }, leadingIcon = { Icon(Icons.Rounded.FolderOpen, null) }, onClick = { mm = false; onClick() })
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator({ used }, Modifier.fillMaxWidth().height(8.dp).clip(CircleShape))
        Spacer(Modifier.height(8.dp))
        if (compact) Text("${fmt(free)} free", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        else Row {
            Text("${fmt(free)} free", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${fmt(total)} total")
        }
    }
}

@Composable
fun Donut(used: Float, mod: Modifier) {
    val pri = MaterialTheme.colorScheme.primary
    Canvas(mod) {
        val w = 14.dp.toPx()
        val d = size.minDimension - w
        val tl = Offset(w / 2, w / 2); val sz = Size(d, d)
        drawArc(Color(0xFF3DDC97), -90f, 360f, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
        drawArc(pri, -90f, 360f * used, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
    }
}

@Composable
fun Home(scan: Scan?, onMenu: () -> Unit, onRefresh: () -> Unit, onSearch: (String) -> Unit, onFolder: (File) -> Unit, onAnalyze: () -> Unit, onCat: (Cat) -> Unit) {
    val ctx = LocalContext.current
    val st = remember { StatFs(ROOT.path) }
    val total = st.totalBytes; val free = st.availableBytes
    val used = (1f - free.toFloat() / total).coerceIn(0f, 1f)
    val pri = MaterialTheme.colorScheme.primary
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val folders = remember(scan, S.homeStyle) {
        if (S.homeStyle == 2) (ROOT.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.sortedBy { it.name.lowercase() } ?: emptyList()) else emptyList()
    }
    fun count(c: Cat) = scan?.counts?.get(c)?.toString() ?: "…"
    var delFor by remember { mutableStateOf<File?>(null) }
    delFor?.let { f ->
        AlertDialog(
            onDismissRequest = { delFor = null },
            title = { Text(if (S.useBin) "Move to bin?" else "Delete permanently?") },
            text = { Text(f.name) },
            confirmButton = { TextButton({ delFor = null; doDelete(ctx, listOf(f)); onRefresh() }) { Text("Delete") } },
            dismissButton = { TextButton({ delFor = null }) { Text("Cancel") } }
        )
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 20.dp, 20.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("My Files", fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onRefresh) { Icon(Icons.Rounded.Refresh, "Refresh") }
                IconButton(onMenu) { Icon(Icons.Rounded.Menu, "Menu") }
            }
        }
        item { var q by remember { mutableStateOf("") }; SearchField(q, { q = it }) { onSearch(q) } }
        when (S.homeStyle) {
            1 -> {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StorageCard(used, free, total, true, Modifier.weight(1f).height(170.dp), onAnalyze) { onFolder(ROOT) }
                        Column(Modifier.weight(1f).height(170.dp).glass(28.dp).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                                Donut(used, Modifier.fillMaxSize())
                                Text("${(used * 100).toInt()}%", fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("● ", color = pri, fontSize = 12.sp); Text("Used  ", fontSize = 12.sp)
                                Text("● ", color = Color(0xFF3DDC97), fontSize = 12.sp); Text("Free", fontSize = 12.sp)
                            }
                        }
                    }
                }
                item { SectionHeader("Categories", S.catsOpen) { S.catsOpen = !S.catsOpen; S.save() } }
                if (S.catsOpen) item {
                    Column(Modifier.fillMaxWidth().glass(24.dp).padding(8.dp)) {
                        Cat.entries.forEach { c ->
                            val open = expanded == c.name
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { expanded = if (open) null else c.name }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(c.icon, null, tint = c.color)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) { Text(c.label, fontWeight = FontWeight.SemiBold); Text(count(c), fontSize = 12.sp, modifier = Modifier.alpha(.6f)) }
                                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                            }
                            if (open) Row(Modifier.fillMaxWidth().padding(start = 46.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Size: " + (scan?.sizes?.get(c)?.let { fmt(it) } ?: "…"), Modifier.weight(1f), fontSize = 13.sp)
                                TextButton({ onCat(c) }) { Text("Open") }
                            }
                        }
                    }
                }
            }
            2 -> {
                item {
                    Row(Modifier.glass(shape = CircleShape).clickable { onFolder(ROOT) }.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.FolderOpen, null, tint = pri)
                        Spacer(Modifier.width(10.dp))
                        Text("Internal Storage", color = pri, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(12.dp))
                        Text("${fmt(free)} free", fontSize = 12.sp, modifier = Modifier.alpha(.6f))
                    }
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(Cat.entries) { c -> CatTile(c, count(c), Modifier.width(78.dp)) { onCat(c) } }
                    }
                }
                item { Text("Internal Storage", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
                items(folders) { f ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onFolder(f) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Folder, null, Modifier.size(36.dp), tint = pri)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium); Text(sub(f), fontSize = 12.sp, modifier = Modifier.alpha(.6f)) }
                        Text("›", fontSize = 22.sp, modifier = Modifier.alpha(.6f))
                    }
                }
            }
            else -> {
                item { StorageCard(used, free, total, false, Modifier.fillMaxWidth(), onAnalyze) { onFolder(ROOT) } }
                item { SectionHeader("Quick Categories", S.catsOpen) { S.catsOpen = !S.catsOpen; S.save() } }
                if (S.catsOpen) items(Cat.entries.chunked(4)) { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { c -> CatTile(c, count(c), Modifier.weight(1f)) { onCat(c) } }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        item { SectionHeader("Recent Files", S.recentOpen) { S.recentOpen = !S.recentOpen; S.save() } }
        if (S.recentOpen) items(scan?.recent ?: emptyList()) { f ->
            var mm by remember(f) { mutableStateOf(false) }
            FileRow(f, false, { open(ctx, f) }, {}, menu = {
                Box {
                    IconButton({ mm = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                    if (mm) DropdownMenu(true, { mm = false }) {
                        DropdownMenuItem(text = { Text("Open file location") }, leadingIcon = { Icon(Icons.Rounded.FolderOpen, null) }, onClick = { mm = false; onFolder(f.parentFile ?: ROOT) })
                        DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Rounded.Share, null) }, onClick = { mm = false; share(ctx, f) })
                        DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { mm = false; delFor = f })
                    }
                }
            })
        }
    }
}
