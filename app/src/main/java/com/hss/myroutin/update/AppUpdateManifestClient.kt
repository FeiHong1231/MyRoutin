package com.hss.myroutin.update

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection

/**
 * 说明：GitHub Release 更新清单客户端，只负责请求、解析和校验清单以及取消当前检查连接。
 *
 * @param connectionFactory 更新模块统一使用的 HTTP 连接工厂
 * @作者 huangssh
 * @版本 2.3
 */
internal class AppUpdateManifestClient(
    private val connectionFactory: UpdateHttpConnectionFactory
) {

    /** 当前更新清单连接，供手动检查被取消或重试时立即中断旧请求。 */
    @Volatile
    private var activeConnection: HttpURLConnection? = null

    /**
     * 请求并解析 GitHub 最新 Release 附带的 update.json；不信任非固定仓库的下载地址。
     * @param manifestUrl 固定仓库的更新清单地址
     * @return 已完成字段和来源校验的更新清单
     */
    fun request(manifestUrl: String): AppUpdateManifest {
        val connection = connectionFactory.open(manifestUrl)
        activeConnection = connection
        try {
            if (connection.responseCode !in HTTP_SUCCESS_RANGE) {
                throw UpdateHttpException(connection.responseCode)
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return parse(body)
        } finally {
            if (activeConnection === connection) {
                activeConnection = null
            }
            connection.disconnect()
        }
    }

    /** 用户关闭手动检查提示或再次发起检查时，立即中断尚未完成的清单请求。 */
    fun cancel() {
        activeConnection?.disconnect()
    }

    /**
     * 校验清单的版本、下载来源、摘要和文件大小；异常清单不会进入下载或安装流程。
     * @param body GitHub Release 附带的 update.json 原文
     * @return 仅包含安全可用字段的更新信息
     */
    internal fun parse(body: String): AppUpdateManifest {
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

    private companion object {
        private const val TRUSTED_APK_URL_PREFIX =
            "https://github.com/huangssh/MyRoutin/releases/download/"
        private const val INVALID_VERSION_CODE = -1
        private const val INVALID_APK_SIZE = -1L
        private val HTTP_SUCCESS_RANGE = 200..299
        private val SHA_256_PATTERN = Regex("^[a-fA-F0-9]{64}$")
    }
}

/** 更新清单字段缺失或来源异常时使用，禁止继续处理该远端数据。 */
internal class UpdateManifestException : IllegalArgumentException()

/**
 * 清单或 APK 请求返回非成功状态码时使用，下载层据此区分可重试的服务端错误。
 * @param responseCode 服务端 HTTP 状态码，连接尚未取得响应时为 null
 */
internal class UpdateHttpException(val responseCode: Int? = null) : IOException()
