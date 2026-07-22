package com.hss.mycodex.model

/**
 * 说明：本地保存的订阅 Key 条目，承载列表排序、卡片展开状态和最近一次成功查询结果。
 *
 * @作者 huangssh
 * @版本 2.1
 */
data class SavedPlanKey(
    val id: String,
    val name: String,
    val apiKey: String,
    val isExpanded: Boolean = true,
    val createdAt: Long,
    val sortOrder: Int = 0,
    val lastUpdatedAt: Long? = null,
    val cachedStartAt: String? = null,
    val cachedEndAt: String? = null,
    val cachedDayWindowStartAt: String? = null,
    val cachedDayWindowEndAt: String? = null,
    val cachedWeekWindowStartAt: String? = null,
    val cachedWeekWindowEndAt: String? = null,
    val cachedUsage: PlanUsageSnapshot? = null
)

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
