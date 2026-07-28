package com.hss.myroutin.update

import com.hss.myroutin.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

/**
 * 说明：更新模块的 HTTP 连接创建边界，使清单请求和 APK 下载共用超时、标识及断点请求头。
 *
 * @作者 huangssh
 * @版本 2.3
 */
internal interface UpdateHttpConnectionFactory {

    /**
     * 创建尚未读取响应的 GET 连接，断点起始值大于零时附带 Range 和可用的 If-Range。
     * @param url 清单或 APK 地址
     * @param rangeStartBytes 断点续传起始字节，完整请求时为 0
     * @param entityTag 旧分片对应的强 ETag
     * @return 已配置完成的 HTTP 连接
     */
    fun open(
        url: String,
        rangeStartBytes: Long = 0L,
        entityTag: String? = null
    ): HttpURLConnection
}

/**
 * 说明：生产环境更新连接工厂，保持原有 HttpURLConnection 行为并允许测试替换网络边界。
 *
 * @param userAgent 请求中用于标识当前应用版本的 User-Agent
 * @作者 huangssh
 * @版本 2.3
 */
internal class DefaultUpdateHttpConnectionFactory(
    private val userAgent: String = "MyRoutin/${BuildConfig.VERSION_NAME}"
) : UpdateHttpConnectionFactory {

    override fun open(
        url: String,
        rangeStartBytes: Long,
        entityTag: String?
    ): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", userAgent)
            if (rangeStartBytes > 0L) {
                setRequestProperty(RANGE_HEADER, "bytes=$rangeStartBytes-")
                entityTag?.let { setRequestProperty(IF_RANGE_HEADER, it) }
            }
        }
    }

    private companion object {
        private const val RANGE_HEADER = "Range"
        private const val IF_RANGE_HEADER = "If-Range"
        private const val CONNECT_TIMEOUT_MILLIS = 10_000
        private const val READ_TIMEOUT_MILLIS = 15_000
    }
}
