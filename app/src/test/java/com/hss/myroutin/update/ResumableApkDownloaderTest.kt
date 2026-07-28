package com.hss.myroutin.update

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

/**
 * 说明：通过本地 HTTP 服务验证 APK 首次下载、Range/ETag 续传、完整回退和异常文件清理。
 *
 * @作者 huangssh
 * @版本 2.3
 */
class ResumableApkDownloaderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** 每个用例使用独立服务端和缓存目录，避免请求队列或分片状态互相污染。 */
    private lateinit var server: MockWebServer
    private lateinit var fileStore: UpdateDownloadFileStore
    private lateinit var downloader: ResumableApkDownloader

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        fileStore = UpdateDownloadFileStore(temporaryFolder.newFolder("updates"))
        downloader = ResumableApkDownloader(
            connectionFactory = DefaultUpdateHttpConnectionFactory(userAgent = "MyRoutin/test"),
            fileStore = fileStore
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun freshDownload_publishesVerifiedApkAndFinalProgress() = runBlocking {
        val apkBytes = "complete-apk".toByteArray()
        server.enqueue(response(200, apkBytes, entityTag = "\"release-v1\""))
        val progressValues = mutableListOf<UpdateDownloadProgress>()

        val result = downloader.download(updateFor(apkBytes)) { progress ->
            progressValues.add(progress)
        }

        assertTrue(result is AppUpdateDownloadResult.Success)
        result as AppUpdateDownloadResult.Success
        assertArrayEquals(apkBytes, result.apkFile.readBytes())
        assertEquals(UpdateDownloadProgress(apkBytes.size.toLong(), apkBytes.size.toLong()), progressValues.last())
        assertFalse(fileStore.filesFor(VERSION_CODE).temporaryFile.exists())
        assertFalse(fileStore.filesFor(VERSION_CODE).entityTagFile.exists())
    }

    @Test
    fun validPartialResponse_sendsRangeAndIfRangeThenAppendsBytes() = runBlocking {
        val apkBytes = "abcdefghij".toByteArray()
        seedPartial(apkBytes.copyOfRange(0, 5), "\"release-v1\"")
        server.enqueue(
            response(206, apkBytes.copyOfRange(5, apkBytes.size), entityTag = "\"release-v1\"")
                .addHeader("Content-Range", "bytes 5-9/10")
        )

        val result = downloader.download(updateFor(apkBytes)) {}

        assertTrue(result is AppUpdateDownloadResult.Success)
        result as AppUpdateDownloadResult.Success
        assertArrayEquals(apkBytes, result.apkFile.readBytes())
        val request = server.takeRequest()
        assertEquals("bytes=5-", request.getHeader("Range"))
        assertEquals("\"release-v1\"", request.getHeader("If-Range"))
    }

    @Test
    fun ignoredRangeResponse_replacesOldPartialWithoutAppending() = runBlocking {
        val apkBytes = "new-complete-apk".toByteArray()
        seedPartial("stale".toByteArray(), "\"release-v1\"")
        server.enqueue(response(200, apkBytes, entityTag = "\"release-v2\""))

        val result = downloader.download(updateFor(apkBytes)) {}

        assertTrue(result is AppUpdateDownloadResult.Success)
        result as AppUpdateDownloadResult.Success
        assertArrayEquals(apkBytes, result.apkFile.readBytes())
        assertEquals("bytes=5-", server.takeRequest().getHeader("Range"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun changedEntityTag_rejectsPartialResponseAndRetriesFullDownload() = runBlocking {
        val apkBytes = "abcdefghij".toByteArray()
        seedPartial(apkBytes.copyOfRange(0, 5), "\"release-v1\"")
        server.enqueue(
            response(206, apkBytes.copyOfRange(5, apkBytes.size), entityTag = "\"release-v2\"")
                .addHeader("Content-Range", "bytes 5-9/10")
        )
        server.enqueue(response(200, apkBytes, entityTag = "\"release-v2\""))

        val result = downloader.download(updateFor(apkBytes)) {}

        assertTrue(result is AppUpdateDownloadResult.Success)
        result as AppUpdateDownloadResult.Success
        assertArrayEquals(apkBytes, result.apkFile.readBytes())
        val rangeRequest = server.takeRequest()
        val fullRequest = server.takeRequest()
        assertEquals("bytes=5-", rangeRequest.getHeader("Range"))
        assertEquals("\"release-v1\"", rangeRequest.getHeader("If-Range"))
        assertNull(fullRequest.getHeader("Range"))
        assertNull(fullRequest.getHeader("If-Range"))
    }

    @Test
    fun weakCachedEntityTag_isNotSentInIfRange() = runBlocking {
        val apkBytes = "abcdefghij".toByteArray()
        seedPartial(apkBytes.copyOfRange(0, 5), "W/\"release-v1\"")
        server.enqueue(
            response(206, apkBytes.copyOfRange(5, apkBytes.size), entityTag = "\"release-v1\"")
                .addHeader("Content-Range", "bytes 5-9/10")
        )

        val result = downloader.download(updateFor(apkBytes)) {}

        assertTrue(result is AppUpdateDownloadResult.Success)
        val request = server.takeRequest()
        assertEquals("bytes=5-", request.getHeader("Range"))
        assertNull(request.getHeader("If-Range"))
    }

    @Test
    fun nonSuccessResponse_deletesPartialAndEntityTag() = runBlocking {
        val files = seedPartial("part".toByteArray(), "\"release-v1\"")
        server.enqueue(MockResponse().setResponseCode(500))

        val result = downloader.download(updateFor("complete".toByteArray())) {}

        assertEquals(
            AppUpdateDownloadResult.Failure(AppUpdateDownloadFailureReason.NETWORK),
            result
        )
        assertFalse(files.temporaryFile.exists())
        assertFalse(files.entityTagFile.exists())
        assertFalse(files.targetFile.exists())
    }

    @Test
    fun partialResponseWithoutRangeRequest_isRejectedAndCleaned() = runBlocking {
        val apkBytes = "complete".toByteArray()
        server.enqueue(
            response(206, apkBytes, entityTag = "\"release-v1\"")
                .addHeader("Content-Range", "bytes 0-7/8")
        )

        val result = downloader.download(updateFor(apkBytes)) {}

        assertEquals(
            AppUpdateDownloadResult.Failure(AppUpdateDownloadFailureReason.NETWORK),
            result
        )
        val files = fileStore.filesFor(VERSION_CODE)
        assertFalse(files.temporaryFile.exists())
        assertFalse(files.entityTagFile.exists())
        assertFalse(files.targetFile.exists())
    }

    @Test
    fun unavailableUpdateDirectory_returnsDedicatedFailureWithoutNetworkRequest() = runBlocking {
        val invalidDirectory = temporaryFolder.newFile("not-a-directory")
        val unavailableDownloader = ResumableApkDownloader(
            connectionFactory = DefaultUpdateHttpConnectionFactory(userAgent = "MyRoutin/test"),
            fileStore = UpdateDownloadFileStore(invalidDirectory)
        )

        val result = unavailableDownloader.download(updateFor("complete".toByteArray())) {}

        assertEquals(
            AppUpdateDownloadResult.Failure(AppUpdateDownloadFailureReason.DIRECTORY_UNAVAILABLE),
            result
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun sha256Mismatch_deletesDownloadedFilesAndReturnsIntegrityFailure() = runBlocking {
        val apkBytes = "tampered-apk".toByteArray()
        server.enqueue(response(200, apkBytes, entityTag = "\"release-v1\""))
        val update = updateFor(apkBytes).copy(sha256 = "0".repeat(64))

        val result = downloader.download(update) {}

        assertEquals(
            AppUpdateDownloadResult.Failure(AppUpdateDownloadFailureReason.INTEGRITY_CHECK_FAILED),
            result
        )
        val files = fileStore.filesFor(VERSION_CODE)
        assertFalse(files.temporaryFile.exists())
        assertFalse(files.entityTagFile.exists())
        assertFalse(files.targetFile.exists())
    }

    /** 构造带真实 Content-Length 的本地 HTTP 响应，避免测试虚构下载总量。 */
    private fun response(statusCode: Int, body: ByteArray, entityTag: String? = null): MockResponse {
        return MockResponse()
            .setResponseCode(statusCode)
            .setBody(Buffer().write(body))
            .apply {
                entityTag?.let { addHeader("ETag", it) }
            }
    }

    /** 生成摘要和文件大小一致的更新对象，下载地址只指向当前测试服务端。 */
    private fun updateFor(apkBytes: ByteArray): AppUpdateManifest {
        return AppUpdateManifest(
            versionCode = VERSION_CODE,
            versionName = "test",
            apkUrl = server.url("/MyRoutin-test.apk").toString(),
            sha256 = sha256(apkBytes),
            apkSizeBytes = apkBytes.size.toLong()
        )
    }

    /** 写入一次暂停后会留下的分片和校验器状态，直接验证后续续传请求。 */
    private fun seedPartial(bytes: ByteArray, entityTag: String): UpdateDownloadFiles {
        val files = fileStore.filesFor(VERSION_CODE)
        files.temporaryFile.writeBytes(bytes)
        files.entityTagFile.writeText(entityTag)
        return files
    }

    /** 计算测试 APK 的真实 SHA-256，保证成功用例同样经过生产摘要校验。 */
    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private companion object {
        private const val VERSION_CODE = 99
    }
}
