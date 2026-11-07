package org.fileshub.app

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import java.io.File

// ---------- root listing (for "/" and other protected folders) ----------
class RootFile(path: String, private val dirFlag: Boolean, private val len: Long, private val mod: Long) : File(path) {
    override fun isDirectory(): Boolean = dirFlag
    override fun isFile(): Boolean = !dirFlag
    override fun length(): Long = len
    override fun lastModified(): Long = mod
    override fun exists(): Boolean = true
    override fun list(): Array<String>? = null
    override fun listFiles(): Array<File>? = rootList(this)?.toTypedArray()
}

fun rootList(dir: File): List<File>? = runCatching {
    val q = dir.path.replace("'", "'\\''")
    val cmd = "cd '$q' && for f in * .[!.]*; do [ -e \"\$f\" ] && stat -L -c '%F|%s|%Y|%n' \"\$f\"; done 2>/dev/null"
    runShell(cmd)?.mapNotNull { l ->
        val a = l.split("|", limit = 4)
        if (a.size < 4) null
        else RootFile(File(dir, a[3]).path, a[0].contains("directory"), a[1].toLongOrNull() ?: 0L, (a[2].toLongOrNull() ?: 0L) * 1000)
    }?.takeIf { it.isNotEmpty() }
}.getOrNull()

// ---------- thumbnails ----------
val THUMB_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "mp4", "mkv", "mov", "avi", "webm", "3gp", "pdf", "apk", "mp3", "flac", "m4a", "ogg", "opus", "wav", "aac")
val IMG_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")
val VID_EXT = setOf("mp4", "mkv", "mov", "avi", "webm", "3gp")
val AUD_EXT = setOf("mp3", "flac", "m4a", "ogg", "opus", "wav", "aac")
fun hasThumb(f: File): Boolean {
    if (!f.isFile || f is RootFile) return false
    val e = f.extension.lowercase()
    return when {
        e in IMG_EXT -> S.thumbImg
        e in VID_EXT -> S.thumbVid
        e == "pdf" -> S.thumbPdf
        e == "apk" -> S.thumbApk
        e in AUD_EXT -> S.thumbAud
        else -> false
    }
}

fun pdfThumb(f: File): ImageBitmap? {
    ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
        PdfRenderer(pfd).use { r ->
            if (r.pageCount == 0) return null
            r.openPage(0).use { pg ->
                val w = 400
                val h = (w.toFloat() * pg.height / pg.width).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bmp.asImageBitmap()
            }
        }
    }
}

fun audioArt(f: File): ImageBitmap? {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(f.path)
        return r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    } finally { r.release() }
}

fun apkIcon(ctx: Context, f: File): ImageBitmap? {
    val pm = ctx.packageManager
    val info = pm.getPackageArchiveInfo(f.path, 0) ?: return null
    info.applicationInfo?.apply { sourceDir = f.path; publicSourceDir = f.path }
    return info.applicationInfo?.let { pm.getApplicationIcon(it).toBitmap(128, 128).asImageBitmap() }
}

fun makeThumb(ctx: Context, f: File): ImageBitmap? = when (f.extension.lowercase()) {
    "mp4", "mkv", "mov", "avi", "webm", "3gp" -> ThumbnailUtils.createVideoThumbnail(f, android.util.Size(256, 256), null).asImageBitmap()
    "pdf" -> pdfThumb(f)
    "apk" -> apkIcon(ctx, f)
    "mp3", "flac", "m4a", "ogg", "opus", "wav", "aac" -> audioArt(f)
    else -> decodeThumb(f)
}

// ---------- APK installer ----------
object Pending { var apk by mutableStateOf<File?>(null) }

fun installApk(ctx: Context, f: File) {
    if (!ctx.packageManager.canRequestPackageInstalls()) {
        toast(ctx, "Allow installs from FilesHub, then tap the APK again")
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return
    }
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uriOf(ctx, f), "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(i) }.onFailure { toast(ctx, "Couldn't start the installer") }
}

