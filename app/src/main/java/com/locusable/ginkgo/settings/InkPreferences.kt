package com.locusable.ginkgo.settings

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.locusable.ginkgo.shelf.HiddenBooks
import com.locusable.ginkgo.shelf.ShelfEntry
import com.locusable.ginkgo.shelf.local.FolderLocation

internal data class ShelfSources(val local: Boolean, val weRead: Boolean)

internal class InkPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("ink", Context.MODE_PRIVATE)
    private val folderOpens by lazy { context.getSharedPreferences("folder_open_times", Context.MODE_PRIVATE) }

    init {
        if (prefs.contains("recent_files") || prefs.contains("recent_expanded")) {
            prefs.edit().remove("recent_files").remove("recent_expanded").apply()
        }
        // The shelf no longer uses the former custom directory or its persisted tree grant.
        prefs.getString("folder_uri", null)?.let { old ->
            val uri = Uri.parse(old)
            if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                try {
                    context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) {
                    // The old grant may already have been revoked.
                }
            }
            prefs.edit().remove("folder_uri").remove("folder_name").apply()
        }
    }

    var showAppsTab: Boolean
        get() = prefs.getBoolean("show_apps_tab", true)
        set(value) { prefs.edit().putBoolean("show_apps_tab", value).apply() }

    var showShelfTab: Boolean
        get() = prefs.getBoolean("show_shelf_tab",
            prefs.getBoolean("show_folder_tab", true) || prefs.getBoolean("show_weread_tab", true))
        set(value) { prefs.edit().putBoolean("show_shelf_tab", value).apply() }

    var shelfSources: ShelfSources
        get() = ShelfSources(prefs.getBoolean("shelf_source_local", true), prefs.getBoolean("shelf_source_weread", true))
        set(value) {
            prefs.edit().putBoolean("shelf_source_local", value.local).putBoolean("shelf_source_weread", value.weRead).apply()
        }

    var showAboutTab: Boolean
        get() = prefs.getBoolean("show_about_tab", false)
        set(value) { prefs.edit().putBoolean("show_about_tab", value).apply() }

    fun folderOpenTimes(): Map<String, Long> = folderOpens.all.mapNotNull { (id, time) ->
        (time as? Long)?.let { id to it }
    }.toMap()

    fun recordBookOpen(location: FolderLocation) {
        folderOpens.edit().putLong(location.id, System.currentTimeMillis()).apply()
    }

    fun hiddenBooks(account: String?) = HiddenBooks(
        prefs.getStringSet("home_hidden_local", null).orEmpty(),
        account?.let { prefs.getStringSet(onlineHiddenKey(it), null) }.orEmpty(),
    )

    fun setBookHidden(entry: ShelfEntry, account: String?, hidden: Boolean) {
        if (entry.directory) return
        val key = when (entry) {
            is ShelfEntry.Local -> "home_hidden_local"
            is ShelfEntry.Online -> onlineHiddenKey(account ?: return)
        }
        val before = prefs.getStringSet(key, null).orEmpty()
        val after = if (hidden) before + entry.id else before - entry.id
        prefs.edit().apply {
            if (after.isEmpty()) remove(key) else putStringSet(key, after)
        }.apply()
    }

    private fun onlineHiddenKey(account: String) = "home_hidden_weread:$account"

    fun pinned() = prefs.getString("pins", "").orEmpty().lineSequence().filter { it.isNotEmpty() }.toList()

    fun savePins(keys: List<String>) = prefs.edit().putString("pins", keys.joinToString("\n")).apply()
}
