package com.locusable.ginkgo.update

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import com.locusable.ginkgo.ui.showPaperDialog
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** The updater only runs in response to the Settings action. The system owns the final install prompt. */
internal class UpdateFlow(private val activity: Activity) {
    private val client = ReleaseUpdateClient(activity.applicationContext)
    private val worker = Executors.newSingleThreadExecutor()
    private var job: Future<*>? = null
    private var dialog: Dialog? = null
    var awaitingPermission: String? = null

    fun check() = task("正在检查更新", "正在读取 GitHub 上的正式发布。", "无法检查更新", "请检查网络连接后重试。", {
        client.latest()?.let { it to client.installedCode() }
    }) { result ->
        when {
            result == null -> message("暂时无法检查更新", "当前正式发布尚未提供应用内更新信息。")
            result.first.versionCode <= result.second -> message("已是最新版本", "当前没有可用的正式更新。")
            else -> offer(result.first)
        }
    }

    private fun offer(release: ReleaseUpdate) {
        val notes = release.notes.lineSequence().map { it.trimEnd() }
            .filterNot { it.startsWith("# ") || it.startsWith("## ") }
            .joinToString("\n") { if (it.startsWith("- ")) "• ${it.removePrefix("- ")}" else it }
            .replace("**", "").trim()
        val version = "${release.versionName} (${release.versionCode})"
        val description = if (notes.isBlank()) version else "$version\n\n$notes"
        dialog = activity.showPaperDialog("发现新版本", description, "下载更新") {
            task("正在下载更新", "下载并校验 APK，完成后将打开系统安装界面。", "无法下载更新", "下载或校验失败，请稍后重试。", {
                client.download(release)
            }, ::requestInstall)
        }
    }

    private fun requestInstall(apk: File) {
        if (activity.packageManager.canRequestPackageInstalls()) {
            install(apk)
            return
        }
        dialog = activity.showPaperDialog("允许安装更新", "请在系统设置中允许 Ginkgo 安装应用，返回后即可继续。", "前往设置") {
            awaitingPermission = apk.name
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", activity.packageName, null))
            launch(intent) { awaitingPermission = null }
        }
    }

    fun resume() {
        val name = awaitingPermission ?: return
        awaitingPermission = null
        if (!activity.packageManager.canRequestPackageInstalls()) return
        val apk = UpdateApks.file(activity, name)
        if (apk == null) message("无法安装更新", "下载的 APK 已不可用，请重新检查更新。") else install(apk)
    }

    private fun install(apk: File) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(UpdateApks.uri(activity, apk), UPDATE_APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        @Suppress("DEPRECATION")
        val installer = activity.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY)
            .firstOrNull()?.activityInfo
        if (installer == null) {
            message("无法安装更新", "系统安装器不可用。")
            return
        }
        intent.component = ComponentName(installer.packageName, installer.name)
        launch(intent)
    }

    private fun launch(intent: Intent, onFailure: () -> Unit = {}) {
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            onFailure()
            message("无法安装更新", "系统安装界面不可用。")
        } catch (_: SecurityException) {
            onFailure()
            message("无法安装更新", "系统没有允许 Ginkgo 安装应用。")
        }
    }

    private fun message(title: String, text: String) {
        dialog = activity.showPaperDialog(title, text, "确定")
    }

    private fun <T> task(title: String, text: String, errorTitle: String, errorText: String, work: () -> T, onResult: (T) -> Unit) {
        pause()
        val progress = activity.showPaperDialog(title, text, "取消")
        dialog = progress
        progress.setOnDismissListener { if (dialog === progress) pause() }
        job = worker.submit {
            val result = try { Result.success(work()) } catch (e: Exception) { Result.failure(e) }
            activity.runOnUiThread {
                if (dialog === progress && progress.isShowing) {
                    job = null
                    dialog = null
                    progress.dismiss()
                    result.fold(onResult) { message(errorTitle, errorText) }
                }
            }
        }
    }

    fun pause() {
        job?.cancel(true)
        job = null
        val previous = dialog
        dialog = null
        previous?.dismiss()
    }

    fun close() {
        pause()
        worker.shutdownNow()
    }
}
