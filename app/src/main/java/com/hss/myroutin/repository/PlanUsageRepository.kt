package com.hss.myroutin.repository

import android.util.Log
import com.hss.myroutin.BuildConfig
import com.hss.myroutin.logic.PlanUsageFormatter
import com.hss.myroutin.model.PlanUsageSnapshot
import com.hss.myroutin.serialization.PlanUsageSnapshotJsonCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.SocketTimeoutException
import java.io.IOException

/**
 * 说明：订阅额度接口的数据入口，负责在 IO 线程完成鉴权请求、响应校验和 JSON 到领域模型的映射。
 *
 * @作者 huangssh
 * @版本 2.3
 */
class PlanUsageRepository(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val endpoint: String = USAGE_ENDPOINT
) {

    /**
     * 查询一个订阅 Key 的当前用量；失败原因转换为稳定类型，调用方不再依赖底层异常文案。
     * @param apiKey 用户输入的订阅 Key，仅以脱敏形式写入 Debug 日志
     * @param requestTrace 当前请求的来源和批次标识
     */
    suspend fun queryPlanUsage(apiKey: String, requestTrace: String): PlanUsageQueryResult {
        return withContext(ioDispatcher) {
            val requestStartedAt = System.currentTimeMillis()
            logDebug {
                "$requestTrace 请求：GET $endpoint，" +
                    "Authorization=Bearer ${PlanUsageFormatter.maskKey(apiKey)}，Accept=application/json"
            }
            try {
                val result = requestUsage(apiKey, requestTrace)
                logDebug {
                    "$requestTrace 请求完成，耗时 ${System.currentTimeMillis() - requestStartedAt}ms，" +
                        "结果=${result.debugDescription()}"
                }
                result
            } catch (exception: CancellationException) {
                // 页面销毁或 ViewModel 清理时必须保留取消信号，禁止误报为一次请求失败。
                throw exception
            } catch (exception: SocketTimeoutException) {
                logDebug(exception) {
                    "$requestTrace 订阅额度查询失败，耗时 ${System.currentTimeMillis() - requestStartedAt}ms"
                }
                PlanUsageQueryResult.Failure(PlanUsageQueryError.NetworkTimeout)
            } catch (exception: IOException) {
                logDebug(exception) {
                    "$requestTrace 订阅额度查询失败，耗时 ${System.currentTimeMillis() - requestStartedAt}ms"
                }
                PlanUsageQueryResult.Failure(PlanUsageQueryError.NetworkUnavailable)
            } catch (exception: JSONException) {
                logDebug(exception) {
                    "$requestTrace 服务响应解析失败，耗时 ${System.currentTimeMillis() - requestStartedAt}ms"
                }
                PlanUsageQueryResult.Failure(PlanUsageQueryError.InvalidResponse)
            } catch (exception: Exception) {
                logDebug(exception) {
                    "$requestTrace 订阅额度查询出现未分类异常，耗时 ${System.currentTimeMillis() - requestStartedAt}ms"
                }
                PlanUsageQueryResult.Failure(PlanUsageQueryError.Unknown)
            }
        }
    }

    /**
     * 发起真实接口请求；响应详情仅在 Debug 构建记录，Release 不输出服务端诊断数据。
     * @param apiKey 用户输入并保存到本地的订阅 Key
     * @param requestTrace 当前请求的来源和批次标识
     */
    private fun requestUsage(apiKey: String, requestTrace: String): PlanUsageQueryResult {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val responseCode = connection.responseCode
            val responseText = (if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader()?.use { it.readText() }.orEmpty()
            logDebugResponse(requestTrace, responseCode, connection.headerFields, responseText)
            return mapHttpResponse(responseCode, responseText)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 按服务端契约映射 HTTP 响应；只有字面量 `null` 表示订阅过期，空正文属于格式异常。
     * @param responseCode HTTP 响应状态码
     * @param responseText 服务端原始响应正文
     */
    internal fun mapHttpResponse(responseCode: Int, responseText: String): PlanUsageQueryResult {
        if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
            // 当前接口约定 401 携带 invalid_api_key；以状态码为稳定主判据，避免依赖服务端文案。
            return PlanUsageQueryResult.Failure(PlanUsageQueryError.InvalidApiKey)
        }
        if (responseCode !in 200..299) {
            return PlanUsageQueryResult.Failure(PlanUsageQueryError.Http(responseCode))
        }
        val body = responseText.trim()
        if (body == "null") {
            return PlanUsageQueryResult.Expired
        }
        if (body.isEmpty()) {
            return PlanUsageQueryResult.Failure(PlanUsageQueryError.InvalidResponse)
        }
        return PlanUsageQueryResult.Available(PlanUsageSnapshotJsonCodec.decode(JSONObject(body)))
    }

    /**
     * 仅在 Debug 构建输出响应详情，避免正式包的 Logcat 包含用户套餐信息或服务端诊断数据。
     * @param requestTrace 当前请求的来源和批次标识
     * @param responseCode HTTP 响应状态码
     * @param responseHeaders 服务端响应头
     * @param responseBody 服务端原始响应体
     */
    private fun logDebugResponse(
        requestTrace: String,
        responseCode: Int,
        responseHeaders: Map<String?, List<String>?>,
        responseBody: String
    ) {
        if (!BuildConfig.DEBUG) {
            return
        }
        Log.d(PLAN_USAGE_LOG_TAG, "$requestTrace 响应状态：HTTP $responseCode")
        logDebugLongMessage("$requestTrace 响应头", responseHeaders.toString())
        logDebugLongMessage("$requestTrace 响应体", responseBody)
    }

    /**
     * 将 Debug 环境的较长接口日志拆成多条输出，避免系统截断原始响应。
     * @param label 日志内容的业务标签
     * @param content 需要完整输出的原始内容
     */
    private fun logDebugLongMessage(label: String, content: String) {
        if (content.isEmpty()) {
            Log.d(PLAN_USAGE_LOG_TAG, "$label：<空>")
            return
        }
        content.chunked(MAX_LOG_CHUNK_SIZE).forEachIndexed { index, chunk ->
            Log.d(PLAN_USAGE_LOG_TAG, "$label（${index + 1}）：$chunk")
        }
    }

    /**
     * Debug 日志使用惰性消息构造，保证 Release 连请求标识和脱敏 Key 都不会进入 Logcat。
     * @param message Debug 构建时才计算的日志内容
     */
    private inline fun logDebug(message: () -> String) {
        if (BuildConfig.DEBUG) {
            Log.d(PLAN_USAGE_LOG_TAG, message())
        }
    }

    /**
     * Debug 异常日志用于本地调试；Release 构建直接跳过，避免输出网络诊断细节。
     * @param throwable 当前请求抛出的异常
     * @param message Debug 构建时才计算的日志内容
     */
    private inline fun logDebug(throwable: Throwable, message: () -> String) {
        if (BuildConfig.DEBUG) {
            Log.e(PLAN_USAGE_LOG_TAG, message(), throwable)
        }
    }

    /** 将强类型结果压缩为仅供 Debug 日志使用的描述，Release 包不会输出该内容。 */
    private fun PlanUsageQueryResult.debugDescription(): String {
        return when (this) {
            is PlanUsageQueryResult.Available -> "有效订阅"
            PlanUsageQueryResult.Expired -> "订阅过期"
            is PlanUsageQueryResult.Failure -> "失败：${error.javaClass.simpleName}"
        }
    }

    private companion object {
        private const val PLAN_USAGE_LOG_TAG = "PlanUsageQuery"
        private const val MAX_LOG_CHUNK_SIZE = 3_000
        private const val USAGE_ENDPOINT = "https://api.routin.ai/plan/v1/usage"
        private const val CONNECT_TIMEOUT_MILLIS = 10_000
        private const val READ_TIMEOUT_MILLIS = 10_000
    }
}

/**
 * 说明：单 Key 查询结果严格区分有效订阅、订阅过期和请求失败，调用方不再解释可空快照。
 *
 * @作者 huangssh
 * @版本 2.3
 */
sealed interface PlanUsageQueryResult {

    /** 当前存在可用订阅，携带服务端最新用量快照。 */
    data class Available(val usage: PlanUsageSnapshot) : PlanUsageQueryResult

    /** 接口成功返回 `null`，按当前契约表示该 Key 的订阅已经过期。 */
    object Expired : PlanUsageQueryResult

    /** 请求未成功，错误类型只携带安全的展示语义，不暴露底层异常或服务端原文。 */
    data class Failure(val error: PlanUsageQueryError) : PlanUsageQueryResult
}

/**
 * 说明：订阅请求的稳定失败分类，供页面展示、后续重试策略和自动测试按类型处理。
 *
 * @作者 huangssh
 * @版本 2.1
 */
sealed interface PlanUsageQueryError {

    /** 页面使用该文案，不会显示异常栈、接口响应体或完整请求信息。 */
    val userMessage: String

    object InvalidApiKey : PlanUsageQueryError {
        override val userMessage = "API Key 无效或已失效"
    }

    data class Http(val responseCode: Int) : PlanUsageQueryError {
        override val userMessage: String
            get() = when (responseCode) {
                HttpURLConnection.HTTP_FORBIDDEN -> "当前 Key 无访问权限（HTTP 403）"
                429 -> "请求过于频繁，请稍后重试（HTTP 429）"
                in 500..599 -> "服务暂时不可用，请稍后重试（HTTP $responseCode）"
                else -> "请求失败（HTTP $responseCode）"
            }
    }

    object NetworkTimeout : PlanUsageQueryError {
        override val userMessage = "网络连接超时，请检查网络后重试"
    }

    object NetworkUnavailable : PlanUsageQueryError {
        override val userMessage = "网络连接失败，请检查网络后重试"
    }

    object InvalidResponse : PlanUsageQueryError {
        override val userMessage = "服务返回的数据格式异常，请稍后重试"
    }

    object Unknown : PlanUsageQueryError {
        override val userMessage = "查询失败，请稍后重试"
    }
}
