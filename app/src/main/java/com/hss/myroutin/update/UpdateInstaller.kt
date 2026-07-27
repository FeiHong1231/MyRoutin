package com.hss.myroutin.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * 说明：将已校验 APK 交给 Android 系统安装器，并只在首次需要时引导用户授予安装更新权限。
 *
 * @作者 huangssh
 * @版本 2.2
 */
object UpdateInstaller {

    /**
     * 仅在 Android 8.0 及以上检查“允许此来源安装应用”授权，低版本直接交给系统安装页确认。
     * @param activity 当前前台 Activity
     * @return 当前 App 是否具备发起 APK 安装的系统授权
     */
    fun canRequestPackageInstalls(activity: Activity): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            activity.packageManager.canRequestPackageInstalls()
    }

    /**
     * 跳转到当前 App 的未知来源安装授权页，用户手动授权后可回到卡片再次点击“立即安装”。
     * @param activity 当前前台 Activity
     * @return 是否成功打开系统授权页
     */
    fun openInstallPermissionSettings(activity: Activity): Boolean {
        return runCatching {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
            )
        }.isSuccess
    }

    /**
     * 使用 FileProvider 安全共享缓存 APK，并发起系统安装确认页；不会执行静默安装。
     * @param activity 当前前台 Activity
     * @param apkFile 已完成 SHA-256 校验的缓存 APK
     * @return 安装页启动结果或仍需用户授权的状态
     */
    fun requestInstall(activity: Activity, apkFile: File): UpdateInstallResult {
        if (!apkFile.isFile) {
            return UpdateInstallResult.Failure("安装包不存在，请重新下载")
        }
        if (!canRequestPackageInstalls(activity)) {
            return UpdateInstallResult.PermissionRequired
        }
        return try {
            val apkUri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apkFile
            )
            activity.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, APK_MIME_TYPE)
                    clipData = ClipData.newRawUri("MyRoutin 更新安装包", apkUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
            UpdateInstallResult.Started
        } catch (exception: ActivityNotFoundException) {
            UpdateInstallResult.Failure("无法打开系统安装页")
        } catch (exception: IllegalArgumentException) {
            UpdateInstallResult.Failure("安装包路径异常，请重新下载")
        }
    }

    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
}

/**
 * 说明：系统安装请求的可展示结果，页面据此决定跳转授权、保留下载卡片或提示失败。
 *
 * @作者 huangssh
 * @版本 2.2
 */
sealed interface UpdateInstallResult {

    /** 系统安装确认页已打开，最终是否覆盖更新由用户在系统页确认。 */
    object Started : UpdateInstallResult

    /** Android 8.0 及以上首次安装时，需要用户先允许 MyRoutin 安装未知来源应用。 */
    object PermissionRequired : UpdateInstallResult

    /** 当前设备无法打开安装页或 APK 路径不可用。 */
    data class Failure(val userMessage: String) : UpdateInstallResult
}
