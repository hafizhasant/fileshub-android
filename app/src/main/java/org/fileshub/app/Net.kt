package org.fileshub.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.security.Security

class Server(val id: String, val proto: String, val host: String, val port: Int, val user: String, val pass: String, val share: String)
class RItem(val name: String, val dir: Boolean, val size: Long)

interface Remote : Closeable {
    fun list(path: String): List<RItem>
    fun download(path: String, out: File)
}

class FtpRemote(s: Server) : Remote {
    private val c = FTPClient()
    init {
        c.connectTimeout = 10000
        c.connect(s.host, s.port)
        check(c.login(s.user.ifBlank { "anonymous" }, s.pass)) { "Login failed" }
        c.enterLocalPassiveMode()
        c.setFileType(FTP.BINARY_FILE_TYPE)
    }
    override fun list(path: String): List<RItem> =
        (if (path.isEmpty()) c.listFiles() else c.listFiles(path))
            .filter { it.name != "." && it.name != ".." }
            .map { RItem(it.name, it.isDirectory, it.size) }
    override fun download(path: String, out: File) {
        out.outputStream().use { check(c.retrieveFile(path, it)) { "Download failed" } }
    }
    override fun close() { runCatching { c.logout(); c.disconnect() } }
}

class SmbRemote(s: Server) : Remote {
    private val client: SMBClient
    private val conn: com.hierynomus.smbj.connection.Connection
    private val session: com.hierynomus.smbj.session.Session
    private val share: DiskShare
    init {
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        client = SMBClient()
        conn = client.connect(s.host, s.port)
        val auth = if (s.user.isBlank()) AuthenticationContext.anonymous() else AuthenticationContext(s.user, s.pass.toCharArray(), "")
        session = conn.authenticate(auth)
        share = session.connectShare(s.share) as DiskShare
    }
    private fun p(x: String) = x.replace('/', '\\')
    override fun list(path: String): List<RItem> =
        share.list(p(path)).filter { it.fileName != "." && it.fileName != ".." }.map {
            RItem(it.fileName, (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L, it.endOfFile)
        }
    override fun download(path: String, out: File) {
        share.openFile(p(path), setOf(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null).use { f ->
            f.inputStream.use { i -> out.outputStream().use { i.copyTo(it) } }
        }
    }
    override fun close() { runCatching { share.close(); session.close(); conn.close(); client.close() } }
}

fun connect(s: Server): Remote = if (s.proto == "FTP") FtpRemote(s) else SmbRemote(s)

object Servers {
    fun load(c: Context): List<Server> {
        val a = JSONArray(c.getSharedPreferences("fileshub", 0).getString("servers", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Server(o.getString("id"), o.getString("proto"), o.getString("host"), o.getInt("port"), o.getString("user"), o.getString("pass"), o.getString("share"))
        }
    }
    fun save(c: Context, l: List<Server>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("proto", it.proto).put("host", it.host).put("port", it.port).put("user", it.user).put("pass", it.pass).put("share", it.share)) }
        c.getSharedPreferences("fileshub", 0).edit().putString("servers", a.toString()).apply()
    }
}

// ---------- LAN transfer (small built-in web server, used only when sharing is switched on) ----------
class Shared(val name: String, val size: Long, val file: File?, val uri: android.net.Uri?) {
    fun open(ctx: Context): java.io.InputStream? =
        if (file != null) java.io.FileInputStream(file) else uri?.let { ctx.contentResolver.openInputStream(it) }
}

private const val PAGE = """<!doctype html><html><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1"><title>FilesHub Transfer</title>
<style>body{font-family:system-ui,sans-serif;background:#151515;color:#fff;margin:0 auto;padding:16px;max-width:640px}h1{font-size:22px}h2{font-size:16px;margin-top:28px;color:#ff8f4d}
.row{display:flex;justify-content:space-between;gap:12px;background:#1e1e1e;border-radius:14px;padding:14px;margin:8px 0;color:#fff;text-decoration:none;word-break:break-all}
small{opacity:.6;white-space:nowrap}.btn{display:inline-block;background:#ff8f4d;color:#000;border-radius:24px;padding:12px 22px;font-weight:600}</style></head><body>
<h1>FilesHub Transfer</h1><h2>Download from this device</h2><div id=l>Loading...</div>
<h2>Send to this device</h2><label class=btn>Choose files<input id=f type=file multiple hidden></label><div id=p></div>
<script>
var K=new URLSearchParams(location.search).get('k');
function esc(s){return s.replace(/[&<>"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
function load(){fetch('/list?k='+K).then(function(r){return r.json()}).then(function(a){document.getElementById('l').innerHTML=a.length?a.map(function(x,i){return '<a class=row href="/f/'+i+'?k='+K+'">'+esc(x.n)+'<small>'+x.s+'</small></a>'}).join(''):'<p>Nothing shared yet</p>'})}
load();setInterval(load,5000);
document.getElementById('f').onchange=function(e){Array.prototype.forEach.call(e.target.files,function(f){var d=document.createElement('div');d.className='row';d.textContent=f.name+' - 0%';document.getElementById('p').appendChild(d);var x=new XMLHttpRequest();x.open('POST','/up?k='+K+'&name='+encodeURIComponent(f.name));x.upload.onprogress=function(v){d.textContent=f.name+' - '+Math.round(v.loaded*100/v.total)+'%'};x.onload=function(){d.textContent=f.name+(x.status==200?' - done':' - failed')};x.onerror=function(){d.textContent=f.name+' - failed'};x.send(f)})}
</script></body></html>"""

object TransferServer {
    var running by mutableStateOf(false)
    var port = 8080
    var token by mutableStateOf(newToken())
    var shared by mutableStateOf(listOf<Shared>())
    var received by mutableStateOf(0)
    private val seen = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var ss: java.net.ServerSocket? = null
    private val pool = java.util.concurrent.Executors.newCachedThreadPool()

