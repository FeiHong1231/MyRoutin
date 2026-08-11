package com.hss.myroutin.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import kotlin.coroutines.coroutineContext

/**
 * 说明：前台 APK 断点下载器，负责 Range/ETag 协议、字节落盘、进度、暂停和完整性校验。
 *
 * @param connectionFactory 更新模块统一使用的 HTTP 连接工厂
 * @param fileStore 下载分片、ETag 和目标 APK 的文件存储
 * @param integrityVerifier APK SHA-256 增量校验器
 * @作者 huangssh
 * @版本 2.3
 */
internal class ResumableApkDownloader(
    private val connectionFactory: UpdateHttpConnectionFactory,
    private val fileStore: UpdateDownloadFileStore,
    private val integrityVerifier: UpdateApkIntegrityVerifier = UpdateApkIntegrityVerifier()
) {

    /** 当前前台下载连接，用于页面离开时立即断开网络请求。 */
    @Volatile
    private var activeConnection: HttpURLConnection? = null

    /** 被用户暂停的连接单独标记，用于区分自动暂停与显式取消。 */
    @Volatile
    private var pausedConnection: HttpURLConnection? = null

    /**
     * 下载并校验指定更新；只有 SHA-256 通过后才把临时文件发布为可安装 APK。
     * @param update 已通过清单校验的更新信息
     * @param onProgress 前台页面使用的真实字节进度回调
     * @return 下载、暂停或失败结果
     */
    suspend fun download(
        update: AppUpdateManifest,
        onProgress: (UpdateDownloadProgress) -> Unit
    ): AppUpdateDownloadResult {
        val files = try {
            fileStore.prepare(update.versionCode)
        } catch (exception: UpdateDirectoryException) {
            return AppUpdateDownloadResult.Failure(AppUpdateDownloadFailureReason.DIRECTORY_UNAVAILABLE)
        }
        var connection: HttpURLConnection? = null
        var downloadedBytes = files.temporaryFile.takeIf { it.isFile }?.length() ?: 0L
        var totalBytes = update.apkSizeBytes
        try {
            var resumeBytes = downloadedBytes
            val cachedEntityTag = files.entityTagFile
                .takeIf { resumeBytes > 0L }
                ?.let { fileStore.readCachedEntityTag(files) }
            var downloadConnection = connectionFactory.open(update.apkUrl, resumeBytes, cachedEntityTag)
            connection = downloadConnection
            activeConnection = downloadConnection
            var responseCode = downloadConnection.responseCode
            if (responseCode !in HTTP_SUCCESS_RANGE) {
                throw UpdateHttpException(responseCode)
            }
            var shouldAppend = resumeBytes > 0L &&
                responseCode == HTTP_PARTIAL_CONTENT &&
                UpdateResumeProtocol.isValidResumeResponse(
                    contentRangeHeader = downloadConnection.getHeaderField(CONTENT_RANGE_HEADER),
                    responseEntityTag = downloadConnection.getHeaderField(ENTITY_TAG_HEADER),
                    expectedTotalBytes = update.apkSizeBytes,
                    resumeBytes = resumeBytes,
                    cachedEntityTag = cachedEntityTag
                )
            if (resumeBytes > 0L && responseCode == HTTP_PARTIAL_CONTENT && !shouldAppend) {
                // 无效 206 不能写入旧分片，改为无 Range 的完整请求重新确认远端实体。
                if (isPauseRequestedFor(downloadConnection)) {
                    throw UpdateHttpException(responseCode)
                }
                val rejectedConnection = downloadConnection
                fileStore.deletePartial(files)
                downloadedBytes = 0L
                resumeBytes = 0L
                val replacementConnection = connectionFactory.open(update.apkUrl)
                connection = replacementConnection
                activeConnection = replacementConnection
                if (pausedConnection === rejectedConnection) {
                    // 用户恰好在重建连接时暂停，需要把标记交给新连接并立即中断。
                    pausedConnection = replacementConnection
                    replacementConnection.disconnect()
                }
                rejectedConnection.disconnect()
                downloadConnection = replacementConnection
                responseCode = downloadConnection.responseCode
                if (responseCode !in HTTP_SUCCESS_RANGE || responseCode == HTTP_PARTIAL_CONTENT) {
                    throw UpdateHttpException(responseCode)
                }
                shouldAppend = false
            }
            if (resumeBytes > 0L && !shouldAppend) {
                // 服务端忽略 Range 返回完整响应时丢弃旧分片，本次响应从头覆盖写入。
                fileStore.deletePartial(files)
                downloadedBytes = 0L
                resumeBytes = 0L
            }
            if (resumeBytes == 0L && responseCode == HTTP_PARTIAL_CONTENT) {
                // 未请求 Range 却收到部分内容，禁止把不完整响应当作完整 APK。
                throw UpdateHttpException(responseCode)
            }
            fileStore.persistResponseEntityTag(downloadConnection, files)
            val contentRange = UpdateResumeProtocol.parseContentRange(
                downloadConnection.getHeaderField(CONTENT_RANGE_HEADER)
            )
            totalBytes = update.apkSizeBytes
                ?: contentRange?.totalBytes
                ?: downloadConnection.contentLengthLong.takeIf { it > 0L }?.let { contentLength ->
                    if (shouldAppend) downloadedBytes + contentLength else contentLength
                }
            if (downloadedBytes > 0L) {
                // 续传建立后立即同步磁盘真实进度，不等待下一批 64 KB 才纠正页面显示。
                onProgress(UpdateDownloadProgress(downloadedBytes, totalBytes))
            }
            val integritySession = integrityVerifier.createSession(
                files.temporaryFile.takeIf { shouldAppend }
            )
            var lastReportedBytes = downloadedBytes
            downloadConnection.inputStream.use { inputStream ->
                FileOutputStream(files.temporaryFile, shouldAppend).buffered().use { outputStream ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead < 0) {
                            break
                        }
                        outputStream.write(buffer, 0, bytesRead)
                        integritySession.update(buffer, bytesRead)
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
            if (!integritySession.matches(update.sha256)) {
                throw UpdateIntegrityException()
            }
            fileStore.publishVerifiedDownload(files)
            return AppUpdateDownloadResult.Success(files.targetFile)
        } catch (exception: CancellationException) {
            if (!isPauseRequestedFor(connection)) {
                fileStore.deletePartial(files)
            }
            throw exception
        } catch (exception: Exception) {
            if (isPauseRequestedFor(connection)) {
                return AppUpdateDownloadResult.Paused(
                    UpdateDownloadProgress(downloadedBytes, totalBytes)
                )
            }
            if (!coroutineContext.isActive) {
                // 显式取消即使先表现为连接异常也必须清理分片，不能误当作可恢复断网。
                fileStore.deletePartial(files)
                throw CancellationException()
            }
            val shouldKeepPartial = downloadedBytes > 0L && exception.shouldKeepPartialDownload()
            if (!shouldKeepPartial) {
                fileStore.deletePartial(files)
            }
            return AppUpdateDownloadResult.Failure(exception.toDownloadFailureReason())
        } finally {
            connection?.let { currentConnection ->
                if (activeConnection === currentConnection) {
                    activeConnection = null
                }
                if (pausedConnection === currentConnection) {
                    pausedConnection = null
                }
                currentConnection.disconnect()
            }
        }
    }

    /** 页面离开时主动断开连接，调用方随后取消协程即可清理未完成分片。 */
    fun cancel() {
        activeConnection?.disconnect()
    }

    /** 用户主动暂停时标记并断开连接，异常出口据此保留分片和 ETag。 */
    fun pause() {
        activeConnection?.let { connection ->
            pausedConnection = connection
            connection.disconnect()
        }
    }

    /**
     * 下载异常只映射为稳定原因，不暴露 URL、异常栈或服务端响应。
     * @return 供 UI 边界解析的失败分类
     */
    private fun Throwable.toDownloadFailureReason(): AppUpdateDownloadFailureReason {
        return when (this) {
            is UpdateIntegrityException -> AppUpdateDownloadFailureReason.INTEGRITY_CHECK_FAILED
            is SocketTimeoutException -> AppUpdateDownloadFailureReason.TIMEOUT
            is IOException -> AppUpdateDownloadFailureReason.NETWORK
            else -> AppUpdateDownloadFailureReason.UNKNOWN
        }
    }

    /** 网络中断、超时和服务端临时错误允许保留已写入分片，协议或完整性错误必须重新下载。 */
    private fun Throwable.shouldKeepPartialDownload(): Boolean {
        return when (this) {
            is UpdateIntegrityException -> false
            is UpdateHttpException -> responseCode != null && responseCode in HTTP_SERVER_ERROR_RANGE
            is SocketTimeoutException -> true
            is IOException -> true
            else -> false
        }
    }

    /** 判断当前退出是否由用户暂停触发，只有该分支可以保留未完成文件。 */
    private fun isPauseRequestedFor(connection: HttpURLConnection?): Boolean {
        return connection != null && pausedConnection === connection
    }

    private companion object {
        private const val CONTENT_RANGE_HEADER = "Content-Range"
        private const val ENTITY_TAG_HEADER = "ETag"
        private const val DOWNLOAD_BUFFER_SIZE = 8 * 1024
        private const val PROGRESS_REPORT_INTERVAL_BYTES = 64 * 1024L
        private const val HTTP_PARTIAL_CONTENT = 206
        private val HTTP_SUCCESS_RANGE = 200..299
        private val HTTP_SERVER_ERROR_RANGE = 500..599
    }
}

/** APK 摘要与 Release 清单不一致时使用，防止安装未通过完整性校验的文件。 */
internal class UpdateIntegrityException : IOException()
