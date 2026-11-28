package com.locusable.ginkgo.shelf.weread

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.system.Os
import android.util.AtomicFile
import android.util.Base64
import java.io.ByteArrayOutputStream
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
    private val profileFile = AtomicFile(File(directory, "profile-v1"))

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
        write(file, bytes)
    }

    fun loadProfile(account: String): WeReadWeb.Profile? {
        val json = try {
            profileFile.openRead().use { JSONObject(it.readLimited(MAX_PROFILE_BYTES).toString(Charsets.UTF_8)) }
        } catch (_: Exception) {
            return null
        }
        if (json.value("account", 64) != account) return null
        val name = json.value("name", 128)
        val avatar = json.value("avatar", MAX_PROFILE_BYTES)?.let { encoded ->
            try {
                val bytes = Base64.decode(encoded, Base64.NO_WRAP)
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                if (options.outWidth in 1..MAX_AVATAR_EDGE && options.outHeight in 1..MAX_AVATAR_EDGE) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } else null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        return if (name != null || avatar != null) WeReadWeb.Profile(name, avatar) else null
    }

    fun saveProfile(account: String, profile: WeReadWeb.Profile) {
        val bytes = JSONObject().apply {
            put("account", account)
            profile.name?.let { put("name", it) }
            profile.avatar?.let { avatar ->
                if (avatar.width !in 1..MAX_AVATAR_EDGE || avatar.height !in 1..MAX_AVATAR_EDGE) throw IOException()
                val buffer = ByteArrayOutputStream()
                if (!avatar.compress(Bitmap.CompressFormat.PNG, 100, buffer)) throw IOException()
                put("avatar", Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP))
            }
        }.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_PROFILE_BYTES) throw IOException()
        write(profileFile, bytes)
    }

    fun clearProfile() = profileFile.delete()

    private fun write(file: AtomicFile, bytes: ByteArray) {
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
        const val MAX_PROFILE_BYTES = 128 * 1024
        const val MAX_AVATAR_EDGE = 128
    }
}
