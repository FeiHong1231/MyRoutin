package com.hss.myroutin.store

import android.content.Context
import android.content.SharedPreferences
import com.hss.myroutin.model.PlanUsageQueryStatus
import com.hss.myroutin.model.PlanUsageSnapshot
import com.hss.myroutin.model.SavedPlanKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * 说明：区分正常空数据和无法认证或解析的本机缓存，避免页面把异常情况误展示为“没有 Key”。
 *
 * @作者 huangssh
 * @版本 2.2
 */
sealed interface PlanUsageKeyLoadResult {

    /** 本机密文认证和 JSON 解析均成功，可安全使用已读取的 Key。 */
    data class Loaded(val keys: List<SavedPlanKey>) : PlanUsageKeyLoadResult

    /** 当前设备没有保存过 Key，不需要向用户展示异常提示。 */
    object Empty : PlanUsageKeyLoadResult

    /** 密文可能来自其他设备、已损坏或当前 Keystore 已失效，禁止继续加载。 */
    object Unreadable : PlanUsageKeyLoadResult
}

/**
 * 说明：订阅 Key 列表的本机加密存储入口，负责将全部 Key 与页面缓存作为同一份密文持久化。
 *
 * @作者 huangssh
 * @版本 2.3
 */
class PlanUsageKeyStore(context: Context) {

    /**
     * SharedPreferences 仅承载密文，实际 AES 密钥由 Android Keystore 管理且不可导出。
     */
    private val preferences: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 统一加密整份 JSON，避免 Key 与缓存字段被拆分后遗漏保护。 */
    private val cipher = KeystoreAesGcmCipher()

    init {
        clearLegacyPlainTextData()
    }

    /**
     * 仅接受认证通过且完整可解析的密文；异常时返回可识别结果供页面提示用户重新添加。
     */
    fun loadKeys(): PlanUsageKeyLoadResult {
        val encryptedPayload = preferences.getString(PREF_KEY_ENCRYPTED_PLAN_KEYS, null)
        if (encryptedPayload.isNullOrBlank()) {
            return PlanUsageKeyLoadResult.Empty
        }
        return try {
            PlanUsageKeyLoadResult.Loaded(decodeKeys(cipher.decrypt(encryptedPayload)))
        } catch (_: Exception) {
            PlanUsageKeyLoadResult.Unreadable
        }
    }

    /**
     * 加密后写入整个列表，确保名称、排序、展开状态和缓存结果始终保持一致。
     * @param keys 当前全部订阅 Key
     */
    fun saveKeys(keys: List<SavedPlanKey>) {
        preferences.edit()
            .putString(PREF_KEY_ENCRYPTED_PLAN_KEYS, cipher.encrypt(encodeKeys(keys)))
            .apply()
    }

    /**
     * 当前尚未对外分发，首次启用加密存储时直接清除历史明文字段，避免残留敏感数据。
     */
    private fun clearLegacyPlainTextData() {
        preferences.edit()
            .remove(PREF_KEY_PLAN_KEYS)
            .remove(PREF_KEY_API_KEY)
            .remove(PREF_KEY_DAY_WINDOW_END_AT)
            .remove(PREF_KEY_WEEK_WINDOW_END_AT)
            .apply()
    }

    /**
     * 将页面使用的稳定字段序列化，而不是缓存完整接口原文，避免接口无关字段进入本地数据。
     */
    private fun encodeKeys(keys: List<SavedPlanKey>): String {
        return JSONArray().apply {
            keys.forEach { key ->
                put(JSONObject().apply {
                    put("id", key.id)
                    put("name", key.name)
                    put("apiKey", key.apiKey)
                    put("isExpanded", key.isExpanded)
                    put("createdAt", key.createdAt)
                    put("sortOrder", key.sortOrder)
                    putNullable("lastUpdatedAt", key.lastUpdatedAt)
                    putNullable("cachedStartAt", key.cachedStartAt)
                    putNullable("cachedEndAt", key.cachedEndAt)
                    putNullable("cachedDayWindowStartAt", key.cachedDayWindowStartAt)
                    putNullable("cachedDayWindowEndAt", key.cachedDayWindowEndAt)
                    putNullable("cachedWeekWindowStartAt", key.cachedWeekWindowStartAt)
                    putNullable("cachedWeekWindowEndAt", key.cachedWeekWindowEndAt)
                    putNullable("cachedUsage", key.cachedUsage?.toJsonObject())
                    putNullable("lastCheckedAt", key.lastCheckedAt)
                    put("queryStatus", key.queryStatus.name)
                })
            }
        }.toString()
    }