    fun newToken(): String = (100000..999999).random().toString()
    fun receivedDir() = File(ROOT, "Download/FilesHub Received")
    fun activeClients(): Int { val now = System.currentTimeMillis(); seen.entries.removeIf { now - it.value > 30000 }; return seen.size }
    fun addFiles(l: List<File>) { shared = shared + l.map { Shared(it.name, it.length(), it, null) } }
    fun addUri(ctx: Context, u: android.net.Uri) {
        var n = "file"; var sz = -1L
        ctx.contentResolver.query(u, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (i >= 0) n = c.getString(i)
                val j = c.getColumnIndex(android.provider.OpenableColumns.SIZE); if (j >= 0 && !c.isNull(j)) sz = c.getLong(j)
            }
        }
        shared = shared + Shared(n, sz, null, u)
    }

    fun ip(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    fun start(ctx: Context) {
        if (running) return
        val app = ctx.applicationContext
        val sock = (0..10).firstNotNullOfOrNull { runCatching { java.net.ServerSocket(8080 + it) }.getOrNull() } ?: return
        ss = sock; port = sock.localPort; running = true
        pool.execute {
            while (running) {
                val c = try { sock.accept() } catch (e: Exception) { break }
                pool.execute { runCatching { handle(app, c) } }
            }
            running = false
        }
    }

    fun stop() { running = false; runCatching { ss?.close() }; ss = null; seen.clear() }

    private fun readLine(i: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun parseQuery(s: String): Map<String, String> =
        s.split("&").filter { it.contains("=") }.associate { it.substringBefore("=") to java.net.URLDecoder.decode(it.substringAfter("="), "UTF-8") }

    private fun respond(out: java.io.OutputStream, code: Int, reason: String, type: String, body: ByteArray) {
        out.write(("HTTP/1.1 $code $reason\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n").toByteArray())
        out.write(body); out.flush()
    }

    private fun jsonEsc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun handle(ctx: Context, c: java.net.Socket) {
        c.use {
            c.soTimeout = 30000; c.tcpNoDelay = true
            val inp = java.io.BufferedInputStream(c.getInputStream(), 262144)
            val out = java.io.BufferedOutputStream(c.getOutputStream(), 262144)
            val req = readLine(inp) ?: return
            val parts = req.split(" ")
            if (parts.size < 2) return
            val headers = HashMap<String, String>()
            while (true) {
                val l = readLine(inp) ?: break
                if (l.isEmpty()) break
                val i = l.indexOf(':')
                if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
            val path = parts[1].substringBefore('?')
            val q = parseQuery(parts[1].substringAfter('?', ""))
            if (q["k"] != token) { respond(out, 403, "Forbidden", "text/plain", "Invalid or expired code".toByteArray()); return }
            seen[c.inetAddress?.hostAddress ?: "?"] = System.currentTimeMillis()
            when {
                parts[0] == "GET" && path == "/" -> respond(out, 200, "OK", "text/html", PAGE.toByteArray())
                parts[0] == "GET" && path == "/list" -> respond(out, 200, "OK", "application/json",
                    shared.joinToString(",", "[", "]") { "{\"n\":\"" + jsonEsc(it.name) + "\",\"s\":\"" + (if (it.size >= 0) fmt(it.size) else "") + "\"}" }.toByteArray())
                parts[0] == "GET" && path.startsWith("/f/") -> {
                    val item = shared.getOrNull(path.removePrefix("/f/").toIntOrNull() ?: -1)
                    val ins = item?.open(ctx)
                    if (item == null || ins == null) { respond(out, 404, "Not Found", "text/plain", "Not found".toByteArray()); return }
                    val ascii = item.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val enc = java.net.URLEncoder.encode(item.name, "UTF-8").replace("+", "%20")
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" + (if (item.size >= 0) "Content-Length: ${item.size}\r\n" else "") +
                        "Content-Disposition: attachment; filename=\"$ascii\"; filename*=UTF-8''$enc\r\nConnection: close\r\n\r\n").toByteArray())
                    ins.use { it.copyTo(out, 262144) }
                    out.flush()
                }
                parts[0] == "POST" && path == "/up" -> {
                    val name = File(q["name"] ?: "file").name.ifBlank { "file" }
                    val len = headers["content-length"]?.toLongOrNull()
                    if (len == null || len < 0) { respond(out, 411, "Length Required", "text/plain", "Length required".toByteArray()); return }
                    val dir = receivedDir(); dir.mkdirs()
                    val dest = uniq(File(dir, name))
                    var left = len
                    dest.outputStream().buffered(262144).use { o ->
                        val buf = ByteArray(262144)
                        while (left > 0) {
                            val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n < 0) break
                            o.write(buf, 0, n); left -= n
                        }
                    }
                    if (left > 0) { dest.delete(); respond(out, 400, "Bad Request", "text/plain", "Upload interrupted".toByteArray()) }
                    else { received++; respond(out, 200, "OK", "text/plain", "OK".toByteArray()) }
                }
                else -> respond(out, 404, "Not Found", "text/plain", "Not found".toByteArray())
            }
        }
    }
}
