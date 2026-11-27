package com.locusable.ginkgo.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

private const val REPO = "locusable-studio/Ginkgo"
private val LATEST = URI("https://api.github.com/repos/$REPO/releases/latest")
private val STABLE_TAG = Regex("v[0-9]+\\.[0-9]+\\.[0-9]+")
internal val UPDATE_SHA256 = Regex("[0-9a-f]{64}")
private const val MAX_RELEASE_BYTES = 512 * 1024L
private const val MAX_MANIFEST_BYTES = 8192L
private const val MAX_APK_BYTES = 32 * 1024 * 1024L
private const val APK_RETENTION_MS = 24 * 60 * 60 * 1000L

internal data class ReleaseUpdate(
    val versionCode: Long,
    val versionName: String,
    val notes: String,
    val apkUrl: URI,
    val apkBytes: Long,
    val sha256: String,
)

internal class ReleaseUpdateClient(private val context: Context) {
    private val packages get() = context.packageManager

    @Suppress("DEPRECATION")
    fun installedCode(): Long = packages.getPackageInfo(context.packageName, 0).updateVersionCode()

    /** Null means the latest release predates the update manifest. An invalid response is an error. */
    fun latest(): ReleaseUpdate? {
        val release = readJson(LATEST, MAX_RELEASE_BYTES)
        val tag = release.getString("tag_name")
        check(STABLE_TAG.matches(tag) && !release.getBoolean("draft") && !release.getBoolean("prerelease"))
        val version = tag.removePrefix("v")
        val assets = release.getJSONArray("assets")
        val uploaded = (0 until assets.length()).map(assets::getJSONObject).filter { it.optString("state") == "uploaded" }
        val manifest = uploaded.firstOrNull { it.optString("name") == "update.json" } ?: return null
        val origin = "https://github.com/$REPO/releases/download/$tag/"
        val apkName = "Ginkgo-$version.apk"
        val apk = uploaded.first { it.optString("name") == apkName }
        val apkBytes = apk.getLong("size")
        check(apkBytes in 1..MAX_APK_BYTES && apk.getString("browser_download_url") == origin + apkName)
        check(manifest.getLong("size") in 1..MAX_MANIFEST_BYTES && manifest.getString("browser_download_url") == origin + "update.json")
        val metadata = readJson(URI(origin + "update.json"), MAX_MANIFEST_BYTES)
        val code = (metadata.opt("versionCode") as? Int)?.toLong() ?: error("Invalid update versionCode")
        val digest = metadata.getString("sha256")
        check(code > 0 && metadata.getString("versionName") == version && UPDATE_SHA256.matches(digest))
        val apkUrl = URI(metadata.getString("apkUrl"))
        check(apkUrl.toString() == origin + apkName)
        return ReleaseUpdate(code, version, release.optString("body", "").take(1500), apkUrl, apkBytes, digest)
    }

    /** Reports 10% steps based on bytes written; 100% means transfer finished and verification started. */
    fun download(release: ReleaseUpdate, onProgress: (Int) -> Unit): File {
        check(release.versionCode > installedCode())
        val directory = UpdateApks.directory(context)
        check(directory.isDirectory || directory.mkdirs() || directory.isDirectory)
        val expiredBefore = System.currentTimeMillis() - APK_RETENTION_MS
        directory.listFiles()?.filter { it.lastModified() < expiredBefore }?.forEach { it.delete() }
        val staged = File.createTempFile("download-", ".apk", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var reportedPercent = 0
            val bytes = request(release.apkUrl) { stream ->
                staged.outputStream().use { output ->
                    stream.readBounded(release.apkBytes) { buffer, length, total ->
                        digest.update(buffer, 0, length)
                        output.write(buffer, 0, length)
                        val percent = (total * 10 / release.apkBytes).toInt() * 10
                        if (percent > reportedPercent && percent < 100) {
                            reportedPercent = percent
                            onProgress(percent)
                        }
                    }
                }
            }
            check(bytes == release.apkBytes && !Thread.currentThread().isInterrupted)
            onProgress(100)
            check(digest.digest().toHexString() == release.sha256)
            verifyArchive(staged, release)
            check(!Thread.currentThread().isInterrupted)
            val apk = File(directory, "${release.sha256}.apk")
            check(staged.renameTo(apk))
            return apk
        } finally {
            staged.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyArchive(file: File, release: ReleaseUpdate) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = packages.getPackageInfo(context.packageName, flags)
        val archive = requireNotNull(packages.getPackageArchiveInfo(file.path, flags))
        val installedSigners = installed.updateSigners()
        check(archive.packageName == context.packageName && archive.updateVersionCode() == release.versionCode &&
            archive.versionName == release.versionName && release.versionCode > installed.updateVersionCode() &&
            installedSigners.isNotEmpty() && archive.updateSigners() == installedSigners)
    }

    private fun readJson(uri: URI, limit: Long): JSONObject = request(uri) { stream ->
        val out = ByteArrayOutputStream()
        stream.readBounded(limit) { buffer, length, _ -> out.write(buffer, 0, length) }
        JSONObject(out.toString(Charsets.UTF_8.name()))
    }

    private fun <T> request(initial: URI, read: (InputStream) -> T): T {
        var uri = initial
        repeat(5) {
            check(!Thread.currentThread().isInterrupted && uri.scheme == "https" && uri.rawUserInfo == null &&
                (uri.port == -1 || uri.port == 443) && uri.host in ALLOWED_HOSTS)
            val connection = uri.toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("User-Agent", "Ginkgo-Updater")
                if (uri == LATEST) connection.setRequestProperty("Accept", "application/vnd.github+json")
                when (val status = connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> return connection.inputStream.use(read)
                    301, 302, 303, 307, 308 -> uri = uri.resolve(requireNotNull(connection.getHeaderField("Location")))
                    else -> throw IOException("Update request failed: HTTP $status")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many update redirects")
    }

    private companion object {
        val ALLOWED_HOSTS = setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    }
}

private fun InputStream.readBounded(limit: Long, write: (ByteArray, Int, Long) -> Unit): Long {
    val buffer = ByteArray(16 * 1024)
    val deadline = SystemClock.elapsedRealtime() + 120_000L
    var size = 0L
    while (true) {
        check(!Thread.currentThread().isInterrupted && SystemClock.elapsedRealtime() < deadline)
        val length = read(buffer)
        if (length == -1) return size
        size += length
        check(size <= limit)
        write(buffer, length, size)
    }
}

@Suppress("DEPRECATION")
private fun PackageInfo.updateVersionCode(): Long = if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

@Suppress("DEPRECATION")
private fun PackageInfo.updateSigners(): Set<String> =
    (if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures)
        .orEmpty().mapTo(HashSet()) { it.toCharsString() }
