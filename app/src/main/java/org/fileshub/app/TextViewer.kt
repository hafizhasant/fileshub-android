package org.fileshub.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
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

// Reads at most MAX_BYTES, without InputStream.readNBytes (API 33 only; minSdk is 30).
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
    val text = String(bytes, Charsets.UTF_8)
    return if (file.length() > MAX_BYTES) text + "\n\n… file truncated at 512 KB" else text
}

@Composable
fun TextViewer(file: File, onClose: () -> Unit) {
    val content by produceState<String?>(null, file) {
        value = withContext(Dispatchers.IO) {
            try { readCapped(file) } catch (e: Exception) { "Can't read this file: ${e.message}" }
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {
            Text(
                text = content ?: "Loading...",
                color = Color(0xFFEEEEEE),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            )
        }
    }
    BackHandler(onBack = onClose)
}
