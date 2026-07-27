package com.hss.myroutin.repository

import android.util.Log
import com.hss.myroutin.BuildConfig
import com.hss.myroutin.model.PlanUsageSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 说明：订阅额度接口的数据入口，负责在 IO 线程完成鉴权请求、响应校验和 JSON 到领域模型的映射。
 *
 * @作者 huangssh
 * @版本 2.1
 */
class PlanUsageRepository(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val endpoint: String = USAGE_ENDPOINT
) {

    /**
     * 查询一个订阅 Key 的当前用量；网络和服务端异常统一转换为页面可展示的错误文案。
     * @param apiKey 用户输入的订阅 Key，仅以脱敏形式写入 Debug 日志
     * @param requestTrace 当前请求的来源和批次标识
     */
    suspend fun queryPlanUsage(apiKey: String, requestTrace: String): PlanUsageQueryResult {
        return withContext(ioDispatcher) {
            val requestStartedAt = System.currentTimeMillis()
            logDebug {
                "$requestTrace 请求：GET $endpoint，Authorization=Bearer ${maskKey(apiKey)}，Accept=application/json"
            }
            try {
                val usage = requestUsage(apiKey, requestTrace)
                logDebug {
                    "$requestTrace 请求完成，耗时 ${System.currentTimeMillis() - requestStartedAt}ms，" +
                        "解析结果=${if (usage == null) "空订阅" else "成功"}"
                }
                PlanUsageQueryResult(usage, null)
            } catch (throwable: Throwable) {
                logDebug(throwable) {
                    "$requestTrace 订阅额度查询失败，耗时 ${System.currentTimeMillis() - requestStartedAt}ms"
                }
                PlanUsageQueryResult(null, throwable.message ?: "查询失败")
            }
        }
    }

    /**
     * 发起真实接口请求；响应详情仅在 Debug 构建记录，Release 不输出服务端诊断数据。
     * @param apiKey 用户输入并保存到本地的订阅 Key
     * @param requestTrace 当前请求的来源和批次标识
     */
    private fun requestUsage(apiKey: String, requestTrace: String): PlanUsageSnapshot? {
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
            if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw IllegalStateException("鉴权失败 invalid_api_key")
            }
            if (responseCode !in 200..299) {
                throw IllegalStateException("HTTP $responseCode")
            }
            val body = responseText.trim()
            if (body.isEmpty() || body == "null") {
                return null
            }
            return parseUsage(JSONObject(body))
        } finally {
            connection.disconnect()
        }
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

    /**
     * 将接口 JSON 映射成页面展示所需字段，接口额外字段不会进入上层状态。
     * @param jsonObject 接口返回的订阅用量 JSON
     */
    private fun parseUsage(jsonObject: JSONObject): PlanUsageSnapshot {
        val allowedModels = jsonObject.optJSONArray("allowedModels")?.let { jsonArray ->
            (0 until jsonArray.length()).mapNotNull { index ->
                jsonArray.optString(index).takeIf { it.isNotBlank() }
            }
        }.orEmpty()
        val allowedGroups = jsonObject.optJSONArray("allowedGroups")?.let { jsonArray ->
            (0 until jsonArray.length()).mapNotNull { index ->
                jsonArray.optString(index).takeIf { it.isNotBlank() }
            }
        }.orEmpty()
        return PlanUsageSnapshot(
            planName = jsonObject.stringOrNull("planName"),
            type = jsonObject.intOrNull("type"),
            status = jsonObject.intOrNull("status"),
            startAt = jsonObject.stringOrNull("startAt"),
            endAt = jsonObject.stringOrNull("endAt"),
            dailyLimitUsd = jsonObject.doubleOrNull("dailyLimitUsd"),
            weeklyLimitUsd = jsonObject.doubleOrNull("weeklyLimitUsd"),
            dailyUsedUsd = jsonObject.doubleOrNull("dailyUsedUsd"),
            weeklyUsedUsd = jsonObject.doubleOrNull("weeklyUsedUsd"),
            dailyRemainingUsd = jsonObject.doubleOrNull("dailyRemainingUsd"),
            weeklyRemainingUsd = jsonObject.doubleOrNull("weeklyRemainingUsd"),
            dayWindowStartAt = jsonObject.stringOrNull("dayWindowStartAt"),
            dayWindowEndAt = jsonObject.stringOrNull("dayWindowEndAt"),
            weekWindowStartAt = jsonObject.stringOrNull("weekWindowStartAt"),
            weekWindowEndAt = jsonObject.stringOrNull("weekWindowEndAt"),
            totalTokens = jsonObject.longOrNull("totalTokens"),
            consumedTokens = jsonObject.longOrNull("consumedTokens"),
            remainingTokens = jsonObject.longOrNull("remainingTokens"),
            allowedModels = allowedModels,
            allowedGroups = allowedGroups,
            groupNames = jsonObject.stringMapOrEmpty("groupNames"),
            groupMultipliers = jsonObject.doubleMapOrEmpty("groupMultipliers")
        )
    }

    private fun JSONObject.stringOrNull(name: String): String? {
        return if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null
    }

    private fun JSONObject.intOrNull(name: String): Int? {
        return if (has(name) && !isNull(name)) optInt(name) else null
    }

    private fun JSONObject.longOrNull(name: String): Long? {
        return optLong(name, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
    }

    private fun JSONObject.doubleOrNull(name: String): Double? {
        return optDouble(name, Double.NaN).takeIf { !it.isNaN() }
    }

    private fun JSONObject.stringMapOrEmpty(name: String): Map<String, String> {
        val mapObject = optJSONObject(name) ?: return emptyMap()
        val result = linkedMapOf<String, String>()
        val keys = mapObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            mapObject.stringOrNull(key)?.let { value ->
                result[key] = value
            }
        }
        return result
    }

    private fun JSONObject.doubleMapOrEmpty(name: String): Map<String, Double> {
        val mapObject = optJSONObject(name) ?: return emptyMap()
        val result = linkedMapOf<String, Double>()
        val keys = mapObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            mapObject.doubleOrNull(key)?.let { value ->
                result[key] = value
            }
        }
        return result
    }

    /** 仅用于 Debug 日志，避免将完整长期凭证写入 Logcat。 */
    private fun maskKey(apiKey: String): String {
        return if (apiKey.length <= MASK_KEY_SHORT_LENGTH) {
            "${apiKey.take(4)}****"
        } else {
            "${apiKey.take(9)}****${apiKey.takeLast(6)}"
        }
    }

    private companion object {
        private const val PLAN_USAGE_LOG_TAG = "PlanUsageQuery"
        private const val MAX_LOG_CHUNK_SIZE = 3_000
        private const val USAGE_ENDPOINT = "https://api.routin.ai/plan/v1/usage"
        private const val CONNECT_TIMEOUT_MILLIS = 10_000
        private const val READ_TIMEOUT_MILLIS = 10_000
        private const val MASK_KEY_SHORT_LENGTH = 15
    }
}

/**
 * 说明：单 Key 查询结果，允许成功空订阅和失败状态共用同一套状态更新入口。
 *
 * @作者 huangssh
 * @版本 2.1
 */
data class PlanUsageQueryResult(
    val usage: PlanUsageSnapshot?,
    val error: String?
)
