package org.fileshub.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object TextFiles {
    val exts = setOf(
        "json", "txt", "md", "xml", "csv", "log", "kt", "java", "py", "sh",
        "yml", "yaml", "gradle", "properties", "ini", "conf", "cfg", "html",
        "css", "js", "ts", "toml", "gitignore"
    )
}

private const val MAX_BYTES = 512 * 1024

private fun readCapped(file: File): String {
    val size = minOf(file.length(), MAX_BYTES.toLong()).toInt()
    val bytes = file.inputStream().use { s ->
        val buf = ByteArray(size)
        var off = 0
        while (off < size) {
            val n = s.read(buf, off, size - off)
            if (n < 0) break
            off += n
        }
        buf.copyOf(off)
    }
    return String(bytes, Charsets.UTF_8)
}

@Composable
fun TextViewer(file: File, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme

    var loaded by remember { mutableStateOf(false) }
    var original by remember { mutableStateOf("") }
    var value by remember { mutableStateOf(TextFieldValue("")) }
    var menuOpen by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    val truncated = remember(file) { file.length() > MAX_BYTES }
    val editable = loaded && !truncated
    val dirty = value.text != original

    LaunchedEffect(file) {
        val text = withContext(Dispatchers.IO) {
            try { readCapped(file) } catch (e: Exception) { "Can't read this file: ${e.message}" }
        }
        original = text
        value = TextFieldValue(text)
        loaded = true
    }

    fun save() {
        if (!editable || !dirty) return
        val text = value.text
        scope.launch {
            withContext(Dispatchers.IO) { file.writeText(text) }
            original = text
        }
    }

    fun copySelection() {
        val sel = value.selection
        val picked = if (sel.collapsed) value.text else value.text.substring(sel.min, sel.max)
        clipboard.setText(AnnotatedString(picked))
    }

    fun paste() {
        if (!editable) return
        val clip = clipboard.getText()?.text ?: return
        val s = value.selection.min
        val e = value.selection.max
        value = TextFieldValue(value.text.replaceRange(s, e, clip), TextRange(s + clip.length))
    }

    fun selectAll() {
        value = value.copy(selection = TextRange(0, value.text.length))
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Column(
            Modifier.fillMaxSize().background(colors.background).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, contentDescription = "Close") }
                    Column(Modifier.weight(1f)) {
                        Text(file.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (dirty) "Unsaved changes" else if (truncated) "Read-only (over 512 KB)" else sizeText(file),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Select all") }, leadingIcon = { Icon(Icons.Default.SelectAll, null) },
                                onClick = { menuOpen = false; selectAll() })
                            DropdownMenuItem(text = { Text("Copy") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                                onClick = { menuOpen = false; copySelection() })
                            DropdownMenuItem(text = { Text("Paste") }, leadingIcon = { Icon(Icons.Default.ContentPaste, null) },
                                enabled = editable, onClick = { menuOpen = false; paste() })
                            DropdownMenuItem(text = { Text("Save") }, leadingIcon = { Icon(Icons.Default.Save, null) },
                                enabled = editable && dirty, onClick = { menuOpen = false; save() })
                            DropdownMenuItem(text = { Text("Details") }, leadingIcon = { Icon(Icons.Default.Info, null) },
                                onClick = { menuOpen = false; showDetails = true })
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth().weight(1f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = { if (editable) value = it },
                    readOnly = !editable,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = colors.onSurface
                    ),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                )
            }
        }
    }

    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            confirmButton = { TextButton(onClick = { showDetails = false }) { Text("Close") } },
            title = { Text("Details") },
            text = {
                Column {
                    Text("Name: ${file.name}")
                    Text("Path: ${file.path}")
                    Text("Size: ${sizeText(file)}")
                    Text("Modified: ${dateText(file)}")
                    Text("Characters: ${value.text.length}")
                    Text("Lines: ${value.text.count { it == '\n' } + 1}")
                }
            }
        )
    }

    BackHandler(onBack = onClose)
}
