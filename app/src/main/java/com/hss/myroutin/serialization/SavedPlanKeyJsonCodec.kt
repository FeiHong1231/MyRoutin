package com.hss.myroutin.serialization

import com.hss.myroutin.model.PlanUsageLegacyPeriod
import com.hss.myroutin.model.PlanUsageQueryStatus
import com.hss.myroutin.model.PlanUsageSnapshot
import com.hss.myroutin.model.SavedPlanKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * 说明：负责保存 Key 列表的纯 JSON 编解码及旧字段迁移，不接触 SharedPreferences、Keystore 或页面状态。
 *
 * @作者 huangssh
 * @版本 2.3
 */
internal object SavedPlanKeyJsonCodec {

    /** 将当前模型编码为收敛后的缓存格式，不再写入六个旧版独立时间字段。 */
    fun encode(keys: List<SavedPlanKey>): String {
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
                    putNullable("cachedUsage", key.cachedUsage?.let(PlanUsageSnapshotJsonCodec::encode))
                    putNullable("legacyPeriod", key.legacyPeriod?.toJsonObject())
                    putNullable("lastCheckedAt", key.lastCheckedAt)
                    put("queryStatus", key.queryStatus.name)
                })
            }
        }.toString()
    }

    /**
     * 解码当前或旧版 Key 数组，并标记是否需要由 Store 立即回写新格式。
     * @param json 解密后的本地 Key 数组
     */
    fun decode(json: String): DecodedSavedPlanKeys {
        val jsonArray = JSONArray(json)
        val decodedKeys = (0 until jsonArray.length()).map { index ->
            val jsonObject = jsonArray.optJSONObject(index)
                ?: throw IllegalStateException("本地 Key 数据格式无效")
            val cachedUsage = jsonObject.optJSONObject("cachedUsage")
                ?.let(PlanUsageSnapshotJsonCodec::decode)
            val key = jsonObject.toSavedPlanKey(cachedUsage)
                ?: throw IllegalStateException("本地 Key 数据缺少必要字段")
            DecodedPlanKey(
                key = key,
                legacyIsPinned = jsonObject.optBoolean("isPinned", false),
                originalIndex = index,
                requiresRewrite = jsonObject.requiresQueryStatusRewrite() ||
                    jsonObject.hasLegacyPeriodFields()
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
        return DecodedSavedPlanKeys(
            keys = keys,
            requiresRewrite = requiresSortOrderMigration || decodedKeys.any { it.requiresRewrite }
        )
    }

    /** 当前快照优先；仅在快照缺失时读取收敛后的 fallback 或六个旧版时间字段。 */
    private fun JSONObject.toSavedPlanKey(cachedUsage: PlanUsageSnapshot?): SavedPlanKey? {
        val id = stringOrNull("id") ?: return null
        val apiKey = stringOrNull("apiKey") ?: return null
        val lastUpdatedAt = longOrNull("lastUpdatedAt")
        val legacyPeriod = if (cachedUsage == null) {
            optJSONObject("legacyPeriod")?.toLegacyPeriod()
                ?: readLegacyPeriodFields()
        } else {
            null
        }
        return SavedPlanKey(
            id = id,
            name = stringOrNull("name") ?: DEFAULT_KEY_NAME,
            apiKey = apiKey,
            isExpanded = optBoolean("isExpanded", true),
            createdAt = optLong("createdAt", 0L),
            sortOrder = optInt("sortOrder", MISSING_SORT_ORDER),
            lastUpdatedAt = lastUpdatedAt,
            cachedUsage = cachedUsage,
            legacyPeriod = legacyPeriod,
            // 旧缓存没有检查时间时沿用原更新时间，保证升级后仍能说明数据新旧。
            lastCheckedAt = longOrNull("lastCheckedAt") ?: lastUpdatedAt,
            queryStatus = resolveStoredPlanUsageQueryStatus(
                currentStatus = stringOrNull("queryStatus"),
                legacyAvailability = stringOrNull("availability"),
                hasCachedUsage = cachedUsage != null
            )
        )
    }

    /** 六个旧字段只在迁移入口读取，空对象不会进入当前模型。 */
    private fun JSONObject.readLegacyPeriodFields(): PlanUsageLegacyPeriod? {
        return PlanUsageLegacyPeriod(
            startAt = stringOrNull("cachedStartAt"),
            endAt = stringOrNull("cachedEndAt"),
            dayWindowStartAt = stringOrNull("cachedDayWindowStartAt"),
            dayWindowEndAt = stringOrNull("cachedDayWindowEndAt"),
            weekWindowStartAt = stringOrNull("cachedWeekWindowStartAt"),
            weekWindowEndAt = stringOrNull("cachedWeekWindowEndAt")
        ).takeIf(PlanUsageLegacyPeriod::hasAnyValue)
    }

    /** 读取已经收敛为单对象的旧周期时间。 */
    private fun JSONObject.toLegacyPeriod(): PlanUsageLegacyPeriod? {
        return PlanUsageLegacyPeriod(
            startAt = stringOrNull("startAt"),
            endAt = stringOrNull("endAt"),
            dayWindowStartAt = stringOrNull("dayWindowStartAt"),
            dayWindowEndAt = stringOrNull("dayWindowEndAt"),
            weekWindowStartAt = stringOrNull("weekWindowStartAt"),
            weekWindowEndAt = stringOrNull("weekWindowEndAt")
        ).takeIf(PlanUsageLegacyPeriod::hasAnyValue)
    }

    /** 旧周期时间只为无法恢复完整快照的数据保留一个结构化兼容对象。 */
    private fun PlanUsageLegacyPeriod.toJsonObject(): JSONObject {
        return JSONObject().apply {
            putNullable("startAt", startAt)
            putNullable("endAt", endAt)
            putNullable("dayWindowStartAt", dayWindowStartAt)
            putNullable("dayWindowEndAt", dayWindowEndAt)
            putNullable("weekWindowStartAt", weekWindowStartAt)
            putNullable("weekWindowEndAt", weekWindowEndAt)
        }
    }

    /** 任一旧字段存在都需要回写，防止后续版本继续依赖分散字段。 */
    private fun JSONObject.hasLegacyPeriodFields(): Boolean {
        return LEGACY_PERIOD_FIELD_NAMES.any(::has)
    }

    /** 缺失、为空或未知的新状态都回写为 UNKNOWN，避免损坏值永久留在缓存中。 */
    private fun JSONObject.requiresQueryStatusRewrite(): Boolean {
        val savedStatus = stringOrNull("queryStatus") ?: return true
        return PlanUsageQueryStatus.values().none { it.name == savedStatus }
    }

    /** 解码中间态只承载排序迁移所需信息，不进入业务层。 */
    private data class DecodedPlanKey(
        val key: SavedPlanKey,
        val legacyIsPinned: Boolean,
        val originalIndex: Int,
        val requiresRewrite: Boolean
    )

    private const val DEFAULT_KEY_NAME = "默认 Key"
    private const val MISSING_SORT_ORDER = -1
    /** 这些字段仅用于识别 2.3 之前的分散周期缓存。 */
    private val LEGACY_PERIOD_FIELD_NAMES = listOf(
        "cachedStartAt",
        "cachedEndAt",
        "cachedDayWindowStartAt",
        "cachedDayWindowEndAt",
        "cachedWeekWindowStartAt",
        "cachedWeekWindowEndAt"
    )
}

/**
 * 说明：纯解码结果同时返回业务 Key 和是否需要升级落盘，Store 据此决定是否重新加密保存。
 *
 * @作者 huangssh
 * @版本 2.3
 */
internal data class DecodedSavedPlanKeys(
    val keys: List<SavedPlanKey>,
    val requiresRewrite: Boolean
)

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
        "AVAILABLE" -> if (hasCachedUsage) PlanUsageQueryStatus.ACTIVE else PlanUsageQueryStatus.UNKNOWN
        "EXPIRED" -> PlanUsageQueryStatus.EXPIRED
        null -> if (hasCachedUsage) PlanUsageQueryStatus.ACTIVE else PlanUsageQueryStatus.UNKNOWN
        else -> PlanUsageQueryStatus.UNKNOWN
    }
}
