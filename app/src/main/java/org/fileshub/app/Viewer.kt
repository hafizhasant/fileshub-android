package org.fileshub.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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

@Composable
fun ImageViewer(file: File, onClose: () -> Unit) {
    val context = LocalContext.current
    val metrics = context.resources.displayMetrics

    val state by produceState<LoadState>(LoadState.Loading, file) {
        value = withContext(Dispatchers.IO) {
            decodeScaled(file, metrics.widthPixels, metrics.heightPixels)
                ?.let { LoadState.Ready(it) } ?: LoadState.Failed
        }
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset = if (scale > 1f) offset + pan else Offset.Zero
                }
            },
        contentAlignment = Alignment.Center
    ) {
        when (val s = state) {
            LoadState.Loading -> Text("Loading...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LoadState.Failed -> Text("Can't open this image", color = MaterialTheme.colorScheme.onSurfaceVariant)
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

    BackHandler(onBack = onClose)
}

object Images {
    val exts = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
}
