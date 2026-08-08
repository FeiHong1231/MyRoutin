package com.hss.myroutin.repository

import com.hss.myroutin.model.ModelRadarSnapshot
import com.hss.myroutin.serialization.ModelRadarJsonCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * 说明：模型雷达的数据入口，直连 CodexRadar 公开接口并将第三方响应收敛为本地领域快照。
 *
 * @作者 huangssh
 * @版本 3.0
 */
internal class ModelRadarRepository(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /**
     * 完整智力数据为必需项；推荐和社区评分属于可选增强，单独失败不会阻断模型概览。
     */
    suspend fun load(): ModelRadarLoadResult {
        return withContext(ioDispatcher) {
            try {
                coroutineScope {
                    val intelligenceRequest = async { requestJson(INTELLIGENCE_URL) }
                    val metricsRequest = async { requestOptionalJson(METRICS_URL) }
                    val insightsRequest = async { requestOptionalJson(INSIGHTS_URL) }
                    val ratingsRequest = async { requestOptionalJson(RATINGS_URL) }
                    ModelRadarLoadResult.Success(
                        ModelRadarJsonCodec.decodeRemoteBundle(
                            intelligenceBody = intelligenceRequest.await(),
                            metricsBody = metricsRequest.await(),
                            insightsBody = insightsRequest.await(),
                            ratingsBody = ratingsRequest.await(),
                            fetchedAt = System.currentTimeMillis()
                        )
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: SocketTimeoutException) {
                ModelRadarLoadResult.Failure(ModelRadarLoadError.NetworkTimeout)
            } catch (exception: ModelRadarHttpException) {
                ModelRadarLoadResult.Failure(ModelRadarLoadError.Http(exception.responseCode))
            } catch (exception: IOException) {
                ModelRadarLoadResult.Failure(ModelRadarLoadError.NetworkUnavailable)
            } catch (exception: JSONException) {
                ModelRadarLoadResult.Failure(ModelRadarLoadError.InvalidResponse)
            } catch (exception: IllegalArgumentException) {
                ModelRadarLoadResult.Failure(ModelRadarLoadError.InvalidResponse)
            } catch (exception: Exception) {
                ModelRadarLoadResult.Failure(ModelRadarLoadError.Unknown)
            }
        }
    }

    /** 可选增强接口失败时返回空值，但协程取消必须继续向上传播。 */
    private fun requestOptionalJson(endpoint: String): String? {
        return try {
            requestJson(endpoint)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            null
        }
    }

    /**
     * 请求单个公开 JSON，并限制最大响应字符数，防止接口异常返回占满应用内存。
     * @param endpoint 固定的 CodexRadar HTTPS 地址
     */
    private fun requestJson(endpoint: String): String {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val responseCode = connection.responseCode
            if (responseCode !in HTTP_SUCCESS_RANGE) {
                throw ModelRadarHttpException(responseCode)
            }
            if (connection.contentLengthLong > MAX_RESPONSE_CHARACTERS.toLong()) {
                throw IOException("Radar response is too large")
            }
            return connection.inputStream.bufferedReader().use { reader ->
                val body = StringBuilder()
                val buffer = CharArray(RESPONSE_BUFFER_SIZE)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (body.length + count > MAX_RESPONSE_CHARACTERS) {
                        throw IOException("Radar response exceeds limit")
                    }
                    body.append(buffer, 0, count)
                }
                body.toString()
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        private const val INTELLIGENCE_URL = "https://codexradar.com/api/intelligence-efficiency"
        private const val METRICS_URL =
            "https://codexradar.com/api/intelligence-efficiency-metrics"
        private const val INSIGHTS_URL = "https://codexradar.com/api/radar-insights"
        private const val RATINGS_URL =
            "https://codexradar.com/api/model-ratings?view=public&history=14"
        private const val USER_AGENT = "MyRoutin-Android/3.0"
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val READ_TIMEOUT_MILLIS = 30_000
        private const val MAX_RESPONSE_CHARACTERS = 5_000_000
        private const val RESPONSE_BUFFER_SIZE = 8_192
        private val HTTP_SUCCESS_RANGE = 200..299
    }
}

/** 模型雷达加载结果明确区分成功快照与稳定失败类型。 */
internal sealed interface ModelRadarLoadResult {
    data class Success(val snapshot: ModelRadarSnapshot) : ModelRadarLoadResult
    data class Failure(val error: ModelRadarLoadError) : ModelRadarLoadResult
}

/** 页面只依赖稳定错误语义，不展示第三方响应正文或底层异常。 */
internal sealed interface ModelRadarLoadError {
    data class Http(val responseCode: Int) : ModelRadarLoadError
    object NetworkTimeout : ModelRadarLoadError
    object NetworkUnavailable : ModelRadarLoadError
    object InvalidResponse : ModelRadarLoadError
    object Unknown : ModelRadarLoadError
}

/** 非成功 HTTP 状态只保留状态码，响应正文不进入业务层。 */
private class ModelRadarHttpException(val responseCode: Int) : IOException()
