package com.hss.myroutin.update

import android.content.Context
import com.hss.myroutin.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 说明：GitHub Release 更新入口，编排清单检查、前台 APK 下载及缓存清理，不直接处理协议和文件流。
 *
 * @作者 huangssh
 * @版本 2.3
 */
class AppUpdateRepository(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val updateManifestUrl: String = UPDATE_MANIFEST_URL
) {

    /** 统一连接工厂确保清单与 APK 请求继续沿用相同超时和请求头。 */
    private val connectionFactory = DefaultUpdateHttpConnectionFactory()

    /** 分片、ETag 和已校验 APK 仅允许落在应用缓存目录的 updates 子目录。 */
    private val fileStore = UpdateDownloadFileStore(
        File(context.applicationContext.cacheDir, UPDATE_CACHE_DIRECTORY)
    )

    /** 清单客户端独立持有检查连接，使检查取消不影响正在进行的 APK 下载。 */
    private val manifestClient = AppUpdateManifestClient(connectionFactory)

    /** APK 下载器独立维护暂停标记、Range/ETag 续传及摘要校验。 */
    private val apkDownloader = ResumableApkDownloader(connectionFactory, fileStore)

    /**
     * 获取最新稳定版更新清单；仅当远端 versionCode 更高时才返回可更新结果。
     * @return 检查结果，网络失败与“已是最新”在类型层面明确区分
     */
    suspend fun checkForUpdate(): AppUpdateCheckResult = withContext(ioDispatcher) {
        try {
            val update = manifestClient.request(updateManifestUrl)
            if (update.versionCode > BuildConfig.VERSION_CODE) {
                AppUpdateCheckResult.UpdateAvailable(update)
            } else {
                AppUpdateCheckResult.NoUpdate
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: UpdateManifestNotFoundException) {
            // 兼容首个未附带 update.json 的旧 Release，不能把“无更新”误报为网络故障。
            AppUpdateCheckResult.NoUpdate
        } catch (exception: Exception) {
            AppUpdateCheckResult.Failure
        }
    }

    /**
     * 将受信任更新下载到应用缓存目录，并在写入完成后校验 SHA-256 才暴露安装文件。
     * @param update 已通过清单校验的更新信息
     * @param onProgress 前台页面使用的真实字节进度回调
     * @return 下载、校验和落盘后的结果
     */
    suspend fun downloadUpdate(
        update: AppUpdateManifest,
        onProgress: (UpdateDownloadProgress) -> Unit
    ): AppUpdateDownloadResult = withContext(ioDispatcher) {
        apkDownloader.download(update, onProgress)
    }

    /** 页面离开时主动断开前台下载连接，确保下载不会在后台继续。 */
    fun cancelForegroundDownload() {
        apkDownloader.cancel()
    }

    /** 暂停仅中断当前网络连接，保留临时 APK 和 ETag 供 Range 请求接续下载。 */
    fun pauseForegroundDownload() {
        apkDownloader.pause()
    }

    /**
     * 关闭暂停卡片或离开下载流程时删除指定版本的部分文件，避免缓存残留无用安装包。
     * @param update 当前下载的更新清单，用于定位受限的缓存文件名
     */
    fun deletePartialUpdate(update: AppUpdateManifest) {
        fileStore.deletePartial(update.versionCode)
    }

    /** 用户关闭手动检查提示或再次发起检查时，立即中断尚未完成的清单请求。 */
    fun cancelUpdateCheck() {
        manifestClient.cancel()
    }

    /**
     * 关闭已下载提示时清理仅用于安装的缓存 APK，避免长期占用用户设备空间。
     * @param apkFile 已校验且位于应用缓存目录的安装文件
     */
    fun deleteCachedUpdate(apkFile: File) {
        fileStore.deleteCachedUpdate(apkFile)
    }

    private companion object {
        private const val UPDATE_MANIFEST_URL =
            "https://github.com/huangssh/MyRoutin/releases/latest/download/update.json"
        private const val UPDATE_CACHE_DIRECTORY = "updates"
    }
}

/**
 * 说明：更新清单中经过校验的稳定版本信息，是下载、展示与安装流程共同使用的业务对象。
 *
 * @作者 huangssh
 * @版本 2.2
 */
data class AppUpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val apkSizeBytes: Long?
)

/**
 * 说明：前台下载的真实字节状态，totalBytes 缺失时页面改为不确定环形进度。
 *
 * @作者 huangssh
 * @版本 2.2
 */
data class UpdateDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long?
)

/**
 * 说明：检查更新结果，避免页面从网络异常或远端版本号推断不可靠状态。
 *
 * @作者 huangssh
 * @版本 2.2
 */
sealed interface AppUpdateCheckResult {

    /** 当前安装版本不低于 GitHub 最新稳定版。 */
    object NoUpdate : AppUpdateCheckResult

    /** GitHub 清单声明了更高的 versionCode，可向用户展示下载入口。 */
    data class UpdateAvailable(val update: AppUpdateManifest) : AppUpdateCheckResult

    /** 检查失败时不展示错误卡片，手动检查场景由 ViewModel 决定是否提示。 */
    object Failure : AppUpdateCheckResult
}

/**
 * 说明：APK 下载与校验结果，成功仅在完整文件通过 SHA-256 后返回。
 *
 * @作者 huangssh
 * @版本 2.2
 */
sealed interface AppUpdateDownloadResult {

    /** 可交给 FileProvider 触发系统安装页的已校验 APK。 */
    data class Success(val apkFile: File) : AppUpdateDownloadResult

    /** 用户主动暂停时保留临时文件与真实进度，供 ViewModel 切换为继续下载入口。 */
    data class Paused(val progress: UpdateDownloadProgress) : AppUpdateDownloadResult

    /** 可直接展示给用户的安全下载失败文案。 */
    data class Failure(val userMessage: String) : AppUpdateDownloadResult
}
