package com.hss.myroutin.update

import android.content.Context
import com.hss.myroutin.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * 说明：GitHub Release 更新入口，负责读取受信任的更新清单、前台下载 APK 与 SHA-256 完整性校验。
 *
 * @作者 huangssh
 * @版本 2.2
 */
class AppUpdateRepository(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val updateManifestUrl: String = UPDATE_MANIFEST_URL
) {

    /** 仅保留应用 Context，下载缓存和网络任务不得持有页面实例。 */
    private val appContext = context.applicationContext

    /** 当前前台下载的连接，用于页面离开时立即断开网络请求。 */
    @Volatile
    private var activeDownloadConnection: HttpURLConnection? = null

    /** 当前更新清单连接，供手动检查被取消或重试时立即中断旧请求。 */
    @Volatile
    private var activeCheckConnection: HttpURLConnection? = null

    /**
     * 获取最新稳定版更新清单；仅当远端 versionCode 更高时才返回可更新结果。
     * @return 检查结果，网络失败与“已是最新”在类型层面明确区分
     */
    suspend fun checkForUpdate(): AppUpdateCheckResult = withContext(ioDispatcher) {
        try {
            val update = requestUpdateManifest()
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
        val updateDirectory = File(appContext.cacheDir, UPDATE_CACHE_DIRECTORY)
        if (!updateDirectory.exists() && !updateDirectory.mkdirs()) {
            return@withContext AppUpdateDownloadResult.Failure("无法创建更新文件目录")
        }
        val targetFile = File(updateDirectory, "MyRoutin-${update.versionCode}.apk")
        val temporaryFile = File(updateDirectory, "MyRoutin-${update.versionCode}.download")
        temporaryFile.delete()
        try {
            val connection = openConnection(update.apkUrl)
            activeDownloadConnection = connection
            try {
                val responseCode = connection.responseCode
                if (responseCode !in HTTP_SUCCESS_RANGE) {
                    throw UpdateHttpException()
                }
                val totalBytes = connection.contentLengthLong.takeIf { it > 0L } ?: update.apkSizeBytes
                val digest = MessageDigest.getInstance(SHA_256_ALGORITHM)
                var downloadedBytes = 0L
                var lastReportedBytes = 0L
                connection.inputStream.use { inputStream ->
                    FileOutputStream(temporaryFile).buffered().use { outputStream ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val bytesRead = inputStream.read(buffer)
                            if (bytesRead < 0) {
                                break
                            }
                            outputStream.write(buffer, 0, bytesRead)
                            digest.update(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (downloadedBytes - lastReportedBytes >= PROGRESS_REPORT_INTERVAL_BYTES) {
                                onProgress(UpdateDownloadProgress(downloadedBytes, totalBytes))
                                lastReportedBytes = downloadedBytes
                            }
                        }
                    }
                }
                coroutineContext.ensureActive()
                onProgress(UpdateDownloadProgress(downloadedBytes, totalBytes))
                if (!digest.digest().toHexString().equals(update.sha256, ignoreCase = true)) {
                    throw UpdateIntegrityException()
                }
                if (targetFile.exists() && !targetFile.delete()) {
                    throw IOException("无法替换旧更新文件")
                }
                if (!temporaryFile.renameTo(targetFile)) {
                    throw IOException("无法保存更新文件")
                }
                AppUpdateDownloadResult.Success(targetFile)
            } finally {
                if (activeDownloadConnection === connection) {
                    activeDownloadConnection = null
                }
                connection.disconnect()
            }
        } catch (exception: CancellationException) {
            temporaryFile.delete()
            throw exception
        } catch (exception: Exception) {
            temporaryFile.delete()
            if (!coroutineContext.isActive) {
                throw CancellationException()
            }
            AppUpdateDownloadResult.Failure(exception.toDownloadFailureMessage())
        }
    }

    /** 页面离开时主动断开前台下载连接，确保下载不会在后台继续。 */
    fun cancelForegroundDownload() {
        activeDownloadConnection?.disconnect()
    }

    /** 用户关闭手动检查提示或再次发起检查时，立即中断尚未完成的清单请求。 */
    fun cancelUpdateCheck() {
        activeCheckConnection?.disconnect()
    }

    /**
     * 关闭已下载提示时清理仅用于安装的缓存 APK，避免长期占用用户设备空间。
     * @param apkFile 已校验且位于应用缓存目录的安装文件
     */
    fun deleteCachedUpdate(apkFile: File) {
        if (apkFile.parentFile == File(appContext.cacheDir, UPDATE_CACHE_DIRECTORY)) {
            apkFile.delete()
        }
    }

    /**
     * 请求并解析 GitHub 最新 Release 附带的 update.json；不信任非固定仓库的下载地址。
     * @return 已完成字段和来源校验的更新清单
     */
    private fun requestUpdateManifest(): AppUpdateManifest {
        val connection = openConnection(updateManifestUrl)
        activeCheckConnection = connection
        try {
            if (connection.responseCode == HTTP_NOT_FOUND) {
                throw UpdateManifestNotFoundException()
            }
            if (connection.responseCode !in HTTP_SUCCESS_RANGE) {
                throw UpdateHttpException()
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return parseUpdateManifest(body)
        } finally {
            if (activeCheckConnection === connection) {
                activeCheckConnection = null
            }
            connection.disconnect()
        }
    }

    /**
     * 为清单和 APK 建立统一网络连接，避免更新流程散落硬编码超时与请求头。
     * @param url 更新清单或 APK 的 HTTPS 地址
     * @return 已设置超时和 GitHub User-Agent 的连接
     */
    private fun openConnection(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MyRoutin/${BuildConfig.VERSION_NAME}")
        }
    }

    /**
     * 校验清单的版本、下载来源、摘要和文件大小；异常清单不会进入下载或安装流程。
     * @param body GitHub Release 附带的 update.json 原文
     * @return 仅包含安全可用字段的更新信息
     */
    private fun parseUpdateManifest(body: String): AppUpdateManifest {
        val jsonObject = JSONObject(body)
        val versionCode = jsonObject.optInt("versionCode", INVALID_VERSION_CODE)
        val versionName = jsonObject.optString("versionName").trim()
        val apkUrl = jsonObject.optString("apkUrl").trim()
        val sha256 = jsonObject.optString("sha256").trim()
        val apkSizeBytes = jsonObject.optLong("apkSizeBytes", INVALID_APK_SIZE).takeIf { it > 0L }
        if (
            versionCode <= 0 ||
            versionName.isBlank() ||
            !apkUrl.startsWith(TRUSTED_APK_URL_PREFIX) ||
            !SHA_256_PATTERN.matches(sha256)
        ) {
            throw UpdateManifestException()
        }
        return AppUpdateManifest(
            versionCode = versionCode,
            versionName = versionName,
            apkUrl = apkUrl,
            sha256 = sha256,
            apkSizeBytes = apkSizeBytes
        )
    }

    /** 将摘要字节转为固定小写十六进制，和 Release 清单中的 sha256 字段逐字节比对。 */
    private fun ByteArray.toHexString(): String {
        return joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    /** 下载失败文案只描述用户可行动的结果，不暴露 URL、异常栈或服务端响应。 */
    private fun Throwable.toDownloadFailureMessage(): String {
        return when (this) {
            is UpdateIntegrityException -> "安装包校验失败，请重试"
            is SocketTimeoutException -> "下载超时，请检查网络后重试"
            is IOException -> "下载失败，请检查网络后重试"
            else -> "下载失败，请稍后重试"
        }
    }

    private companion object {
        private const val UPDATE_MANIFEST_URL =
            "https://github.com/huangssh/MyRoutin/releases/latest/download/update.json"
        private const val TRUSTED_APK_URL_PREFIX =
            "https://github.com/huangssh/MyRoutin/releases/download/"
        private const val UPDATE_CACHE_DIRECTORY = "updates"
        private const val SHA_256_ALGORITHM = "SHA-256"
        private const val CONNECT_TIMEOUT_MILLIS = 10_000
        private const val READ_TIMEOUT_MILLIS = 15_000
        private const val DOWNLOAD_BUFFER_SIZE = 8 * 1024
        private const val PROGRESS_REPORT_INTERVAL_BYTES = 64 * 1024L
        private const val INVALID_VERSION_CODE = -1
        private const val INVALID_APK_SIZE = -1L
        private const val HTTP_NOT_FOUND = 404
        private val HTTP_SUCCESS_RANGE = 200..299
        private val SHA_256_PATTERN = Regex("^[a-fA-F0-9]{64}$")
    }
}

/**
 * 说明：更新清单中经过校验的稳定版本信息，是下载、展示与安装流程共同使用的业务对象。
 *
 * @作者 huangssh
 * @版本 2.1
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
 * @版本 2.1
 */
data class UpdateDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long?
)

/**
 * 说明：检查更新结果，避免页面从网络异常或远端版本号推断不可靠状态。
 *
 * @作者 huangssh
 * @版本 2.1
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
 * @版本 2.1
 */
sealed interface AppUpdateDownloadResult {

    /** 可交给 FileProvider 触发系统安装页的已校验 APK。 */
    data class Success(val apkFile: File) : AppUpdateDownloadResult

    /** 可直接展示给用户的安全下载失败文案。 */
    data class Failure(val userMessage: String) : AppUpdateDownloadResult
}

/** 更新清单字段缺失或来源异常时使用，禁止继续处理该远端数据。 */
private class UpdateManifestException : IllegalArgumentException()

/** 旧版 Release 未附带更新清单时使用，兼容上线更新功能前已发布的安装包。 */
private class UpdateManifestNotFoundException : IOException()

/** 清单或 APK 请求返回非成功状态码时使用。 */
private class UpdateHttpException : IOException()

/** APK 摘要与 Release 清单不一致时使用，防止安装未通过完整性校验的文件。 */
private class UpdateIntegrityException : IOException()