@Composable
fun InstallDialog(f: File, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    val info = remember(f) { pm.getPackageArchiveInfo(f.path, 0)?.also { it.applicationInfo?.apply { sourceDir = f.path; publicSourceDir = f.path } } }
    val label = remember(info) { info?.applicationInfo?.let { pm.getApplicationLabel(it).toString() } ?: f.name }
    val icon = remember(info) { info?.applicationInfo?.let { runCatching { pm.getApplicationIcon(it).toBitmap(112, 112).asImageBitmap() }.getOrNull() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { icon?.let { Image(it, null, Modifier.size(56.dp)) } },
        title = { Text(label) },
        text = { Text(listOfNotNull(info?.packageName, info?.versionName?.let { "Version $it" }, fmt(f.length())).joinToString("\n")) },
        confirmButton = { TextButton({ onDismiss(); installApk(ctx, f) }) { Text("Install") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

fun shareMany(ctx: Context, l: List<File>) {
    if (l.isEmpty()) return
    val uris = ArrayList(l.map { uriOf(ctx, it) })
    val i = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, "Share"))
}

// ---------- encrypt / decrypt (AES-256-GCM, PBKDF2) ----------
private fun deriveKey(pass: CharArray, salt: ByteArray): javax.crypto.spec.SecretKeySpec {
    val k = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(javax.crypto.spec.PBEKeySpec(pass, salt, 150000, 256)).encoded
    return javax.crypto.spec.SecretKeySpec(k, "AES")
}

fun encryptFile(f: File, out: File, pass: CharArray) {
    require(f.length() <= 150_000_000L) { "File is too large to encrypt (limit 150 MB)" }
    val rnd = java.security.SecureRandom()
    val salt = ByteArray(16).also { rnd.nextBytes(it) }
    val iv = ByteArray(12).also { rnd.nextBytes(it) }
    val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
    c.init(javax.crypto.Cipher.ENCRYPT_MODE, deriveKey(pass, salt), javax.crypto.spec.GCMParameterSpec(128, iv))
    out.outputStream().buffered().use { o ->
        o.write("FHE1".toByteArray()); o.write(salt); o.write(iv)
        javax.crypto.CipherOutputStream(o, c).use { co -> f.inputStream().use { it.copyTo(co) } }
    }
}

fun decryptFile(f: File, out: File, pass: CharArray) {
    require(f.length() <= 150_000_000L) { "File is too large to decrypt (limit 150 MB)" }
    try {
        f.inputStream().buffered().use { i ->
            val magic = ByteArray(4); val salt = ByteArray(16); val iv = ByteArray(12)
            check(i.read(magic) == 4 && String(magic) == "FHE1") { "Not a FilesHub encrypted file" }
            check(i.read(salt) == 16 && i.read(iv) == 12) { "File is damaged" }
            val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            c.init(javax.crypto.Cipher.DECRYPT_MODE, deriveKey(pass, salt), javax.crypto.spec.GCMParameterSpec(128, iv))
            javax.crypto.CipherInputStream(i, c).use { ci -> out.outputStream().use { ci.copyTo(it) } }
        }
    } catch (e: java.io.IOException) {
        out.delete(); throw IllegalStateException("Wrong password or damaged file")
    }
}

// ---------- pinned shortcut (opens the folder in FilesHub) ----------
fun pinShortcut(ctx: Context, f: File) {
    val target = if (f.isDirectory) f else (f.parentFile ?: f)
    if (!androidx.core.content.pm.ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) { toast(ctx, "Your launcher doesn't support pinned shortcuts"); return }
    val intent = Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("path", target.path)
    val info = androidx.core.content.pm.ShortcutInfoCompat.Builder(ctx, "dir:" + target.path)
        .setShortLabel(target.name.ifEmpty { "/" }).setIntent(intent)
        .setIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(ctx, R.mipmap.ic_launcher)).build()
    androidx.core.content.pm.ShortcutManagerCompat.requestPinShortcut(ctx, info, null)
}

// ---------- Shizuku / shell access ----------
fun shizukuOk(): Boolean = runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

fun shizukuExec(cmd: String): List<String>? = runCatching {
    val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
    m.isAccessible = true
    val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
    val lines = p.inputStream.bufferedReader().readLines()
    p.waitFor()
    lines
}.getOrNull()

fun runShell(cmd: String): List<String>? {
    if (shizukuOk()) shizukuExec(cmd)?.let { if (it.isNotEmpty()) return it }
    return runCatching {
        val p = ProcessBuilder("su", "-c", cmd).start()
        val lines = p.inputStream.bufferedReader().readLines()
        p.waitFor()
        lines
    }.getOrNull()
}

fun isRestricted(d: File) = (!d.path.startsWith(ROOT.path) && Vols.list.none { d.path.startsWith(it.dir.path) }) || d.path.contains("/Android/data") || d.path.contains("/Android/obb")

val thumbSem = kotlinx.coroutines.sync.Semaphore(3)

// ---------- OTG / removable storage ----------
class Vol(val name: String, val dir: File)

object Vols {
    var list by mutableStateOf(listOf<Vol>())
    fun refresh(c: Context) {
        list = runCatching {
            val sm = c.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
            sm.storageVolumes.filter { !it.isPrimary && it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED }
                .mapNotNull { v -> v.directory?.let { Vol(v.getDescription(c), it) } }
        }.getOrDefault(emptyList())
    }
}
