package com.hss.myroutin.repository

import android.os.Build
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
            logDiagnostic {
                val maskedKey = if (BuildConfig.DEBUG) {
                    PlanUsageFormatter.maskKey(apiKey)
                } else {
                    "<hidden>"
                }
                "$requestTrace 请求开始：method=GET，endpoint=$endpoint，" +
                    "key=$maskedKey，Accept=application/json，" +
                    "connectTimeoutMs=$CONNECT_TIMEOUT_MILLIS，readTimeoutMs=$READ_TIMEOUT_MILLIS"
            }
            try {
                val result = requestUsage(apiKey, requestTrace)
                logDiagnostic {
                    "$requestTrace 请求完成：elapsedMs=${System.currentTimeMillis() - requestStartedAt}，" +
                        "结果=${result.debugDescription()}"
                }
                result
            } catch (exception: CancellationException) {
                // 页面销毁或 ViewModel 清理时必须保留取消信号，禁止误报为一次请求失败。
                throw exception
            } catch (exception: SocketTimeoutException) {
                logQueryFailure(requestTrace, requestStartedAt, "NetworkTimeout", exception)
                PlanUsageQueryResult.Failure(PlanUsageQueryError.NetworkTimeout)
            } catch (exception: IOException) {
                logQueryFailure(requestTrace, requestStartedAt, "NetworkUnavailable", exception)
                PlanUsageQueryResult.Failure(PlanUsageQueryError.NetworkUnavailable)
            } catch (exception: JSONException) {
                logQueryFailure(requestTrace, requestStartedAt, "InvalidResponse", exception)
                PlanUsageQueryResult.Failure(PlanUsageQueryError.InvalidResponse)
            } catch (exception: Exception) {
                logQueryFailure(requestTrace, requestStartedAt, "Unknown", exception)
                PlanUsageQueryResult.Failure(PlanUsageQueryError.Unknown)
            }
        }
    }

    /**
     * 发起真实接口请求；所有构建记录安全摘要，完整响应详情仅在 Debug 构建记录。
     * @param apiKey 用户输入并保存到本地的订阅 Key
     * @param requestTrace 当前请求的来源和批次标识
     */
    private fun requestUsage(apiKey: String, requestTrace: String): PlanUsageQueryResult {
        val requestUrl = URL(endpoint)
        val connection = (requestUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")
        }
        try {
            logDiagnostic {
                "$requestTrace 连接开始：protocol=${requestUrl.protocol}，host=${requestUrl.host}，" +
                    "port=${requestUrl.port}，proxy=${connection.usingProxy()}"
            }
            connection.connect()
            logDiagnostic {
                "$requestTrace 连接建立：proxy=${connection.usingProxy()}"
            }
            val responseCode = connection.responseCode
            logDiagnostic {
                "$requestTrace 收到响应：http=$responseCode，message=${connection.responseMessage.orEmpty()}"
            }
            val responseText = (if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader()?.use { it.readText() }.orEmpty()
            logDiagnostic {
                "$requestTrace 响应体读取完成：bodyLength=${responseText.length}"
            }
            logResponseSummary(requestTrace, connection, responseCode, responseText)
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
        logDebugLongMessage("$requestTrace 响应头", responseHeaders.toString())
        logDebugLongMessage("$requestTrace 响应体", responseBody)
    }

    /**
     * 输出所有构建变体都可见的安全响应摘要，便于区分 HTTP、空响应和网络链路失败。
     * @param requestTrace 当前请求的来源和批次标识
     * @param connection 已收到响应的 HTTP 连接
     * @param responseCode 服务端 HTTP 状态码
     * @param responseBody 已读取的响应正文，仅记录长度不记录正文内容
     */
    private fun logResponseSummary(
        requestTrace: String,
        connection: HttpURLConnection,
        responseCode: Int,
        responseBody: String
    ) {
        val responseUrl = connection.url
        logDiagnostic {
            "$requestTrace 响应摘要：http=$responseCode，" +
                "finalUrl=${responseUrl.protocol}://${responseUrl.host}${responseUrl.path}，" +
                "contentType=${connection.contentType.orEmpty()}，" +
                "contentLength=${connection.contentLengthLong}，bodyLength=${responseBody.length}"
        }
    }

    /**
     * 输出网络失败的映射类型和底层异常摘要；Debug 构建额外保留完整堆栈供定位 DNS/TLS 细节。
     * @param requestTrace 当前请求的来源和批次标识
     * @param requestStartedAt 请求开始时间，单位为毫秒
     * @param mappedError 当前异常映射到的业务错误类型
     * @param exception 请求抛出的底层异常
     */
    private fun logQueryFailure(
        requestTrace: String,
        requestStartedAt: Long,
        mappedError: String,
        exception: Throwable
    ) {
        val cause = exception.cause
        Log.e(
            PLAN_USAGE_LOG_TAG,
            "$requestTrace 查询失败：mappedError=$mappedError，" +
                "elapsedMs=${System.currentTimeMillis() - requestStartedAt}，" +
                "exception=${exception::class.java.name}，message=${exception.message.orEmpty()}，" +
                "cause=${cause?.javaClass?.name.orEmpty()}，causeMessage=${cause?.message.orEmpty()}"
        )
        if (BuildConfig.DEBUG) {
            Log.e(PLAN_USAGE_LOG_TAG, "$requestTrace 查询异常堆栈", exception)
        }
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
     * 输出不包含完整凭证或响应正文的基础诊断日志，正式包也保留以支持现场排查。
     * @param message 仅包含脱敏请求标识和协议摘要的日志内容
     */
    private inline fun logDiagnostic(message: () -> String) {
        Log.i(
            PLAN_USAGE_LOG_TAG,
            "device=${Build.MANUFACTURER}/${Build.MODEL}，sdk=${Build.VERSION.SDK_INT}，${message()}"
        )
    }

    /** 将强类型结果压缩为不含额度内容的安全日志描述。 */
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
    object InvalidApiKey : PlanUsageQueryError

    /** HTTP 状态码由 UI 边界映射为可本地化文案，Repository 只保留稳定诊断数据。 */
    data class Http(val responseCode: Int) : PlanUsageQueryError

    object NetworkTimeout : PlanUsageQueryError

    object NetworkUnavailable : PlanUsageQueryError

    object InvalidResponse : PlanUsageQueryError

    object Unknown : PlanUsageQueryError
}
