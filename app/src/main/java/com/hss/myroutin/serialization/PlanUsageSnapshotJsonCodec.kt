package com.hss.myroutin.serialization

import com.hss.myroutin.model.PlanUsageSnapshot
import org.json.JSONArray
import org.json.JSONObject

/**
 * 说明：统一定义接口响应和本地缓存共用的用量快照 JSON 格式，避免两条链路分别维护字段映射。
 *
 * @作者 huangssh
 * @版本 2.3
 */
internal object PlanUsageSnapshotJsonCodec {

    /**
     * 将接口或缓存 JSON 转换为页面使用的完整用量快照，未知字段会被忽略。
     * @param jsonObject 服务端响应或本地缓存中的快照对象
     */
    fun decode(jsonObject: JSONObject): PlanUsageSnapshot {
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
            allowedModels = jsonObject.stringList("allowedModels"),
            allowedGroups = jsonObject.stringList("allowedGroups"),
            groupNames = jsonObject.stringMap("groupNames"),
            groupMultipliers = jsonObject.doubleMap("groupMultipliers")
        )
    }

    /**
     * 仅写入页面和离线展示需要的稳定字段，不缓存服务端无关原文。
     * @param usage 需要持久化的最后有效用量快照
     */
    fun encode(usage: PlanUsageSnapshot): JSONObject {
        return JSONObject().apply {
            putNullable("planName", usage.planName)
            putNullable("type", usage.type)
            putNullable("status", usage.status)
            putNullable("startAt", usage.startAt)
            putNullable("endAt", usage.endAt)
            putNullable("dailyLimitUsd", usage.dailyLimitUsd)
            putNullable("weeklyLimitUsd", usage.weeklyLimitUsd)
            putNullable("dailyUsedUsd", usage.dailyUsedUsd)
            putNullable("weeklyUsedUsd", usage.weeklyUsedUsd)
            putNullable("dailyRemainingUsd", usage.dailyRemainingUsd)
            putNullable("weeklyRemainingUsd", usage.weeklyRemainingUsd)
            putNullable("dayWindowStartAt", usage.dayWindowStartAt)
            putNullable("dayWindowEndAt", usage.dayWindowEndAt)
            putNullable("weekWindowStartAt", usage.weekWindowStartAt)
            putNullable("weekWindowEndAt", usage.weekWindowEndAt)
            putNullable("totalTokens", usage.totalTokens)
            putNullable("consumedTokens", usage.consumedTokens)
            putNullable("remainingTokens", usage.remainingTokens)
            put("allowedModels", JSONArray(usage.allowedModels))
            put("allowedGroups", JSONArray(usage.allowedGroups))
            put("groupNames", JSONObject(usage.groupNames))
            put("groupMultipliers", JSONObject(usage.groupMultipliers))
        }
    }
}

/** 写入显式 JSON null，保证缺失值在缓存往返后语义稳定。 */
internal fun JSONObject.putNullable(name: String, value: Any?) {
    put(name, value ?: JSONObject.NULL)
}

/** 只接受非空白字符串，JSON null 和空文本统一映射为空值。 */
internal fun JSONObject.stringOrNull(name: String): String? {
    return if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null
}

/** 字段存在且不是 JSON null 时读取整数。 */
internal fun JSONObject.intOrNull(name: String): Int? {
    return if (has(name) && !isNull(name)) optInt(name) else null
}

/** 使用哨兵值区分真实零值和缺失字段。 */
internal fun JSONObject.longOrNull(name: String): Long? {
    return optLong(name, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
}

/** 使用 NaN 区分真实零值和缺失字段。 */
internal fun JSONObject.doubleOrNull(name: String): Double? {
    return optDouble(name, Double.NaN).takeIf { !it.isNaN() }
}

/** 数组不存在或元素为空白时返回稳定的空列表。 */
private fun JSONObject.stringList(name: String): List<String> {
    val jsonArray = optJSONArray(name) ?: return emptyList()
    return (0 until jsonArray.length()).mapNotNull { index ->
        jsonArray.optString(index).takeIf { it.isNotBlank() }
    }
}

/** 字符串映射只保留非空键值。 */
private fun JSONObject.stringMap(name: String): Map<String, String> {
    val jsonObject = optJSONObject(name) ?: return emptyMap()
    return jsonObject.keys().asSequence().mapNotNull { key ->
        jsonObject.stringOrNull(key)?.let { key to it }
    }.toMap()
}

/** 数字映射忽略无法解析的值，避免单个倍率破坏整份快照。 */
private fun JSONObject.doubleMap(name: String): Map<String, Double> {
    val jsonObject = optJSONObject(name) ?: return emptyMap()
    return jsonObject.keys().asSequence().mapNotNull { key ->
        jsonObject.doubleOrNull(key)?.let { key to it }
    }.toMap()
}
