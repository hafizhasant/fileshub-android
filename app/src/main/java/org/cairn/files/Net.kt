package org.cairn.files

import android.content.Context
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
        val a = JSONArray(c.getSharedPreferences("cairn", 0).getString("servers", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Server(o.getString("id"), o.getString("proto"), o.getString("host"), o.getInt("port"), o.getString("user"), o.getString("pass"), o.getString("share"))
        }
    }
    fun save(c: Context, l: List<Server>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("proto", it.proto).put("host", it.host).put("port", it.port).put("user", it.user).put("pass", it.pass).put("share", it.share)) }
        c.getSharedPreferences("cairn", 0).edit().putString("servers", a.toString()).apply()
    }
}
