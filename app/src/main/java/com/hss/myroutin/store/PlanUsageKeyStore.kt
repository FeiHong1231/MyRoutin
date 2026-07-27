package com.hss.myroutin.store

import android.content.Context
import android.content.SharedPreferences
import com.hss.myroutin.model.PlanUsageSnapshot
import com.hss.myroutin.model.SavedPlanKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * 说明：订阅 Key 列表的本机加密存储入口，负责将全部 Key 与页面缓存作为同一份密文持久化。
 *
 * @作者 huangssh
 * @版本 2.1
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
     * 仅接受认证通过的密文；密文格式或完整性异常时不返回任何本地 Key。
     */
    fun loadKeys(): List<SavedPlanKey> {
        val encryptedPayload = preferences.getString(PREF_KEY_ENCRYPTED_PLAN_KEYS, null)
        if (encryptedPayload.isNullOrBlank()) {
            return emptyList()
        }
        return runCatching { decodeKeys(cipher.decrypt(encryptedPayload)) }.getOrDefault(emptyList())
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
                })
            }
        }.toString()
    }

    /**
     * 旧版本缺少排序字段时，按原来的置顶规则生成顺序号并立即回写，确保升级后卡片顺序不变。
     */
    private fun decodeKeys(json: String): List<SavedPlanKey> {
        return runCatching {
            val jsonArray = JSONArray(json)
            val decodedKeys = (0 until jsonArray.length()).mapNotNull { index ->
                val jsonObject = jsonArray.optJSONObject(index) ?: return@mapNotNull null
                jsonObject.toSavedPlanKey()?.let { key ->
                    DecodedPlanKey(
                        key = key,
                        legacyIsPinned = jsonObject.optBoolean("isPinned", false),
                        originalIndex = index
                    )
                }
            }
            if (decodedKeys.any { it.key.sortOrder == MISSING_SORT_ORDER }) {
                val migratedKeys = decodedKeys.sortedWith(
                    compareByDescending<DecodedPlanKey> { it.legacyIsPinned }
                        .thenBy { it.key.createdAt }
                        .thenBy { it.originalIndex }
                ).mapIndexed { sortOrder, decodedKey ->
                    decodedKey.key.copy(sortOrder = sortOrder)
                }
                saveKeys(migratedKeys)
                migratedKeys
            } else {
                decodedKeys.map { it.key }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 缺失的新字段使用兼容默认值，保证后续版本可继续读取当前缓存。
     */
    private fun JSONObject.toSavedPlanKey(): SavedPlanKey? {
        val id = stringOrNull("id") ?: return null
        val apiKey = stringOrNull("apiKey") ?: return null
        return SavedPlanKey(
            id = id,
            name = stringOrNull("name") ?: DEFAULT_KEY_NAME,
            apiKey = apiKey,
            isExpanded = optBoolean("isExpanded", true),
            createdAt = optLong("createdAt", 0L),
            sortOrder = optInt("sortOrder", MISSING_SORT_ORDER),
            lastUpdatedAt = longOrNull("lastUpdatedAt"),
            cachedStartAt = stringOrNull("cachedStartAt"),
            cachedEndAt = stringOrNull("cachedEndAt"),
            cachedDayWindowStartAt = stringOrNull("cachedDayWindowStartAt"),
            cachedDayWindowEndAt = stringOrNull("cachedDayWindowEndAt"),
            cachedWeekWindowStartAt = stringOrNull("cachedWeekWindowStartAt"),
            cachedWeekWindowEndAt = stringOrNull("cachedWeekWindowEndAt"),
            cachedUsage = optJSONObject("cachedUsage")?.toPlanUsageSnapshot()
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
        val originalIndex: Int
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
