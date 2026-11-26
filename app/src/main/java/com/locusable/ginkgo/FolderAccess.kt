package com.locusable.ginkgo

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import java.io.File
import java.text.Collator
import java.util.Locale

internal data class BoundFolder(val uri: Uri, val name: String) {
    val isLocal get() = uri.scheme == ContentResolver.SCHEME_FILE
    val root: FolderLocation get() = FolderLocation(if (isLocal) requireNotNull(uri.path) else DocumentsContract.getTreeDocumentId(uri), name)
    val documentUri: Uri get() = DocumentsContract.buildDocumentUriUsingTree(uri, root.id)
}

internal data class FolderLocation(val id: String, val name: String)

internal data class FolderItem(
    val location: FolderLocation,
    val uri: Uri,
    val mimeType: String,
    val lastModified: Long = 0L,
) {
    val isDirectory get() = mimeType == Document.MIME_TYPE_DIR
    val extension = location.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val coverFormat get() = when {
        isDirectory -> null
        extension == "epub" || mimeType == "application/epub+zip" -> CoverFormat.EPUB
        extension == "pdf" || mimeType == "application/pdf" -> CoverFormat.PDF
        else -> null
    }

    fun viewIntent(): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, fileMime(location.name, mimeType))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .apply { clipData = ClipData.newRawUri(location.name, uri) }
}

internal fun fileMime(name: String, provided: String = ""): String = when (val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
    "epub" -> "application/epub+zip"
    "mobi" -> "application/x-mobipocket-ebook"
    "azw", "azw3" -> "application/vnd.amazon.ebook"
    else -> provided.takeUnless { it.isBlank() || it == "application/octet-stream" }
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        ?: "application/octet-stream"
}

internal enum class FolderSort(val label: String) {
    NAME("按文件名"), LAST_OPENED("按上次打开时间"), TYPE("按类型"),
}

private val directoriesFirst = compareBy<FolderItem> { !it.isDirectory }

// FolderAccess provides directories first and filename order within each group. Stable sorting keeps
// that name order for ties, including files of the same type and items that have never been opened.
internal fun List<FolderItem>.orderedBy(sort: FolderSort, times: Map<String, Long>): List<FolderItem> = when (sort) {
    FolderSort.NAME -> this
    FolderSort.LAST_OPENED -> if (times.isEmpty()) this else sortedWith(
        directoriesFirst.thenByDescending { times[it.location.id] ?: Long.MIN_VALUE },
    )
    FolderSort.TYPE -> sortedWith(directoriesFirst
        .thenBy { !it.isDirectory && it.extension.isEmpty() }
        .thenBy { if (it.isDirectory) "" else it.extension })
}

internal sealed interface FolderContents {
    data object Loading : FolderContents
    data object Unavailable : FolderContents
    data class Items(val entries: List<FolderItem>) : FolderContents
}

internal class FolderAccess(private val context: Context) {
    private val resolver get() = context.contentResolver

    fun picker(current: BoundFolder?) = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        .apply {
            if (current != null && !current.isLocal) putExtra(DocumentsContract.EXTRA_INITIAL_URI, current.documentUri)
        }

    fun retain(result: Intent, previous: BoundFolder?): BoundFolder {
        result.getStringExtra(LegacyFolders.EXTRA_LOCAL_DIRECTORY)?.let { path ->
            val directory = requireNotNull(LegacyFolders.directory(File(path)))
            val name = if (directory == LegacyFolders.root()) "内部存储" else directory.name
            return BoundFolder(Uri.fromFile(directory), name)
        }
        val uri = requireNotNull(result.data)
        require(DocumentsContract.isTreeUri(uri) && result.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            val id = DocumentsContract.getTreeDocumentId(uri)
            val doc = DocumentsContract.buildDocumentUriUsingTree(uri, id)
            return BoundFolder(uri, folderName(doc))
        } catch (exception: RuntimeException) {
            if (previous?.uri != uri) release(uri)
            throw exception
        }
    }

    fun release(uri: Uri) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return
        try {
            resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // A deleted or revoked tree may no longer have a grant to release.
        }
    }

    private fun folderName(uri: Uri): String = resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE), null, null, null)
        ?.use { cursor ->
            check(cursor.moveToFirst() && cursor.getString(1) == Document.MIME_TYPE_DIR)
            cursor.getString(0)?.takeIf { it.isNotBlank() } ?: "自定义目录"
        } ?: error("Directory is not available")

    fun contents(binding: BoundFolder, location: FolderLocation): FolderContents = try {
        val children = if (binding.isLocal) LegacyFolders.items(context, binding, location) else documentItems(binding, location)
        val collator = Collator.getInstance()
        val byName = compareBy<FolderItem, String>(collator) { it.location.name }
            .thenBy { it.location.name }.thenBy { it.location.id }
        FolderContents.Items(children.sortedWith(directoriesFirst.then(byName)))
    } catch (_: RuntimeException) {
        FolderContents.Unavailable
    }

    private fun documentItems(binding: BoundFolder, location: FolderLocation): List<FolderItem> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(binding.uri, location.id)
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED,
        )
        val children = resolver.query(uri, projection, null, null, null)?.use { cursor ->
            val modified = cursor.getColumnIndex(Document.COLUMN_LAST_MODIFIED)
            buildList<FolderItem> {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: id.substringAfterLast('/')
                    val doc = DocumentsContract.buildDocumentUriUsingTree(binding.uri, id)
                    add(FolderItem(FolderLocation(id, name), doc, cursor.getString(2).orEmpty(),
                        if (modified >= 0 && !cursor.isNull(modified)) cursor.getLong(modified) else 0L))
                }
            }
        } ?: error("Directory is not available")
        if (children.isEmpty()) folderName(DocumentsContract.buildDocumentUriUsingTree(binding.uri, location.id))
        return children
    }
}
