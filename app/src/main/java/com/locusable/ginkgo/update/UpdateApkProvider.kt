package com.locusable.ginkgo.update

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

internal const val UPDATE_APK_MIME = "application/vnd.android.package-archive"

internal object UpdateApks {
    fun directory(context: Context) = File(context.cacheDir, "updates")
    fun authority(context: Context) = "${context.packageName}.update.apk"
    fun uri(context: Context, apk: File): Uri = Uri.Builder().scheme("content")
        .authority(authority(context)).appendPath(apk.name).build()

    fun file(context: Context, name: String): File? {
        if (!name.endsWith(".apk") || !UPDATE_SHA256.matches(name.removeSuffix(".apk"))) return null
        val directory = directory(context).canonicalFile
        return File(directory, name).canonicalFile.takeIf { it.parentFile == directory && it.name == name && it.isFile }
    }
}

/** Only a verified, immutable update APK can be shared, by exact read grant to the installer. */
class UpdateApkProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri) = UPDATE_APK_MIME

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException()
        return ParcelFileDescriptor.open(apk(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = apk(uri)
        val columns = (projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            .filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }
        return MatrixCursor(columns.toTypedArray(), 1).apply {
            addRow(columns.map { if (it == OpenableColumns.SIZE) file.length() else "Ginkgo.apk" })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    private fun apk(uri: Uri): File {
        val app = requireNotNull(context)
        val name = uri.pathSegments.singleOrNull()
        if (uri.scheme != "content" || uri.authority != UpdateApks.authority(app) || name == null) throw FileNotFoundException()
        return UpdateApks.file(app, name) ?: throw FileNotFoundException()
    }
}
