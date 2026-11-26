package com.locusable.ginkgo

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Base64
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

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

    private fun readerLinks(context: Context) = context.getSharedPreferences("reader_links", Context.MODE_PRIVATE)

    fun clearReaderLinks(context: Context) {
        readerLinks(context).edit().clear().apply()
    }

    fun readerUri(context: Context, item: FolderItem): Uri {
        // Keep a stable identity for readers without putting the real path in the URI or ClipData.
        val source = item.uri.toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
        val token = Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val links = readerLinks(context)
        if (links.getString(token, null) != source) links.edit().putString(token, source).apply()
        return Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority(context))
            .appendPath("read").appendPath(token).appendPath(item.location.name).build()
    }

    fun readerFile(context: Context, uri: Uri): File? {
        val parts = uri.pathSegments
        if (uri.authority != authority(context) || parts.size != 3 || parts[0] != "read") return null
        val source = readerLinks(context).getString(parts[1], null) ?: return null
        return boundFile(context, Uri.parse(source))
    }

    private fun String.isWithin(root: File) = this == root.path || startsWith(root.path + File.separator)
    private fun authority(context: Context) = "${context.packageName}.files"
}

/** External readers get a seekable proxy, not a descriptor backed directly by the original path. */
class GinkgoFilesProvider : ContentProvider() {
    private val proxyHandler by lazy {
        Handler(HandlerThread("ginkgo-reader-proxy").apply { start() }.looper)
    }

    override fun onCreate() = true

    override fun getType(uri: Uri): String = fileMime(uri.lastPathSegment.orEmpty())

    private fun internalFile(context: Context, uri: Uri): File? =
        if (Binder.getCallingUid() == Process.myUid()) LegacyFolders.boundFile(context, uri) else null

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read-only file")
        val ctx = context ?: throw FileNotFoundException("File is not available")
        // Our cover decoder can use the original FD; even old URI grants cannot give it to another app.
        internalFile(ctx, uri)?.let { return ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
        val file = LegacyFolders.readerFile(ctx, uri) ?: throw FileNotFoundException("File is not available")
        val storage = ctx.getSystemService(StorageManager::class.java) ?: throw FileNotFoundException("No file proxy")
        val reader = try {
            RandomAccessFile(file, "r")
        } catch (_: IOException) {
            throw FileNotFoundException("File is not available")
        }
        fun closeReader() {
            try { reader.close() } catch (_: IOException) { }
        }
        try {
            val length = reader.length()
            return storage.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                object : ProxyFileDescriptorCallback() {
                    override fun onGetSize() = length

                    override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
                        reader.seek(offset)
                        reader.read(data, 0, size).coerceAtLeast(0)
                    } catch (error: IOException) {
                        throw ErrnoException("read", OsConstants.EIO, error)
                    }

                    override fun onRelease() = closeReader()
                },
                proxyHandler,
            )
        } catch (_: IOException) {
            closeReader()
            throw FileNotFoundException("Unable to share the file safely")
        } catch (_: RuntimeException) {
            closeReader()
            throw FileNotFoundException("Unable to share the file safely")
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val ctx = context ?: return null
        val file = internalFile(ctx, uri) ?: LegacyFolders.readerFile(ctx, uri) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)
        cursor.addRow(columns.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> uri.lastPathSegment ?: file.name
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
