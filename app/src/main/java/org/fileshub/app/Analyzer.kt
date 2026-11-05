package org.fileshub.app

import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File

class AStats(val folders: List<Pair<File, Long>>, val biggest: List<Pair<File, Long>>)

fun analyze(): AStats {
    val heap = java.util.PriorityQueue<Pair<File, Long>>(compareBy<Pair<File, Long>> { it.second })
    fun push(f: File, n: Long) { heap.add(f to n); if (heap.size > 40) heap.poll() }
    val folders = ArrayList<Pair<File, Long>>()
    ROOT.listFiles()?.forEach { t ->
        if (t == BIN) return@forEach
        var sum = 0L
        if (t.isFile) { sum = t.length(); push(t, sum) }
        else t.walkTopDown().filter { it.isFile }.forEach { val n = it.length(); sum += n; push(it, n) }
        folders.add(t to sum)
    }
    return AStats(folders.sortedByDescending { it.second }.take(15), heap.sortedByDescending { it.second })
}

@Composable
fun AnalyzerScreen(scan: Scan?, onBack: () -> Unit, onFolder: (File) -> Unit) {
    val ctx = LocalContext.current
    val st = remember { StatFs(ROOT.path) }
    val total = st.totalBytes; val free = st.availableBytes; val used = total - free
    val res by produceState<AStats?>(null) { value = withContext(Dispatchers.IO) { analyze() } }
    val pri = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f)
    val r = res
    val types = Cat.entries.filter { it != Cat.Bin && it != Cat.Downloads && it != Cat.Screenshots }
    val maxType = (types.maxOfOrNull { scan?.sizes?.get(it) ?: 0L } ?: 1L).coerceAtLeast(1L)
    Column(Modifier.fillMaxSize()) {
        TopRow("Storage analyzer", onBack)
        LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(Modifier.fillMaxWidth().glass(24.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                        Donut((used.toFloat() / total).coerceIn(0f, 1f), Modifier.fillMaxSize())
                        Text("${(used * 100 / total).toInt()}%", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("${fmt(used)} used", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text("${fmt(free)} free of ${fmt(total)}", fontSize = 13.sp, color = dim)
                    }
                }
            }
            item { Text("By type", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
            items(types) { c ->
                val n = scan?.sizes?.get(c) ?: 0L
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(c.icon, null, tint = c.color)
                        Spacer(Modifier.width(10.dp))
                        Text(c.label, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        Text(if (scan == null) "…" else fmt(n), fontSize = 13.sp, color = dim)
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator({ n.toFloat() / maxType }, Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = c.color)
                }
            }
            item { Text("Largest folders", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
            if (r == null) item { Text("Analyzing…", color = dim) } else {
                val maxF = (r.folders.firstOrNull()?.second ?: 1L).coerceAtLeast(1L)
                items(r.folders) { (f, n) ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onFolder(f) }.padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (f.isDirectory) Icons.Rounded.Folder else Icons.Rounded.Description, null, tint = pri)
                            Spacer(Modifier.width(10.dp))
                            Text(f.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(fmt(n), fontSize = 13.sp, color = dim)
                        }
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator({ n.toFloat() / maxF }, Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
                    }
                }
                item { Text("Largest files", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
                items(r.biggest) { (f, _) -> FileRow(f, false, { open(ctx, f) }, {}) }
            }
        }
    }
}

@Composable
fun RestrictedBox(onRetry: () -> Unit) {
    val pri = MaterialTheme.colorScheme.primary
    val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    val granted = shizukuOk()
    Column(Modifier.fillMaxWidth().padding(16.dp).glass(24.dp).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Rounded.Lock, null, Modifier.size(40.dp), tint = pri)
        Text("Restricted folder", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(
            if (!running) "Start Shizuku (or allow root), then tap Retry." else if (!granted) "Android blocks apps from this folder. Grant Shizuku access to view it."
            else "Nothing here, or this folder can't be read.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running && !granted) Button({ runCatching { Shizuku.requestPermission(1001) } }) { Text("Grant Shizuku access") }
            TextButton(onRetry) { Text("Retry") }
        }
    }
}
