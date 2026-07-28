package com.hss.myroutin.model

/**
 * 说明：本地保存的订阅 Key 条目，承载列表排序、卡片展开状态和最近一次成功查询结果。
 *
 * @作者 huangssh
 * @版本 2.3
 */
data class SavedPlanKey(
    val id: String,
    val name: String,
    val apiKey: String,
    val isExpanded: Boolean = true,
    val createdAt: Long,
    val sortOrder: Int = 0,
    /** 最近一次拿到有效额度快照的时间，过期或 Key 失效结果不得覆盖该时间。 */
    val lastUpdatedAt: Long? = null,
    /** 最近一次非空额度快照；订阅过期或 Key 失效时保留，用于继续展示最后有效数据。 */
    val cachedUsage: PlanUsageSnapshot? = null,
    /** 仅承接旧版本已经丢失额度快照但仍保留的周期时间，新有效快照会清除此对象。 */
    val legacyPeriod: PlanUsageLegacyPeriod? = null,
    /** 最近一次得到确定业务结果的时间，包含有效快照、订阅过期和 Key 失效。 */
    val lastCheckedAt: Long? = lastUpdatedAt,
    /** 最新一次确定的查询状态，与最后有效额度快照分开保存。 */
    val queryStatus: PlanUsageQueryStatus = if (cachedUsage == null) {
        PlanUsageQueryStatus.UNKNOWN
    } else {
        PlanUsageQueryStatus.ACTIVE
    }
)

/**
 * 说明：订阅 Key 最新一次确定的查询状态；额度是否耗尽由当前快照单独计算，不再混入订阅状态。
 *
 * @作者 huangssh
 * @版本 2.3
 */
enum class PlanUsageQueryStatus {
    ACTIVE,
    EXPIRED,
    INVALID_API_KEY,
    UNKNOWN
}

/**
 * 说明：旧缓存的周期时间兼容对象，只在完整用量快照已丢失时保留迁移后仍可展示的信息。
 *
 * @作者 huangssh
 * @版本 2.3
 */
data class PlanUsageLegacyPeriod(
    val startAt: String?,
    val endAt: String?,
    val dayWindowStartAt: String?,
    val dayWindowEndAt: String?,
    val weekWindowStartAt: String?,
    val weekWindowEndAt: String?
) {
    /** 防止空兼容对象进入当前缓存格式。 */
    fun hasAnyValue(): Boolean {
        return listOf(
            startAt,
            endAt,
            dayWindowStartAt,
            dayWindowEndAt,
            weekWindowStartAt,
            weekWindowEndAt
        ).any { !it.isNullOrBlank() }
    }
}

/**
 * 说明：订阅用量页面需要长期缓存的展示数据，避免接口暂时失败时清空已有卡片内容。
 *
 * @作者 huangssh
 * @版本 2.1
 */
data class PlanUsageSnapshot(
    val planName: String?,
    val type: Int?,
    val status: Int?,
    val startAt: String?,
    val endAt: String?,
    val dailyLimitUsd: Double?,
    val weeklyLimitUsd: Double?,
    val dailyUsedUsd: Double?,
    val weeklyUsedUsd: Double?,
    val dailyRemainingUsd: Double?,
    val weeklyRemainingUsd: Double?,
    val dayWindowStartAt: String?,
    val dayWindowEndAt: String?,
    val weekWindowStartAt: String?,
    val weekWindowEndAt: String?,
    val totalTokens: Long?,
    val consumedTokens: Long?,
    val remainingTokens: Long?,
    val allowedModels: List<String>,
    val allowedGroups: List<String>,
    val groupNames: Map<String, String>,
    val groupMultipliers: Map<String, Double>
) {
    /**
     * 资源包套餐以 token 字段为主，只要任一额度字段有效就展示资源包区块。
     */
    fun hasResourceUsage(): Boolean {
        return listOf(totalTokens, consumedTokens, remainingTokens).any { value ->
            value != null && value > 0L
        }
    }

    /**
     * 周期订阅以日周美元额度为主，只要任一额度字段有效就展示周期订阅区块。
     */
    fun hasCycleUsage(): Boolean {
        return listOf(dailyLimitUsd, weeklyLimitUsd, dailyUsedUsd, weeklyUsedUsd, dailyRemainingUsd, weeklyRemainingUsd)
            .any { value -> value != null && value > 0.0 }
    }
}
