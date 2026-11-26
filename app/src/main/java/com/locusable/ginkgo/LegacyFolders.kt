package com.locusable.ginkgo

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** Older firmware without an external-storage DocumentsProvider still exposes shared storage to the user. */
internal object LegacyFolders {
    const val EXTRA_LOCAL_DIRECTORY = "com.locusable.ginkgo.LOCAL_DIRECTORY"

    @Suppress("DEPRECATION")
    fun root(): File = Environment.getExternalStorageDirectory().canonicalFile

    fun directory(file: File): File? = shared(file)?.takeIf { it.isDirectory && it.canRead() }

    private fun shared(file: File): File? = try {
        val root = root()
        val candidate = file.canonicalFile
        val path = candidate.path
        val privateRoots = listOf(File(root, "Android/data"), File(root, "Android/obb"))
        candidate.takeIf { path.isWithin(root) && privateRoots.none { path.isWithin(it) } }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    fun directories(parent: File): List<File>? {
        val valid = directory(parent) ?: return null
        return valid.listFiles()?.mapNotNull { if (it.isHidden) null else directory(it) }?.distinctBy { it.path }
    }

    fun items(context: Context, binding: BoundFolder, location: FolderLocation): List<FolderItem> {
        val root = requireNotNull(directory(File(requireNotNull(binding.uri.path))))
        val directory = requireNotNull(directory(File(location.id)))
        require(directory.path.isWithin(root))
        return checkNotNull(directory.listFiles()).mapNotNull { file ->
            if (file.isHidden) return@mapNotNull null
            val item = shared(file)?.takeIf { it.path.isWithin(root) && it != root && it != directory && it.canRead() }
                ?: return@mapNotNull null
            val directoryEntry = item.isDirectory
            if (!directoryEntry && !item.isFile) return@mapNotNull null
            val uri = Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority(context))
                .appendPath("file").appendPath(root.path).appendPath(item.relativeTo(root).path).build()
            FolderItem(FolderLocation(item.path, file.name), uri,
                if (directoryEntry) Document.MIME_TYPE_DIR else fileMime(file.name),
                item.lastModified())
        }.distinctBy { it.location.id }
    }

    fun boundFile(context: Context, uri: Uri): File? {
        val parts = uri.pathSegments
        if (parts.size != 3 || parts[0] != "file" || uri.authority != authority(context)) return null
        val binding = InkPreferences(context).boundFolder()?.takeIf { it.isLocal } ?: return null
        val root = directory(File(binding.uri.path ?: return null)) ?: return null
        if (parts[1] != root.path || File(parts[2]).isAbsolute) return null
        return shared(File(root, parts[2]))?.takeIf { it.path.isWithin(root) && it.isFile && it.canRead() }
    }

    private fun String.isWithin(root: File) = this == root.path || startsWith(root.path + File.separator)
    private fun authority(context: Context) = "${context.packageName}.files"
}

/** Shares only the requested file, read-only, and only while its root remains bound. */
class GinkgoFilesProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri): String = fileMime(uri.lastPathSegment.orEmpty())

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read-only file")
        val file = context?.let { LegacyFolders.boundFile(it, uri) } ?: throw FileNotFoundException("File is not available")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val file = context?.let { LegacyFolders.boundFile(it, uri) } ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)
        cursor.addRow(columns.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        })
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read-only files")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only files")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only files")
}
