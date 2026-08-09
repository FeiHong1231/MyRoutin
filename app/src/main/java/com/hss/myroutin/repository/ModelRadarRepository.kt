package com.hss.myroutin.repository

import android.util.Log
import com.hss.myroutin.BuildConfig
import com.hss.myroutin.model.ModelRadarSnapshot
import com.hss.myroutin.serialization.ModelRadarJsonCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
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
            val loadStartedAt = System.currentTimeMillis()
            ModelRadarDiagnostics.debug { "远端加载开始" }
            try {
                coroutineScope {
                    val intelligenceRequest = async { requestJson(INTELLIGENCE_URL) }
                    val metricsRequest = async { requestOptionalJson(METRICS_URL) }
                    val insightsRequest = async { requestOptionalJson(INSIGHTS_URL) }
                    val ratingsRequest = async { requestOptionalJson(RATINGS_URL) }
                    val intelligenceBody = intelligenceRequest.await()
                    val metricsBody = metricsRequest.await()
                    val insightsBody = insightsRequest.await()
                    val ratingsBody = ratingsRequest.await()
                    ModelRadarDiagnostics.logMetricsResponse(metricsBody)
                    val snapshot = ModelRadarJsonCodec.decodeRemoteBundle(
                        intelligenceBody = intelligenceBody,
                        metricsBody = metricsBody,
                        insightsBody = insightsBody,
                        ratingsBody = ratingsBody,
                        fetchedAt = System.currentTimeMillis()
                    )
                    ModelRadarDiagnostics.debug {
                        "远端加载成功，耗时=${System.currentTimeMillis() - loadStartedAt}ms"
                    }
                    ModelRadarDiagnostics.logSnapshot("网络", snapshot)
                    ModelRadarLoadResult.Success(snapshot)
                }
            } catch (exception: CancellationException) {
                ModelRadarDiagnostics.debug { "远端加载取消" }
                throw exception
            } catch (exception: SocketTimeoutException) {
                ModelRadarDiagnostics.error("远端加载超时", exception)
                ModelRadarLoadResult.Failure(ModelRadarLoadError.NetworkTimeout)
            } catch (exception: ModelRadarHttpException) {
                ModelRadarDiagnostics.error(
                    "必需接口响应失败，HTTP ${exception.responseCode}",
                    exception
                )
                ModelRadarLoadResult.Failure(ModelRadarLoadError.Http(exception.responseCode))
            } catch (exception: IOException) {
                ModelRadarDiagnostics.error("远端加载网络异常", exception)
                ModelRadarLoadResult.Failure(ModelRadarLoadError.NetworkUnavailable)
            } catch (exception: JSONException) {
                ModelRadarDiagnostics.error("远端响应 JSON 解析失败", exception)
                ModelRadarLoadResult.Failure(ModelRadarLoadError.InvalidResponse)
            } catch (exception: IllegalArgumentException) {
                ModelRadarDiagnostics.error("远端响应字段校验失败", exception)
                ModelRadarLoadResult.Failure(ModelRadarLoadError.InvalidResponse)
            } catch (exception: Exception) {
                ModelRadarDiagnostics.error("远端加载出现未分类异常", exception)
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
            ModelRadarDiagnostics.error(
                "可选接口 ${endpointLabel(endpoint)} 失败，已降级为空数据",
                exception
            )
            null
        }
    }

    /**
     * 请求单个公开 JSON，并限制最大响应字符数，防止接口异常返回占满应用内存。
     * @param endpoint 固定的 CodexRadar HTTPS 地址
     */
    private fun requestJson(endpoint: String): String {
        val endpointLabel = endpointLabel(endpoint)
        val requestStartedAt = System.currentTimeMillis()
        ModelRadarDiagnostics.debug { "$endpointLabel 请求开始" }
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val responseCode = connection.responseCode
            ModelRadarDiagnostics.debug { "$endpointLabel 响应状态：HTTP $responseCode" }
            if (responseCode !in HTTP_SUCCESS_RANGE) {
                throw ModelRadarHttpException(responseCode)
            }
            if (connection.contentLengthLong > MAX_RESPONSE_CHARACTERS.toLong()) {
                throw IOException("Radar response is too large")
            }
            val responseBody = connection.inputStream.bufferedReader().use { reader ->
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
            ModelRadarDiagnostics.debug {
                "$endpointLabel 请求完成，耗时=${System.currentTimeMillis() - requestStartedAt}ms，" +
                    "响应字符数=${responseBody.length}"
            }
            return responseBody
        } finally {
            connection.disconnect()
        }
    }

    /** 将固定接口地址转换为稳定的日志标签，便于过滤单个数据源。 */
    private fun endpointLabel(endpoint: String): String {
        return when (endpoint) {
            INTELLIGENCE_URL -> "intelligence-efficiency"
            METRICS_URL -> "intelligence-efficiency-metrics"
            INSIGHTS_URL -> "radar-insights"
            RATINGS_URL -> "model-ratings"
            else -> "unknown-endpoint"
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

/**
 * 说明：模型雷达 Debug 诊断日志入口，统一记录数据来源和档位聚合结果。
 *
 * @作者 huangssh
 * @版本 3.0
 */
internal object ModelRadarDiagnostics {

    /** 稳定 TAG 供 Android Studio Logcat 和 adb 精确过滤。 */
    private const val LOG_TAG = "ModelRadar"

    /** Debug 构建才生成普通诊断消息，避免 Release 输出第三方数据。 */
    fun debug(message: () -> String) {
        if (BuildConfig.DEBUG) {
            Log.d(LOG_TAG, message())
        }
    }

    /**
     * Debug 构建记录请求或解析异常，保留堆栈用于区分网络、HTTP 和字段问题。
     * @param message 异常所属阶段
     * @param throwable 原始异常
     */
    fun error(message: String, throwable: Throwable) {
        if (BuildConfig.DEBUG) {
            Log.e(LOG_TAG, message, throwable)
        }
    }

    /**
     * 记录轻量效率接口的原始字段摘要，用来区分服务端空值与客户端档位匹配失败。
     * @param responseBody intelligence-efficiency-metrics 响应正文，可为空
     */
    fun logMetricsResponse(responseBody: String?) {
        if (!BuildConfig.DEBUG) return
        if (responseBody == null) {
            Log.d(LOG_TAG, "metrics 原始摘要：响应为空")
            return
        }
        try {
            val root = JSONObject(responseBody)
            val points = root.optJSONArray("points")
            Log.d(
                LOG_TAG,
                "metrics 原始摘要：schema=${root.optInt("schema")}，" +
                    "sourceUpdatedAt=${root.optString("source_updated_at")}，" +
                    "runs24hTotal=${root.opt("runs_24h_total")}，" +
                    "runs48hTotal=${root.opt("runs_48h_total")}，" +
                    "runsTotal=${root.opt("runs_total")}，points=${points?.length() ?: 0}"
            )
            if (points != null) {
                for (index in 0 until points.length()) {
                    val point = points.optJSONObject(index) ?: continue
                    Log.d(
                        LOG_TAG,
                        "metrics 原始档位[$index]：model=${point.opt("model")}，" +
                            "effort=${point.opt("effort")}，runs24h=${point.opt("runs_24h")}，" +
                            "averageAgentSteps=${point.opt("average_agent_steps")}，" +
                            "averageTotalTokens=${point.opt("average_total_tokens")}，" +
                            "cacheHitRate=${point.opt("cache_hit_rate")}"
                    )
                }
            }
        } catch (exception: JSONException) {
            Log.e(LOG_TAG, "metrics 原始摘要解析失败", exception)
        }
    }

    /**
     * 输出页面实际使用的聚合快照，重点保留近 24 小时运行次数的空值证据。
     * @param source 快照来源，例如网络或缓存
     * @param snapshot 页面实际展示的模型雷达快照
     */
    fun logSnapshot(source: String, snapshot: ModelRadarSnapshot) {
        if (!BuildConfig.DEBUG) return
        Log.d(
            LOG_TAG,
            "$source 快照：fetchedAt=${snapshot.fetchedAt}，" +
                "models=${snapshot.models.size}，efficiencyPoints=${snapshot.efficiencyPoints.size}，" +
                "recentRuns24h=${snapshot.recentRuns24h}"
        )
        snapshot.efficiencyPoints.forEachIndexed { index, point ->
            Log.d(
                LOG_TAG,
                "$source 智力效率[$index]：modelId=${point.modelId}，effort=${point.effort}，" +
                    "iq=${point.iq}，tasks=${point.passedTasks}/${point.validTasks}，" +
                    "recentRuns24h=${point.recentRuns24h}，totalRuns=${point.totalRuns}，" +
                    "averageCostUsd=${point.averageCostUsd}，" +
                    "averageDurationMinutes=${point.averageDurationMinutes}，" +
                    "averageAgentSteps=${point.averageAgentSteps}，" +
                    "averageTotalTokens=${point.averageTotalTokens}，" +
                    "cacheHitRate=${point.cacheHitRate}，communityRating=${point.communityRating}，" +
                    "communityRatingCount=${point.communityRatingCount}"
            )
        }
    }
}
