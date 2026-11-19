package org.fileshub.app

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun humanSize(b: Number): String {
    val v = b.toDouble()
    return when {
        v < 1024 -> "${v.toLong()} B"
        v < 1024 * 1024 -> "%.1f KB".format(v / 1024)
        v < 1024L * 1024 * 1024 -> "%.1f MB".format(v / 1024 / 1024)
        else -> "%.2f GB".format(v / 1024 / 1024 / 1024)
    }
}

private fun kindOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "apk" -> "Install"
    "jpg", "jpeg", "png", "gif", "webp", "heic" -> "View"
    "mp4", "mkv", "webm", "mp3", "m4a", "wav", "flac", "pdf", "txt", "md", "log", "json", "xml", "html", "csv" -> "Open"
    else -> "Save to Downloads"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RemoteScreen(s: Server, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var tapItem by remember { mutableStateOf<RItem?>(null) }
    var showOptions by remember { mutableStateOf(false) }
    var showProps by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<List<String>?>(null) }
    var busy by remember { mutableStateOf(false) }

    val res by produceState<Result<List<RItem>>?>(null, path, tick) {
        value = null
        value = withContext(Dispatchers.IO) { runCatching { connect(s).use { it.list(path) } } }
    }
    val r = res
    val list = r?.getOrNull()?.sortedWith(compareBy({ !it.dir }, { it.name.lowercase() })) ?: emptyList()

    BackHandler(enabled = path.isNotEmpty() || selectionMode) {
        if (selectionMode) {
            selectionMode = false
            selected = emptySet()
        } else {
            path = path.substringBeforeLast('/', "")
        }
    }

    fun fullOf(name: String) = if (path.isEmpty()) name else "$path/$name"
    fun say(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    fun clearSelection() {
        selectionMode = false
        selected = emptySet()
    }

    // Runs one operation on its own connection, reports the real error if it fails, then refreshes.
    fun runOp(label: String, op: (Remote) -> Unit, done: () -> Unit = {}) {
        scope.launch {
            busy = true
            val err = withContext(Dispatchers.IO) { runCatching { connect(s).use(op) }.exceptionOrNull() }
            busy = false
            if (err == null) {
                say("$label done")
                done()
                tick++
            } else {
                say("$label failed: ${err.message ?: err.javaClass.simpleName}")
            }
        }
    }

    fun copyToDownloads(names: List<String>, move: Boolean) {
        if (list.any { it.name in names && it.dir }) {
            say("Folders can't be copied yet. Select files only.")
            return
        }
        runOp(if (move) "Move" else "Copy", { rem ->
            names.forEach { n ->
                val dest = File(ROOT, "Download/$n").apply { parentFile?.mkdirs() }
                rem.download(fullOf(n), dest)
                if (move) rem.delete(fullOf(n))
            }
        }) { clearSelection() }
    }

    fun openFile(x: RItem) {
        val dest = File(ROOT, "Download/${x.name}")
        runOp("Saved ${x.name}", { rem ->
            dest.parentFile?.mkdirs()
            rem.download(fullOf(x.name), dest)
        }) { open(ctx, dest) }
    }

    fun deleteRemote(names: List<String>) {
        runOp("Delete", { rem -> names.forEach { rem.delete(fullOf(it)) } }) { clearSelection() }
    }

    fun shareSelected(names: List<String>) {
        if (list.any { it.name in names && it.dir }) {
            say("Folders can't be shared yet. Select files only.")
            return
        }
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
                    names.map { n ->
                        val f = File(dir, n)
                        connect(s).use { it.download(fullOf(n), f) }
                        FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                    }
                }
            }
            busy = false
            result.onSuccess { uris ->
                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ctx.startActivity(Intent.createChooser(intent, "Share"))
                clearSelection()
            }.onFailure { say("Share failed: ${it.message}") }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopRow(
            if (selectionMode) "${selected.size} selected"
            else if (path.isEmpty()) "${s.proto} · ${s.host}"
            else path.substringAfterLast('/'),
            {
                if (selectionMode) {
                    clearSelection()
                } else if (path.isEmpty()) {
                    onBack()
                } else {
                    path = path.substringBeforeLast('/', "")
                }
            }
        ) {
            IconButton({ tick++ }) { Icon(Icons.Rounded.Refresh, "Refresh") }
            IconButton({
                if (selectionMode) {
                    clearSelection()
                } else {
                    selectionMode = true
                }
            }) {
                Icon(
                    if (selectionMode) Icons.Rounded.Check else Icons.Rounded.SelectAll,
                    if (selectionMode) "Exit select" else "Select mode"
                )
            }
        }

        if (busy) Text("Working…", Modifier.padding(8.dp).alpha(.6f))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                r == null -> Text("Connecting…", Modifier.padding(24.dp).alpha(.6f))
                r.isFailure -> Text(
                    "Couldn't connect: ${r.exceptionOrNull()?.message ?: "unknown error"}",
                    Modifier.padding(24.dp)
                )
                list.isEmpty() -> Text("This folder is empty", Modifier.padding(24.dp).alpha(.6f))
                else -> LazyColumn(contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, 100.dp)) {
                    items(list, key = { it.name }) { x ->
                        val isSel = selected.contains(x.name)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .combinedClickable(
                                    onClick = {
                                        when {
                                            selectionMode -> selected =
                                                if (isSel) selected - x.name else selected + x.name
                                            x.dir -> path = fullOf(x.name)
                                            else -> tapItem = x
                                        }
                                    },
                                    onLongClick = {
                                        selectionMode = true
                                        selected = selected + x.name
                                        showOptions = true
                                    }
                                )
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (selectionMode) {
                                Checkbox(
                                    checked = isSel,
                                    onCheckedChange = null,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(x.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!x.dir) Text(humanSize(x.size), Modifier.alpha(.6f))
                            }
                        }
                    }
                }
            }
        }

        if (selectionMode) {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                TextButton({ copyToDownloads(selected.toList(), false) }, enabled = selected.isNotEmpty()) { Text("Copy") }
                TextButton({ copyToDownloads(selected.toList(), true) }, enabled = selected.isNotEmpty()) { Text("Move") }
                TextButton({ pendingDelete = selected.toList() }, enabled = selected.isNotEmpty()) { Text("Delete") }
                TextButton({ shareSelected(selected.toList()) }, enabled = selected.isNotEmpty()) { Text("Share") }
                TextButton({ showProps = true }, enabled = selected.size == 1) { Text("Info") }
            }
        }
    }

    // Single tap on a file: type-based action first.
    tapItem?.let { x ->
        AlertDialog(
            onDismissRequest = { tapItem = null },
            title = { Text(x.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    TextButton({ tapItem = null; openFile(x) }) { Text(kindOf(x.name)) }
                    TextButton({ tapItem = null; copyToDownloads(listOf(x.name), false) }) { Text("Copy to Downloads") }
                    TextButton({
                        tapItem = null
                        selectionMode = true
                        selected = setOf(x.name)
                        showOptions = true
                    }) { Text("More options") }
                }
            },
            confirmButton = { TextButton({ tapItem = null }) { Text("Cancel") } }
        )
    }

    // Long press or "More options": full action list for the selection.
    if (showOptions && selected.isNotEmpty()) {
        val names = selected.toList()
        AlertDialog(
            onDismissRequest = { showOptions = false },
            title = { Text(if (names.size == 1) names[0] else "${names.size} items", maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    TextButton({ showOptions = false; copyToDownloads(names, false) }) { Text("Copy to Downloads") }
                    TextButton({ showOptions = false; copyToDownloads(names, true) }) { Text("Move to Downloads") }
                    TextButton({ showOptions = false; shareSelected(names) }) { Text("Share") }
                    TextButton({ showOptions = false; showProps = true }) { Text("Properties") }
                    TextButton({ showOptions = false; pendingDelete = names }) { Text("Delete") }
                }
            },
            confirmButton = { TextButton({ showOptions = false }) { Text("Close") } }
        )
    }

    if (showProps) {
        val sel = list.filter { selected.contains(it.name) }
        AlertDialog(
            onDismissRequest = { showProps = false },
            title = { Text("Properties") },
            text = {
                Column {
                    if (sel.size == 1) {
                        val x = sel[0]
                        Text("Name: ${x.name}")
                        Text("Type: ${if (x.dir) "Folder" else "File"}")
                        if (!x.dir) Text("Size: ${humanSize(x.size)}")
                        Text("Location: ${if (path.isEmpty()) "/" else "/$path"}")
                    } else {
                        Text("${sel.size} items selected")
                    }
                }
            },
            confirmButton = { TextButton({ showProps = false }) { Text("Close") } }
        )
    }

    pendingDelete?.let { names ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${names.size} item(s)?") },
            text = { Text("This removes them from the server. It can't be undone.") },
            confirmButton = { TextButton({ pendingDelete = null; deleteRemote(names) }) { Text("Delete") } },
            dismissButton = { TextButton({ pendingDelete = null }) { Text("Cancel") } }
        )
    }
}
