package com.locusable.ginkgo.shelf.weread

import android.content.Context
import android.system.Os
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import org.json.JSONObject

internal class WeReadSnapshot(
    val account: String? = null,
    val session: WeReadWeb.Session? = null,
    val shelf: WeReadShelf? = null,
)

/** One atomic, app-private, non-backup snapshot. The target reader's Keystore is not usable. */
internal class WeReadStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "weread-private")
    // Keep the old encrypted file untouched; this storage path never consults Keystore.
    private val file = AtomicFile(File(directory, "snapshot-v1"))

    fun load(): WeReadSnapshot {
        val json = try {
            file.openRead().use { JSONObject(it.readLimited(MAX_BYTES).toString(Charsets.UTF_8)) }
        } catch (_: FileNotFoundException) {
            return WeReadSnapshot()
        }
        val account = json.value("account", 64) ?: return WeReadSnapshot()
        val cookies = json.optJSONObject("cookies")?.let { jar ->
            jar.keys().asSequence().associateWith { jar.getString(it) }
        }
        return WeReadSnapshot(account, cookies?.let { WeReadWeb.session(account, it) },
            json.optJSONObject("shelf")?.let { WeReadShelf.parse(it) })
    }

    fun save(snapshot: WeReadSnapshot) {
        val bytes = JSONObject().apply {
            put("account", snapshot.account)
            snapshot.session?.let { put("cookies", JSONObject(it.cookies)) }
            snapshot.shelf?.let { put("shelf", it.toJson()) }
        }.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) throw IOException()
        if (!directory.isDirectory && !directory.mkdir()) throw IOException()
        Os.chmod(directory.path, 0x1C0) // 0700
        val out = file.startWrite()
        try {
            Os.fchmod(out.fd, 0x180) // 0600
            out.write(bytes)
            out.fd.sync()
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    private companion object {
        const val MAX_BYTES = WEREAD_SHELF_LIMIT + 64 * 1024
    }
}
