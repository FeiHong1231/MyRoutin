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

    /** 被用户暂停的连接单独标记，下载异常时据此保留未完成的临时 APK。 */
    @Volatile
    private var pausedDownloadConnection: HttpURLConnection? = null

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
        val entityTagFile = File(updateDirectory, "MyRoutin-${update.versionCode}.etag")
        if (!temporaryFile.isFile) {
            // 校验器必须和同版本分片成对存在，孤立文件不能参与下一次断点续传。
            entityTagFile.delete()
        }
        var connection: HttpURLConnection? = null
        var downloadedBytes = temporaryFile.takeIf(File::isFile)?.length() ?: 0L
        var totalBytes = update.apkSizeBytes
        try {
            var resumeBytes = downloadedBytes
            val cachedEntityTag = entityTagFile.takeIf { resumeBytes > 0L }?.let(::readCachedEntityTag)
            var downloadConnection = openConnection(update.apkUrl, resumeBytes, cachedEntityTag)
            connection = downloadConnection
            activeDownloadConnection = downloadConnection
            var responseCode = downloadConnection.responseCode
            if (responseCode !in HTTP_SUCCESS_RANGE) {
                throw UpdateHttpException()
            }
            var shouldAppend = resumeBytes > 0L &&
                responseCode == HTTP_PARTIAL_CONTENT &&
                isValidResumeResponse(downloadConnection, update, resumeBytes, cachedEntityTag)
            if (resumeBytes > 0L && responseCode == HTTP_PARTIAL_CONTENT && !shouldAppend) {
                // 206 缺少有效 Content-Range 或实体校验器不一致时，当前响应不能写入旧分片。
                if (isPauseRequestedFor(downloadConnection)) {
                    throw UpdateHttpException()
                }
                val rejectedConnection = downloadConnection
                temporaryFile.delete()
                entityTagFile.delete()
                downloadedBytes = 0L
                resumeBytes = 0L
                val replacementConnection = openConnection(update.apkUrl)
                connection = replacementConnection
                activeDownloadConnection = replacementConnection
                if (pausedDownloadConnection === rejectedConnection) {
                    // 用户恰好在重建连接时点击暂停，需要把暂停标记转交给新连接。
                    pausedDownloadConnection = replacementConnection
                    replacementConnection.disconnect()
                }
                rejectedConnection.disconnect()
                downloadConnection = replacementConnection
                responseCode = downloadConnection.responseCode
                if (responseCode !in HTTP_SUCCESS_RANGE || responseCode == HTTP_PARTIAL_CONTENT) {
                    throw UpdateHttpException()
                }
                shouldAppend = false
            }
            if (resumeBytes > 0L && !shouldAppend) {
                // 服务端未接受 Range 时放弃旧分片，避免把完整响应错误追加到临时 APK。
                temporaryFile.delete()
                entityTagFile.delete()
                downloadedBytes = 0L
                resumeBytes = 0L
            }
            if (resumeBytes == 0L && responseCode == HTTP_PARTIAL_CONTENT) {
                // 未请求 Range 却收到不完整响应时禁止继续，避免把部分内容当作完整 APK。
                throw UpdateHttpException()
            }
            persistResponseEntityTag(downloadConnection, entityTagFile)
            val contentRange = parseContentRange(downloadConnection.getHeaderField(CONTENT_RANGE_HEADER))
            totalBytes = update.apkSizeBytes
                ?: contentRange?.totalBytes
                ?: downloadConnection.contentLengthLong.takeIf { it > 0L }?.let { contentLength ->
                    if (shouldAppend) downloadedBytes + contentLength else contentLength
                }
            val digest = MessageDigest.getInstance(SHA_256_ALGORITHM)
            if (shouldAppend) {
                temporaryFile.inputStream().buffered().use { cachedInputStream ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    while (true) {
                        val bytesRead = cachedInputStream.read(buffer)
                        if (bytesRead < 0) {
                            break
                        }
                        digest.update(buffer, 0, bytesRead)
                    }
                }
            }
            var lastReportedBytes = downloadedBytes
            downloadConnection.inputStream.use { inputStream ->
                FileOutputStream(temporaryFile, shouldAppend).buffered().use { outputStream ->
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
            entityTagFile.delete()
            AppUpdateDownloadResult.Success(targetFile)
        } catch (exception: CancellationException) {
            if (!isPauseRequestedFor(connection)) {
                deletePartialDownloadFiles(temporaryFile, entityTagFile)
            }
            throw exception
        } catch (exception: Exception) {
            if (isPauseRequestedFor(connection)) {
                return@withContext AppUpdateDownloadResult.Paused(
                    UpdateDownloadProgress(downloadedBytes, totalBytes)
                )
            }
            deletePartialDownloadFiles(temporaryFile, entityTagFile)
            if (!coroutineContext.isActive) {
                throw CancellationException()
            }
            AppUpdateDownloadResult.Failure(exception.toDownloadFailureMessage())
        } finally {
            connection?.let { currentConnection ->
                if (activeDownloadConnection === currentConnection) {
                    activeDownloadConnection = null
                }
                if (pausedDownloadConnection === currentConnection) {
                    pausedDownloadConnection = null
                }
                currentConnection.disconnect()
            }
        }
    }

    /** 页面离开时主动断开前台下载连接，确保下载不会在后台继续。 */
    fun cancelForegroundDownload() {
        activeDownloadConnection?.disconnect()
    }

    /** 暂停仅中断当前网络连接，保留临时 APK 供用户点击继续后通过 Range 请求接续下载。 */
    fun pauseForegroundDownload() {
        activeDownloadConnection?.let { connection ->
            pausedDownloadConnection = connection
            connection.disconnect()
        }
    }

    /**
     * 关闭暂停卡片或离开下载流程时删除指定版本的部分文件，避免缓存中残留无用安装包。
     * @param update 当前下载的更新清单，用于定位受限的缓存文件名
     */
    fun deletePartialUpdate(update: AppUpdateManifest) {
        val updateDirectory = File(appContext.cacheDir, UPDATE_CACHE_DIRECTORY)
        deletePartialDownloadFiles(
            temporaryFile = File(updateDirectory, "MyRoutin-${update.versionCode}.download"),
            entityTagFile = File(updateDirectory, "MyRoutin-${update.versionCode}.etag")
        )
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
     * @param rangeStartBytes 断点续传请求的起始字节，完整请求时为 0
     * @param entityTag 旧分片对应的强 ETag，仅用于 If-Range
     * @return 已设置超时和 GitHub User-Agent 的连接
     */
    private fun openConnection(
        url: String,
        rangeStartBytes: Long = 0L,
        entityTag: String? = null
    ): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MyRoutin/${BuildConfig.VERSION_NAME}")
            if (rangeStartBytes > 0L) {
                setRequestProperty(RANGE_HEADER, "bytes=$rangeStartBytes-")
                entityTag?.let { setRequestProperty(IF_RANGE_HEADER, it) }
            }
        }
    }

    /**
     * 校验断点响应确实从本地文件末尾开始，并在存在强 ETag 时确认仍是同一个远端实体。
     * @param connection 当前返回 HTTP 206 的下载连接
     * @param update 当前更新清单，用于核对已知的 APK 总字节数
     * @param resumeBytes 本地临时文件当前长度
     * @param cachedEntityTag 本地分片记录的强 ETag
     * @return 当前响应能否安全追加到已有分片
     */
    private fun isValidResumeResponse(
        connection: HttpURLConnection,
        update: AppUpdateManifest,
        resumeBytes: Long,
        cachedEntityTag: String?
    ): Boolean {
        val contentRange = parseContentRange(connection.getHeaderField(CONTENT_RANGE_HEADER))
            ?: return false
        if (contentRange.startByte != resumeBytes) {
            return false
        }
        if (update.apkSizeBytes != null && contentRange.totalBytes != update.apkSizeBytes) {
            return false
        }
        if (cachedEntityTag != null) {
            val responseEntityTag = normalizeStrongEntityTag(connection.getHeaderField(ENTITY_TAG_HEADER))
            if (responseEntityTag != cachedEntityTag) {
                return false
            }
        }
        return true
    }

    /**
     * 解析 HTTP 字节范围，拒绝倒序、越过总大小或无法转换为 Long 的响应头。
     * @param headerValue 服务端返回的 Content-Range
     * @return 通过结构和边界校验的字节范围
     */
    private fun parseContentRange(headerValue: String?): HttpByteContentRange? {
        val matchResult = headerValue
            ?.trim()
            ?.let(CONTENT_RANGE_PATTERN::matchEntire)
            ?: return null
        val startByte = matchResult.groupValues[1].toLongOrNull() ?: return null
        val endByte = matchResult.groupValues[2].toLongOrNull() ?: return null
        val rawTotalBytes = matchResult.groupValues[3]
        val totalBytes = if (rawTotalBytes == UNKNOWN_CONTENT_RANGE_TOTAL) {
            null
        } else {
            rawTotalBytes.toLongOrNull() ?: return null
        }
        if (
            endByte < startByte ||
            (totalBytes != null && (totalBytes <= 0L || endByte >= totalBytes))
        ) {
            return null
        }
        return HttpByteContentRange(
            startByte = startByte,
            endByte = endByte,
            totalBytes = totalBytes
        )
    }

    /**
     * 只接受长度受限且不含换行的强 ETag，弱校验器不能用于 If-Range 字节续传。
     * @param rawEntityTag 响应头或本地文件中的原始 ETag
     * @return 可安全写入请求头的强 ETag
     */
    private fun normalizeStrongEntityTag(rawEntityTag: String?): String? {
        val entityTag = rawEntityTag?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return entityTag.takeIf {
            it.length <= MAX_ENTITY_TAG_LENGTH &&
                !it.startsWith(WEAK_ENTITY_TAG_PREFIX, ignoreCase = true) &&
                it.startsWith(ENTITY_TAG_QUOTE) &&
                it.endsWith(ENTITY_TAG_QUOTE) &&
                !it.contains('\r') &&
                !it.contains('\n')
        }
    }

    /** 读取旧分片的强 ETag；文件损坏时删除校验器并退化为仅校验 Content-Range。 */
    private fun readCachedEntityTag(entityTagFile: File): String? {
        val entityTag = runCatching { entityTagFile.readText() }
            .getOrNull()
            .let(::normalizeStrongEntityTag)
        if (entityTag == null) {
            entityTagFile.delete()
        }
        return entityTag
    }

    /** 将当前响应的强 ETag 与分片配套保存；无有效校验器时清理旧文件。 */
    private fun persistResponseEntityTag(connection: HttpURLConnection, entityTagFile: File) {
        val entityTag = normalizeStrongEntityTag(connection.getHeaderField(ENTITY_TAG_HEADER))
        if (entityTag == null) {
            entityTagFile.delete()
            return
        }
        runCatching { entityTagFile.writeText(entityTag) }
            .onFailure { entityTagFile.delete() }
    }

    /** 暂停以外的退出路径同时删除 APK 分片和实体校验器，禁止下次使用孤立状态续传。 */
    private fun deletePartialDownloadFiles(temporaryFile: File, entityTagFile: File) {
        temporaryFile.delete()
        entityTagFile.delete()
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

    /**
     * 判断当前异常是否由用户点击暂停造成；只有该分支可保留不完整文件用于断点继续。
     * @param connection 当前下载使用的网络连接
     * @return 是否应保留对应的临时 APK
     */
    private fun isPauseRequestedFor(connection: HttpURLConnection?): Boolean {
        return connection != null && pausedDownloadConnection === connection
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
        private const val RANGE_HEADER = "Range"
        private const val IF_RANGE_HEADER = "If-Range"
        private const val CONTENT_RANGE_HEADER = "Content-Range"
        private const val ENTITY_TAG_HEADER = "ETag"
        private const val ENTITY_TAG_QUOTE = "\""
        private const val WEAK_ENTITY_TAG_PREFIX = "W/"
        private const val UNKNOWN_CONTENT_RANGE_TOTAL = "*"
        private const val CONNECT_TIMEOUT_MILLIS = 10_000
        private const val READ_TIMEOUT_MILLIS = 15_000
        private const val DOWNLOAD_BUFFER_SIZE = 8 * 1024
        private const val PROGRESS_REPORT_INTERVAL_BYTES = 64 * 1024L
        private const val MAX_ENTITY_TAG_LENGTH = 256
        private const val INVALID_VERSION_CODE = -1
        private const val INVALID_APK_SIZE = -1L
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_PARTIAL_CONTENT = 206
        private val HTTP_SUCCESS_RANGE = 200..299
        private val SHA_256_PATTERN = Regex("^[a-fA-F0-9]{64}$")
        private val CONTENT_RANGE_PATTERN = Regex(
            pattern = "^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$",
            option = RegexOption.IGNORE_CASE
        )
    }
}

/**
 * 说明：通过校验的 HTTP 字节响应范围，供断点起点和远端文件总大小核对使用。
 *
 * @作者 huangssh
 * @版本 2.3
 */
private data class HttpByteContentRange(
    val startByte: Long,
    val endByte: Long,
    val totalBytes: Long?
)

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

/** 更新清单字段缺失或来源异常时使用，禁止继续处理该远端数据。 */
private class UpdateManifestException : IllegalArgumentException()

/** 旧版 Release 未附带更新清单时使用，兼容上线更新功能前已发布的安装包。 */
private class UpdateManifestNotFoundException : IOException()

/** 清单或 APK 请求返回非成功状态码时使用。 */
private class UpdateHttpException : IOException()

/** APK 摘要与 Release 清单不一致时使用，防止安装未通过完整性校验的文件。 */
private class UpdateIntegrityException : IOException()