    /**
     * 旧版本缺少排序字段时，按原来的置顶规则生成顺序号并立即回写，确保升级后卡片顺序不变。
     */
    private fun decodeKeys(json: String): List<SavedPlanKey> {
        val jsonArray = JSONArray(json)
        val decodedKeys = (0 until jsonArray.length()).map { index ->
            val jsonObject = jsonArray.optJSONObject(index)
                ?: throw IllegalStateException("本地 Key 数据格式无效")
            val key = jsonObject.toSavedPlanKey()
                ?: throw IllegalStateException("本地 Key 数据缺少必要字段")
            DecodedPlanKey(
                key = key,
                legacyIsPinned = jsonObject.optBoolean("isPinned", false),
                originalIndex = index,
                requiresQueryStatusMigration = !jsonObject.has("queryStatus")
            )
        }
        val requiresSortOrderMigration = decodedKeys.any { it.key.sortOrder == MISSING_SORT_ORDER }
        val keys = if (requiresSortOrderMigration) {
            decodedKeys.sortedWith(
                compareByDescending<DecodedPlanKey> { it.legacyIsPinned }
                    .thenBy { it.key.createdAt }
                    .thenBy { it.originalIndex }
            ).mapIndexed { sortOrder, decodedKey ->
                decodedKey.key.copy(sortOrder = sortOrder)
            }
        } else {
            decodedKeys.map { it.key }
        }
        if (requiresSortOrderMigration || decodedKeys.any { it.requiresQueryStatusMigration }) {
            // 读取成功后立即用新字段回写，旧额度推断状态不会在后续启动中反复迁移。
            saveKeys(keys)
        }
        return keys
    }

    /**
     * 缺失的新字段使用兼容默认值，保证后续版本可继续读取当前缓存。
     */
    private fun JSONObject.toSavedPlanKey(): SavedPlanKey? {
        val id = stringOrNull("id") ?: return null
        val apiKey = stringOrNull("apiKey") ?: return null
        val lastUpdatedAt = longOrNull("lastUpdatedAt")
        val cachedUsage = optJSONObject("cachedUsage")?.toPlanUsageSnapshot()
        val queryStatus = resolveStoredPlanUsageQueryStatus(
            currentStatus = stringOrNull("queryStatus"),
            legacyAvailability = stringOrNull("availability"),
            hasCachedUsage = cachedUsage != null
        )
        return SavedPlanKey(
            id = id,
            name = stringOrNull("name") ?: DEFAULT_KEY_NAME,
            apiKey = apiKey,
            isExpanded = optBoolean("isExpanded", true),
            createdAt = optLong("createdAt", 0L),
            sortOrder = optInt("sortOrder", MISSING_SORT_ORDER),
            lastUpdatedAt = lastUpdatedAt,
            cachedStartAt = stringOrNull("cachedStartAt"),
            cachedEndAt = stringOrNull("cachedEndAt"),
            cachedDayWindowStartAt = stringOrNull("cachedDayWindowStartAt"),
            cachedDayWindowEndAt = stringOrNull("cachedDayWindowEndAt"),
            cachedWeekWindowStartAt = stringOrNull("cachedWeekWindowStartAt"),
            cachedWeekWindowEndAt = stringOrNull("cachedWeekWindowEndAt"),
            cachedUsage = cachedUsage,
            // 旧缓存没有检查时间时沿用原更新时间，保证升级后仍能说明数据新旧。
            lastCheckedAt = longOrNull("lastCheckedAt") ?: lastUpdatedAt,
            queryStatus = queryStatus
        )
    }

    /**
     * 将缓存快照完整映射回展示对象，卡片在离线或刷新失败时可直接恢复原来的信息。
     */
    private fun JSONObject.toPlanUsageSnapshot(): PlanUsageSnapshot {
        return PlanUsageSnapshot(
            planName = stringOrNull("planName"),
            type = intOrNull("type"),
            status = intOrNull("status"),
            startAt = stringOrNull("startAt"),
            endAt = stringOrNull("endAt"),
            dailyLimitUsd = doubleOrNull("dailyLimitUsd"),
            weeklyLimitUsd = doubleOrNull("weeklyLimitUsd"),
            dailyUsedUsd = doubleOrNull("dailyUsedUsd"),
            weeklyUsedUsd = doubleOrNull("weeklyUsedUsd"),
            dailyRemainingUsd = doubleOrNull("dailyRemainingUsd"),
            weeklyRemainingUsd = doubleOrNull("weeklyRemainingUsd"),
            dayWindowStartAt = stringOrNull("dayWindowStartAt"),
            dayWindowEndAt = stringOrNull("dayWindowEndAt"),
            weekWindowStartAt = stringOrNull("weekWindowStartAt"),
            weekWindowEndAt = stringOrNull("weekWindowEndAt"),
            totalTokens = longOrNull("totalTokens"),
            consumedTokens = longOrNull("consumedTokens"),
            remainingTokens = longOrNull("remainingTokens"),
            allowedModels = stringList("allowedModels"),
            allowedGroups = stringList("allowedGroups"),
            groupNames = stringMap("groupNames"),
            groupMultipliers = doubleMap("groupMultipliers")
        )
    }

