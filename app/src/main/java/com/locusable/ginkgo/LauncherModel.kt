package com.locusable.ginkgo

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.net.Uri
import java.text.Collator

internal const val MAX_PINS = 8

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

internal class InkPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("ink", Context.MODE_PRIVATE)
    private val folderOpens by lazy { context.getSharedPreferences("folder_open_times", Context.MODE_PRIVATE) }

    init {
        // Delete only the keys used by the removed file history feature on older installs.
        if (prefs.contains("recent_files") || prefs.contains("recent_expanded")) {
            prefs.edit().remove("recent_files").remove("recent_expanded").apply()
        }
    }

    var showAppsTab: Boolean
        get() = prefs.getBoolean("show_apps_tab", true)
        set(value) { prefs.edit().putBoolean("show_apps_tab", value).apply() }

    var showFolderTab: Boolean
        get() = prefs.getBoolean("show_folder_tab", false)
        set(value) { prefs.edit().putBoolean("show_folder_tab", value).apply() }

    var showAboutTab: Boolean
        get() = prefs.getBoolean("show_about_tab", true)
        set(value) { prefs.edit().putBoolean("show_about_tab", value).apply() }

    var homeGrid: Boolean
        get() = prefs.getBoolean("home_grid", false)
        set(value) { prefs.edit().putBoolean("home_grid", value).apply() }

    var folderShelf: Boolean
        get() = prefs.getBoolean("folder_shelf", false)
        set(value) { prefs.edit().putBoolean("folder_shelf", value).apply() }

    var folderSort: FolderSort
        get() {
            val saved = prefs.getString("folder_sort", null)
            return FolderSort.entries.firstOrNull { it.name == saved }
                ?: if (prefs.getBoolean("folder_sort_by_opened", false)) FolderSort.LAST_OPENED else FolderSort.NAME
        }
        set(value) { prefs.edit().putString("folder_sort", value.name).remove("folder_sort_by_opened").apply() }

    fun folderOpenTimes(): Map<String, Long> = folderOpens.all.mapValues { (_, time) -> time as? Long ?: 0L }

    // Only the latest timestamp per entry is needed; it is never shown as history.
    fun recordFolderOpen(location: FolderLocation) {
        folderOpens.edit().putLong(location.id, System.currentTimeMillis()).apply()
    }

    fun boundFolder(): BoundFolder? {
        val uri = prefs.getString("folder_uri", null) ?: return null
        val name = prefs.getString("folder_name", null) ?: return null
        return BoundFolder(Uri.parse(uri), name)
    }

    fun saveFolder(folder: BoundFolder) {
        val uri = folder.uri.toString()
        if (prefs.getString("folder_uri", null) != uri) folderOpens.edit().clear().apply()
        prefs.edit().putString("folder_uri", uri).putString("folder_name", folder.name).apply()
    }

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

    fun savePins(keys: List<String>) = prefs.edit().putString("pins", keys.joinToString("\n")).apply()
}
