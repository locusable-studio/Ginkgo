package com.locusable.ginkgo.shelf

import com.locusable.ginkgo.shelf.local.FolderItem

internal enum class ShelfSort {
    NAME, LAST_OPENED,
}

// FolderAccess provides name order. Stable sorting preserves it for equal or missing open times.
internal fun List<FolderItem>.orderedBy(sort: ShelfSort, times: Map<String, Long>): List<FolderItem> = when (sort) {
    ShelfSort.NAME -> this
    ShelfSort.LAST_OPENED -> if (times.isEmpty()) this else sortedByDescending { times[it.location.id] ?: Long.MIN_VALUE }
}