    /**
     * 快照仅保留当前页面会展示的字段，配合读取逻辑形成稳定的本地缓存格式。
     */
    private fun PlanUsageSnapshot.toJsonObject(): JSONObject {
        return JSONObject().apply {
            putNullable("planName", planName)
            putNullable("type", type)
            putNullable("status", status)
            putNullable("startAt", startAt)
            putNullable("endAt", endAt)
            putNullable("dailyLimitUsd", dailyLimitUsd)
            putNullable("weeklyLimitUsd", weeklyLimitUsd)
            putNullable("dailyUsedUsd", dailyUsedUsd)
            putNullable("weeklyUsedUsd", weeklyUsedUsd)
            putNullable("dailyRemainingUsd", dailyRemainingUsd)
            putNullable("weeklyRemainingUsd", weeklyRemainingUsd)
            putNullable("dayWindowStartAt", dayWindowStartAt)
            putNullable("dayWindowEndAt", dayWindowEndAt)
            putNullable("weekWindowStartAt", weekWindowStartAt)
            putNullable("weekWindowEndAt", weekWindowEndAt)
            putNullable("totalTokens", totalTokens)
            putNullable("consumedTokens", consumedTokens)
            putNullable("remainingTokens", remainingTokens)
            put("allowedModels", JSONArray(allowedModels))
            put("allowedGroups", JSONArray(allowedGroups))
            put("groupNames", JSONObject(groupNames))
            put("groupMultipliers", JSONObject(groupMultipliers))
        }
    }

    private fun JSONObject.putNullable(name: String, value: Any?) {
        put(name, value ?: JSONObject.NULL)
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

    private fun JSONObject.stringList(name: String): List<String> {
        val jsonArray = optJSONArray(name) ?: return emptyList()
        return (0 until jsonArray.length()).mapNotNull { index ->
            jsonArray.optString(index).takeIf { it.isNotBlank() }
        }
    }

    private fun JSONObject.stringMap(name: String): Map<String, String> {
        val jsonObject = optJSONObject(name) ?: return emptyMap()
        return jsonObject.keys().asSequence().mapNotNull { key ->
            jsonObject.stringOrNull(key)?.let { key to it }
        }.toMap()
    }

    private fun JSONObject.doubleMap(name: String): Map<String, Double> {
        val jsonObject = optJSONObject(name) ?: return emptyMap()
        return jsonObject.keys().asSequence().mapNotNull { key ->
            jsonObject.doubleOrNull(key)?.let { key to it }
        }.toMap()
    }

    /**
     * 读取时暂存旧置顶状态，仅用于将 2.0 及更早版本的显示顺序迁移为自由排序。
     */
    private data class DecodedPlanKey(
        val key: SavedPlanKey,
        val legacyIsPinned: Boolean,
        val originalIndex: Int,
        /** 缺少新状态字段时需要在本次成功解密后立即回写。 */
        val requiresQueryStatusMigration: Boolean
    )

    private companion object {
        private const val PREFS_NAME = "plan_usage_input_cache"
        private const val PREF_KEY_ENCRYPTED_PLAN_KEYS = "encrypted_plan_keys_v1"
        private const val PREF_KEY_PLAN_KEYS = "plan_keys"
        private const val PREF_KEY_API_KEY = "api_key"
        private const val PREF_KEY_DAY_WINDOW_END_AT = "day_window_end_at"
        private const val PREF_KEY_WEEK_WINDOW_END_AT = "week_window_end_at"
        private const val DEFAULT_KEY_NAME = "默认 Key"
        private const val MISSING_SORT_ORDER = -1
    }
}

/**
 * 将当前或旧版持久化字段转换为新查询状态；旧额度推断结果统一待刷新，禁止误迁移为过期。
 * @param currentStatus 2.3 新契约写入的查询状态
 * @param legacyAvailability 旧版本写入的可用状态
 * @param hasCachedUsage 是否仍保留最后有效用量快照
 */
internal fun resolveStoredPlanUsageQueryStatus(
    currentStatus: String?,
    legacyAvailability: String?,
    hasCachedUsage: Boolean
): PlanUsageQueryStatus {
    if (currentStatus != null) {
        return PlanUsageQueryStatus.values().firstOrNull { it.name == currentStatus }
            ?: PlanUsageQueryStatus.UNKNOWN
    }
    return when (legacyAvailability) {
        "AVAILABLE" -> PlanUsageQueryStatus.ACTIVE
        "EXPIRED" -> PlanUsageQueryStatus.EXPIRED
        null -> if (hasCachedUsage) PlanUsageQueryStatus.ACTIVE else PlanUsageQueryStatus.UNKNOWN
        else -> PlanUsageQueryStatus.UNKNOWN
    }
}
