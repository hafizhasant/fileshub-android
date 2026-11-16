package org.fileshub.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface LoadState {
    object Loading : LoadState
    object Failed : LoadState
    class Ready(val bitmap: Bitmap) : LoadState
}

// Decode at a size close to the screen so large photos don't run out of memory.
private fun decodeScaled(file: File, maxW: Int, maxH: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxW && bounds.outHeight / (sample * 2) >= maxH) {
        sample *= 2
    }
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample }
    )
}

// Copies the image as a content URI. Whether another app can paste it depends on that app.
private fun copyImage(ctx: Context, f: File) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newUri(ctx.contentResolver, f.name, uriOf(ctx, f)))
    Toast.makeText(ctx, "Image copied", Toast.LENGTH_SHORT).show()
}

private fun shareFile(ctx: Context, f: File) {
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "image/*"
    val send = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uriOf(ctx, f))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "Share image"))
}

@Composable
fun ImageViewer(file: File, onClose: () -> Unit) {
    val context = LocalContext.current
    val metrics = context.resources.displayMetrics
    val colors = MaterialTheme.colorScheme

    val state by produceState<LoadState>(LoadState.Loading, file) {
        value = withContext(Dispatchers.IO) {
            decodeScaled(file, metrics.widthPixels, metrics.heightPixels)?.let { LoadState.Ready(it) } ?: LoadState.Failed
        }
    }
    val dims by produceState("...", file) {
        value = withContext(Dispatchers.IO) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, o)
            if (o.outWidth > 0) "${o.outWidth} × ${o.outHeight}" else "unknown"
        }
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var menuOpen by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }

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
                        Text(sizeText(file), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Copy image") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                                onClick = { menuOpen = false; copyImage(context, file) })
                            DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Default.Share, null) },
                                onClick = { menuOpen = false; shareFile(context, file) })
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
                Box(
                    Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                offset = if (scale > 1f) offset + pan else Offset.Zero
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    when (val s = state) {
                        LoadState.Loading -> Text("Loading...", color = colors.onSurfaceVariant)
                        LoadState.Failed -> Text("Can't open this image", color = colors.onSurfaceVariant)
                        is LoadState.Ready -> Image(
                            bitmap = s.bitmap.asImageBitmap(),
                            contentDescription = file.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offset.x,
                                    translationY = offset.y
                                )
                        )
                    }
                }
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
                    Text("Dimensions: $dims")
                    Text("Type: ${file.extension.uppercase()}")
                }
            }
        )
    }

    BackHandler(onBack = onClose)
}

object Images {
    val exts = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
}
