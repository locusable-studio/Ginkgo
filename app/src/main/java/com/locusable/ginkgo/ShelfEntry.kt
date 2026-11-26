package com.locusable.ginkgo

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

    fun openedAt(local: Map<String, Long>, online: Map<String, Long>): Long = when (this) {
        is Local -> local[id] ?: 0L
        is Online -> when (val value = entry) {
            is WeReadEntry.Book -> value.openedAt(online)
            is WeReadEntry.Group -> value.books.maxOfOrNull { it.openedAt(online) } ?: 0L
        }
    }
}

private fun WeReadEntry.Book.openedAt(opened: Map<String, Long>): Long {
    // Compare service timestamps and launcher timestamps on the same epoch-millisecond scale.
    val serverTime = if (readAt in 1L until 100_000_000_000L) readAt * 1000 else readAt
    return maxOf(serverTime, opened[id] ?: 0L)
}

internal fun <T : ShelfEntry> List<T>.orderedOnShelf(
    sort: FolderSort, local: Map<String, Long>, online: Map<String, Long>,
): List<T> {
    val byName = compareBy<T, String>(Collator.getInstance()) { it.name }
        .thenBy { it.name }.thenBy { it.id }
    if (sort == FolderSort.NAME) return sortedWith(byName)
    return map { it to it.openedAt(local, online) }
        .sortedWith(compareByDescending<Pair<T, Long>> { it.second }.then { a, b ->
            byName.compare(a.first, b.first)
        }).map { it.first }
}

internal fun recentShelfBooks(
    localBooks: List<FolderItem>, onlineBooks: List<WeReadEntry.Book>,
    local: Map<String, Long>, online: Map<String, Long>, limit: Int,
): List<ShelfEntry> = (localBooks.map(ShelfEntry::Local) + onlineBooks.map(ShelfEntry::Online))
    .filter { !it.directory && it.openedAt(local, online) > 0 }
    .orderedOnShelf(FolderSort.LAST_OPENED, local, online).take(limit)
