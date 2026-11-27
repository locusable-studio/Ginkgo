package com.locusable.ginkgo.shelf

import com.locusable.ginkgo.shelf.local.FolderItem
import com.locusable.ginkgo.shelf.weread.WeReadEntry
import java.text.Collator

internal sealed interface ShelfEntry {
    val name: String
    val id: String
    val directory: Boolean

    data class Local(val item: FolderItem) : ShelfEntry {
        override val name get() = item.location.name
        override val id get() = item.location.id
        override val directory get() = item.isDirectory
    }

    data class Online(val entry: WeReadEntry) : ShelfEntry {
        override val name get() = entry.name
        override val id get() = entry.id
        override val directory get() = entry is WeReadEntry.Group
    }

    fun openedAt(local: Map<String, Long>, online: Map<String, Long>, directories: Map<String, Long> = emptyMap()): Long = when (this) {
        is Local -> (if (directory) directories else local)[id] ?: 0L
        is Online -> when (val value = entry) {
            is WeReadEntry.Book -> value.openedAt(online)
            is WeReadEntry.Group -> value.books.maxOfOrNull { it.openedAt(online) } ?: 0L
        }
    }
}

/** Strip a local book's file suffix when displaying its title. */
internal val ShelfEntry.displayTitle: String
    get() {
        val local = (this as? ShelfEntry.Local)?.item ?: return name
        if (local.isDirectory) return name
        val stem = name.substringBeforeLast('.')
        val hasSuffix = local.extension.length in 1..8 && local.extension.all { it in 'a'..'z' || it in '0'..'9' }
        return if (stem.isNotBlank() && hasSuffix) stem else name
    }

private fun WeReadEntry.Book.openedAt(opened: Map<String, Long>): Long {
    // Compare service timestamps and launcher timestamps on the same epoch-millisecond scale.
    val serverTime = if (readAt in 1L until 100_000_000_000L) readAt * 1000 else readAt
    return maxOf(serverTime, opened[id] ?: 0L)
}

internal fun <T : ShelfEntry> List<T>.orderedOnShelf(
    sort: ShelfSort, local: Map<String, Long>, online: Map<String, Long>, hidden: HiddenBooks? = null,
    directories: Map<String, Long> = emptyMap(),
): List<T> {
    val byName = compareBy<T, String>(Collator.getInstance()) { it.name }
        .thenBy { it.name }.thenBy { it.id }
    val ordered = if (sort == ShelfSort.NAME) sortedWith(byName) else map { it to it.openedAt(local, online, directories) }
        .sortedWith(compareByDescending<Pair<T, Long>> { it.second }.then { a, b ->
            byName.compare(a.first, b.first)
        }).map { it.first }
    if (hidden == null || (hidden.local.isEmpty() && hidden.online.isEmpty())) return ordered
    val (visible, hiddenEntries) = ordered.partition { it !in hidden }
    return visible + hiddenEntries
}

internal data class HiddenBooks(val local: Set<String>, val online: Set<String>) {
    operator fun contains(entry: ShelfEntry): Boolean = when (entry) {
        is ShelfEntry.Local -> !entry.directory && entry.id in local
        is ShelfEntry.Online -> !entry.directory && entry.id in online
    }
}

internal fun recentShelfBooks(
    localBooks: List<FolderItem>, onlineBooks: List<WeReadEntry.Book>,
    local: Map<String, Long>, online: Map<String, Long>, hidden: HiddenBooks, limit: Int,
): List<ShelfEntry> = (localBooks.map(ShelfEntry::Local) + onlineBooks.map(ShelfEntry::Online))
    .filter { !it.directory && it !in hidden && it.openedAt(local, online) > 0 }
    .orderedOnShelf(ShelfSort.LAST_OPENED, local, online).take(limit)
