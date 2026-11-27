package com.locusable.ginkgo.shelf.local

import android.os.Environment
import java.io.File
import java.io.IOException
import java.text.Collator
import java.util.Locale

internal data class FolderLocation(val id: String, val name: String)

internal enum class CoverFormat { EPUB, PDF }

internal data class FolderItem(
    val location: FolderLocation,
    val isDirectory: Boolean,
    val lastModified: Long,
    val size: Long,
) {
    val extension = bookExtension(location.name)
    val coverFormat get() = if (isDirectory) null else when (extension) {
        "epub" -> CoverFormat.EPUB
        "pdf" -> CoverFormat.PDF
        else -> null
    }
}

private fun bookExtension(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

internal sealed interface FolderContents {
    data object Loading : FolderContents
    data object PermissionRequired : FolderContents
    data object Unsupported : FolderContents
    data object Missing : FolderContents
    data object Unavailable : FolderContents
    data class Items(val entries: List<FolderItem>) : FolderContents
}

/** The Xiaomi Duokan scanner indexes Books in primary shared storage. Never follow aliases out of it. */
internal object FolderAccess {
    const val DIRECTORY = "Books"

    // Matches ReaderUtils.checkSupportFormat in the EPD106 firmware.
    private val bookTypes = setOf(
        "epub", "pdf", "txt", "doc", "docx", "ppt", "pptx", "xls", "xlsx",
        "wps", "csv", "et", "dps", "html", "mht",
    )

    @Suppress("DEPRECATION")
    private fun root() = File(Environment.getExternalStorageDirectory().canonicalFile, DIRECTORY)

    private fun File.isShelfPath(root: File) =
        (this == root || path.startsWith(root.path + File.separator)) && canonicalFile == this

    fun contents(location: FolderLocation?): FolderContents = try {
        val root = root()
        val directory = location?.let { File(it.id) } ?: root
        when {
            !directory.isShelfPath(root) -> FolderContents.Unavailable
            location == null && !directory.exists() -> FolderContents.Missing
            else -> {
                val children = directory.listFiles()?.mapNotNull { file ->
                    if (file.isHidden || file.name.startsWith('.') || !file.isShelfPath(root) || !file.canRead()) {
                        return@mapNotNull null
                    }
                    val isDirectory = file.isDirectory
                    if (!isDirectory && (!file.isFile || bookExtension(file.name) !in bookTypes)) return@mapNotNull null
                    FolderItem(FolderLocation(file.path, file.name), isDirectory, file.lastModified(),
                        if (isDirectory) 0L else file.length())
                }
                val byName = compareBy<FolderItem, String>(Collator.getInstance()) { it.location.name }
                    .thenBy { it.location.name }
                if (children == null) FolderContents.Unavailable else FolderContents.Items(children.sortedWith(byName))
            }
        }
    } catch (_: IOException) {
        FolderContents.Unavailable
    } catch (_: SecurityException) {
        FolderContents.Unavailable
    }

    fun recentBooks(opened: Map<String, Long>, limit: Int): List<FolderItem> = opened.asSequence()
        .filter { (_, time) -> time > 0 }
        .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
        .mapNotNull { (path) ->
            val name = File(path).name
            if (name.startsWith('.') || bookExtension(name) !in bookTypes) return@mapNotNull null
            val location = FolderLocation(path, name)
            val file = book(location) ?: return@mapNotNull null
            FolderItem(location, false, file.lastModified(), file.length())
        }.take(limit).toList()

    fun directoryOpenTimes(opened: Map<String, Long>): Map<String, Long> {
        if (opened.isEmpty()) return emptyMap()
        val root = shelfRoot() ?: return emptyMap()
        return buildMap {
            for ((path, time) in opened) {
                if (Thread.currentThread().isInterrupted) break
                if (time <= 0) continue
                var parent = shelfBook(path, root)?.parentFile
                while (parent != null && parent != root) {
                    val id = parent.path
                    if (time > (get(id) ?: 0L)) put(id, time)
                    parent = parent.parentFile
                }
            }
        }
    }

    fun book(location: FolderLocation): File? = shelfRoot()?.let { shelfBook(location.id, it) }

    private fun shelfRoot(): File? = try {
        root()
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun shelfBook(path: String, root: File): File? = try {
        val file = File(path)
        file.takeIf { it != root && it.isShelfPath(root) && it.isFile && it.canRead() && bookExtension(it.name) in bookTypes }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}
