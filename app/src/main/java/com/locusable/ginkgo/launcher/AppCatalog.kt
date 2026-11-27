package com.locusable.ginkgo.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Resources
import android.graphics.drawable.Drawable
import java.text.Collator

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
