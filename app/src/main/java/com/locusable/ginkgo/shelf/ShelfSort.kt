package com.locusable.ginkgo.shelf

import com.locusable.ginkgo.shelf.local.FolderItem

internal enum class ShelfSort(val label: String) {
    NAME("按名称"), LAST_OPENED("按最近打开"),
}

// FolderAccess provides name order. Stable sorting preserves it for equal or missing open times.
internal fun List<FolderItem>.orderedBy(sort: ShelfSort, times: Map<String, Long>): List<FolderItem> = when (sort) {
    ShelfSort.NAME -> this
    ShelfSort.LAST_OPENED -> if (times.isEmpty()) this else sortedByDescending { times[it.location.id] ?: Long.MIN_VALUE }
}
