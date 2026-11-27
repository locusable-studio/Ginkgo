package com.locusable.ginkgo

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.net.Uri
import java.text.Collator

internal const val MAX_PINS = 4
internal const val MAX_RECENT_BOOKS = 4

internal data class AppEntry(
    val component: ComponentName,
    val label: String,
    val stockHome: Boolean = false,
    val hasIcon: Boolean = false,
) {
    val key: String get() = component.flattenToString()
    val isAndroidSettings: Boolean get() = component.packageName == "com.android.settings" && component.shortClassName == ".Settings"
    val isDeviceSettings: Boolean get() = component.packageName == "com.mgs.settings" && component.shortClassName == ".MgsSettingsActivity"
    val isFileManager: Boolean get() = when (component.packageName) {
        "com.softwinner.explore", "com.android.documentsui", "com.google.android.documentsui", "com.mi.android.globalFileexplorer" -> true
        else -> false
    }
    val isWeRead: Boolean get() = component.packageName == "com.tencent.weread" || component.packageName == "com.tencent.weread.eink"

    fun launchIntent() = Intent(Intent.ACTION_MAIN)
        .addCategory(if (stockHome) Intent.CATEGORY_HOME else Intent.CATEGORY_LAUNCHER)
        .setComponent(component)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}

internal class AppCatalog(private val context: Context) {
    private val pm get() = context.packageManager

    fun apps(): List<AppEntry> {
        val homes = candidates(Intent.CATEGORY_HOME)
        val stock = homes.filter {
            val activity = it.activityInfo
            activity.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                !activity.name.contains("FallbackHome", ignoreCase = true) &&
                !activity.name.contains("ResolverActivity", ignoreCase = true)
        }.let { list -> list.firstOrNull { isDuokan(it.activityInfo.packageName) } ?: list.firstOrNull() }
            ?.let { makeEntry(it, stock = true) }

        val launchable = candidates(Intent.CATEGORY_LAUNCHER).map { makeEntry(it) }
        val visible = if (launchable.any { it.isDeviceSettings }) launchable.filterNot { it.isAndroidSettings } else launchable
        val collator = Collator.getInstance()
        return (listOfNotNull(stock) + visible.sortedWith(compareBy(collator) { it.label })).distinctBy { it.key }
    }

    fun icon(entry: AppEntry): Drawable? {
        if (!entry.hasIcon) return null
        return try {
            pm.getActivityIcon(entry.component)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: Resources.NotFoundException) {
            null
        }
    }

    private fun candidates(category: String): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
        @Suppress("DEPRECATION")
        return pm.queryIntentActivities(intent, if (category == Intent.CATEGORY_HOME) PackageManager.MATCH_DEFAULT_ONLY else 0)
            .filter { info ->
                info.activityInfo?.let {
                    it.exported && it.packageName != context.packageName && it.packageName != "com.mgs.factorytest"
                } == true
            }
    }

    private fun isDuokan(packageName: String) =
        packageName.contains("duokan", ignoreCase = true) || packageName.contains("mireader", ignoreCase = true)

    private fun makeEntry(info: ResolveInfo, stock: Boolean = false): AppEntry {
        val activity = info.activityInfo
        val label = if (stock) {
            if (isDuokan(activity.packageName)) "多看桌面" else "原厂桌面"
        } else info.loadLabel(pm).toString().trim().ifEmpty { activity.packageName }
        return AppEntry(ComponentName(activity.packageName, activity.name), label, stock, hasIcon = info.iconResource != 0)
    }
}

internal data class ShelfSources(val local: Boolean, val weRead: Boolean)

internal class InkPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("ink", Context.MODE_PRIVATE)
    private val folderOpens by lazy { context.getSharedPreferences("folder_open_times", Context.MODE_PRIVATE) }
    private val weReadOpens by lazy { context.getSharedPreferences("weread_open_times", Context.MODE_PRIVATE) }

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
        get() = prefs.getBoolean("show_about_tab", true)
        set(value) { prefs.edit().putBoolean("show_about_tab", value).apply() }

    var homeGrid: Boolean
        get() = prefs.getBoolean("home_grid", false)
        set(value) { prefs.edit().putBoolean("home_grid", value).apply() }

    var appsGrid: Boolean
        get() = prefs.getBoolean("apps_grid", false)
        set(value) { prefs.edit().putBoolean("apps_grid", value).apply() }

    var shelfGrid: Boolean
        get() = prefs.getBoolean("shelf_grid", prefs.getBoolean("folder_shelf", prefs.getBoolean("weread_shelf", false)))
        set(value) { prefs.edit().putBoolean("shelf_grid", value).apply() }

    var shelfSort: FolderSort
        get() {
            val saved = prefs.getString("shelf_sort", null) ?: prefs.getString("folder_sort", null)
            val legacyRecent = prefs.getBoolean("folder_sort_by_opened", prefs.getBoolean("weread_sort_recent", false))
            return FolderSort.entries.firstOrNull { it.name == saved }
                ?: if (legacyRecent) FolderSort.LAST_OPENED else FolderSort.NAME
        }
        set(value) { prefs.edit().putString("shelf_sort", value.name).apply() }

    fun folderOpenTimes(): Map<String, Long> = folderOpens.all.mapNotNull { (id, time) ->
        (time as? Long)?.let { id to it }
    }.toMap()

    fun recordFolderOpen(location: FolderLocation) {
        folderOpens.edit().putLong(location.id, System.currentTimeMillis()).apply()
    }

    fun weReadOpenTimes(account: String?): Map<String, Long> {
        if (account == null || weReadOpens.getString("account", null) != account) return emptyMap()
        return weReadOpens.all.mapNotNull { (key, time) ->
            (time as? Long)?.let { key.removePrefix("book:") to it }
        }.toMap()
    }

    fun recordWeReadOpen(account: String, bookId: String) {
        val edit = weReadOpens.edit()
        if (weReadOpens.getString("account", null) != account) edit.clear().putString("account", account)
        edit.putLong("book:$bookId", System.currentTimeMillis()).apply()
    }

    fun clearWeReadOpenTimes() = weReadOpens.edit().clear().apply()

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

    fun seedPins(apps: List<AppEntry>) {
        if (prefs.contains("pins")) return
        val base = listOfNotNull(
            apps.firstOrNull { it.stockHome },
            apps.firstOrNull { it.isFileManager },
            apps.firstOrNull { it.isDeviceSettings } ?: apps.firstOrNull { it.isAndroidSettings },
        )
        savePins(base.map { it.key }.distinct())
    }

    fun savePins(keys: List<String>) = prefs.edit().putString("pins", keys.take(MAX_PINS).joinToString("\n")).apply()
}
