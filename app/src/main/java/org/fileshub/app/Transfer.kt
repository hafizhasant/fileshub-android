package org.fileshub.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

object TransferState { var open by mutableStateOf(false) }

class TransferService : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("transfer", "File transfer", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "transfer").setContentTitle("FilesHub is sharing files").setContentText("Tap to open")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)).build()
        startForeground(77, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        TransferServer.start(applicationContext)
        return START_NOT_STICKY
    }
    override fun onDestroy() { TransferServer.stop(); super.onDestroy() }
}

fun startSharing(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, TransferService::class.java))
fun stopSharing(ctx: Context) { ctx.stopService(Intent(ctx, TransferService::class.java)); TransferServer.stop() }

fun qrBitmap(text: String, size: Int = 512): ImageBitmap {
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size) { if (m.get(it % size, it / size)) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

fun LazyListScope.transferItem(onClick: () -> Unit) {
    item {
        val pri = MaterialTheme.colorScheme.primary
        Row(Modifier.fillMaxWidth().glass(24.dp).clickable { onClick() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(pri.copy(.18f)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Share, null, tint = pri) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Transfer", fontWeight = FontWeight.SemiBold)
                Text(
                    if (TransferServer.running) "Sharing on · ${TransferServer.activeClients()} connected" else "Send and receive files over Wi-Fi",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f)
                )
            }
            if (TransferServer.running) Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF3DDC97)))
        }
    }
}

@Composable
fun TTile(mod: Modifier, icon: ImageVector, title: String, onClick: () -> Unit) {
    val pri = MaterialTheme.colorScheme.primary
    Row(mod.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer).clickable { onClick() }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(pri.copy(.2f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = pri) }
        Spacer(Modifier.width(10.dp))
        Text(title, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun TransferScreen(onBack: () -> Unit, onFolder: (File) -> Unit) {
    val ctx = LocalContext.current
    val pri = MaterialTheme.colorScheme.primary
    val onPri = MaterialTheme.colorScheme.onPrimary
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f)
    var ip by remember { mutableStateOf<String?>(null) }
    var clients by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { ip = withContext(Dispatchers.IO) { TransferServer.ip() }; clients = TransferServer.activeClients(); delay(2000) } }
    val running = TransferServer.running
    val url = ip?.let { "http://" + it + ":" + TransferServer.port + "/?k=" + TransferServer.token }
    val qr = remember(url) { url?.let { qrBitmap(it) } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> uris.forEach { TransferServer.addUri(ctx, it) } }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { startSharing(ctx) }
    fun toggle() {
        if (running) stopSharing(ctx)
        else if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
        else startSharing(ctx)
    }
    Column(Modifier.fillMaxSize()) {
        TopRow("Transfer", onBack)
        LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(pri).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(224.dp).clip(RoundedCornerShape(20.dp)).background(Color.White).padding(10.dp), contentAlignment = Alignment.Center) {
                        if (running && qr != null) Image(qr, "QR code", Modifier.fillMaxSize())
                        else Text(if (ip == null) "Connect to Wi-Fi or turn on a hotspot" else "Sharing is off", color = Color.Black, textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(if (running) "Scan to connect" else "Turn sharing on to get a code", color = onPri, fontWeight = FontWeight.SemiBold)
                    if (running && url != null) Text(url, color = onPri.copy(alpha = .85f), fontSize = 12.sp, textAlign = TextAlign.Center)
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(
                        Modifier.weight(1f).height(150.dp).clip(RoundedCornerShape(24.dp))
                            .background(if (running) pri else MaterialTheme.colorScheme.surfaceContainer).clickable { toggle() }.padding(16.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(.25f)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PowerSettingsNew, null, tint = Color.White) }
                        Column {
                            Text("Sharing", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = if (running) onPri else MaterialTheme.colorScheme.onSurface)
                            Text((if (running) "On" else "Off") + " · $clients connected", fontSize = 12.sp, color = if (running) onPri.copy(alpha = .85f) else dim)
                        }
                    }
                    Column(Modifier.weight(1f).height(150.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TTile(Modifier.fillMaxWidth().weight(1f), Icons.Rounded.Refresh, "New code") { TransferServer.token = TransferServer.newToken() }
                        TTile(Modifier.fillMaxWidth().weight(1f), Icons.Rounded.Share, "Send a file") { pick.launch(arrayOf("*/*")) }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer).clickable { onFolder(TransferServer.receivedDir().also { it.mkdirs() }) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(pri.copy(.2f)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Download, null, tint = pri) }
                    Spacer(Modifier.width(12.dp))
                    Column { Text("Received files", fontWeight = FontWeight.SemiBold); Text("From your other devices · ${TransferServer.received} received", fontSize = 12.sp, color = dim) }
                }
            }
            if (TransferServer.shared.isNotEmpty()) item { Text("Shared with devices", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            items(TransferServer.shared) { s ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (s.size >= 0) Text(fmt(s.size), fontSize = 12.sp, color = dim)
                    }
                    IconButton({ TransferServer.shared = TransferServer.shared - s }) { Icon(Icons.Rounded.Close, "Remove") }
                }
            }
            item {
                Text("Open the link or scan the code on another device on the same Wi-Fi to download shared files or send files here. Anyone with the code can reach the shared files, so only use trusted networks.", fontSize = 12.sp, color = dim)
            }
        }
    }
}
