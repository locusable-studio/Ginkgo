package com.locusable.ginkgo.shelf.weread

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal const val WEREAD_COVER_URL_LIMIT = 4096

internal fun String.isWeReadUploadId() = startsWith("CB_")

internal sealed interface WeReadEntry {
    val id: String
    val name: String

    data class Book(
        override val id: String, override val name: String, val readAt: Long,
        val cover: String?, val readerLink: String?,
    ) : WeReadEntry {
        val isUploaded get() = id.isWeReadUploadId()
    }

    data class Group(
        override val id: String, override val name: String, val books: List<Book>,
    ) : WeReadEntry
}

internal data class WeReadShelf(
    val books: List<WeReadEntry.Book>, val archives: List<Archive>?,
) {
    data class Archive(val id: String, val name: String, val bookIds: List<String>)

    val groups: List<WeReadEntry.Group> = books.associateBy { it.id }.let { indexed ->
        archives.orEmpty().map { WeReadEntry.Group(it.id, it.name, it.bookIds.mapNotNull(indexed::get)) }
    }
    val root: List<WeReadEntry> = groups.flatMapTo(HashSet()) { g -> g.books.map { it.id } }.let { grouped ->
        groups + books.filterNot { it.id in grouped }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("books", JSONArray().apply {
            books.forEach { b -> put(JSONObject().put("bookId", b.id).put("title", b.name)
                .put("readUpdateTime", b.readAt).put("readerLink", b.readerLink).apply {
                    if (!b.isUploaded) put("cover", b.cover)
                }) }
        })
        archives?.let { list ->
            put("archive", JSONArray().apply {
                list.forEach { a -> put(JSONObject().put("archiveId", a.id).put("name", a.name)
                    .put("bookIds", JSONArray(a.bookIds))) }
            })
        }
    }

    companion object {
        fun parse(json: JSONObject, cached: WeReadShelf? = null): WeReadShelf {
            val rows = json.optJSONArray("books") ?: throw JSONException("Missing shelf books")
            val books = (0 until rows.length()).mapNotNull { i ->
                val item = rows.getJSONObject(i)
                val id = item.value("bookId", 128) ?: return@mapNotNull null // Non-book entrance.
                val name = item.value("title", 512) ?: throw JSONException("Missing book title")
                WeReadEntry.Book(id, name, item.optLong("readUpdateTime", 0).coerceAtLeast(0),
                    item.value("cover", WEREAD_COVER_URL_LIMIT), item.value("readerLink", 2048))
            }.distinctBy { it.id }
            // Omission differs from a confirmed empty archive. A partial response cannot erase local groups.
            val archives = if (json.isNull("archive")) cached?.archives else parseArchives(json.getJSONArray("archive"))
            return WeReadShelf(books, archives)
        }

        private fun parseArchives(rows: JSONArray): List<Archive> {
            val inferred = mutableMapOf<String, Int>()
            val groups = linkedMapOf<String, Archive>()
            for (i in 0 until rows.length()) {
                val item = rows.getJSONObject(i)
                val name = item.value("name", 512) ?: throw JSONException("Missing folder name")
                val id = item.value("archiveId", 640) ?: item.value("id", 128) ?: run {
                    val occurrence = inferred.getOrDefault(name, 0)
                    inferred[name] = occurrence + 1
                    "name:$occurrence:$name"
                }
                // Null/omitted book membership is valid for audio-only or empty groups.
                val members = if (item.isNull("bookIds")) null else item.getJSONArray("bookIds")
                val ids = if (members == null) emptyList() else (0 until members.length()).mapNotNull { index ->
                    members.optString(index, "").takeIf { !members.isNull(index) && it.length in 1..128 }
                }
                val existing = groups[id]
                groups[id] = Archive(id, existing?.name ?: name, (existing?.bookIds.orEmpty() + ids).distinct())
            }
            return groups.values.toList()
        }
    }
}

internal fun JSONObject.value(key: String, max: Int): String? =
    (opt(key) as? String ?: (opt(key) as? Number)?.toString())?.trim()?.takeIf { it.length in 1..max }
