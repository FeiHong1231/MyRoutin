package com.hss.myroutin.logic

import com.hss.myroutin.model.PlanUsageQueryStatus
import com.hss.myroutin.model.PlanUsageSnapshot
import com.hss.myroutin.model.SavedPlanKey

/**
 * 说明：定义确定查询结果的缓存更新规则，确保过期或 Key 失效不会清空最后有效额度。
 *
 * @作者 huangssh
 * @版本 2.3
 */
internal object PlanUsageCachePolicy {

    /**
     * 应用有效订阅快照，同时恢复 ACTIVE 状态并刷新最后有效数据时间。
     * @param planKey 查询前的完整 Key 缓存
     * @param usage 本次接口返回的非空用量快照
     * @param checkedAt 本次得到确定结果的时间
     */
    fun applyAvailableUsage(
        planKey: SavedPlanKey,
        usage: PlanUsageSnapshot,
        checkedAt: Long
    ): SavedPlanKey {
        return planKey.copy(
            lastUpdatedAt = checkedAt,
            cachedUsage = usage,
            legacyPeriod = null,
            weeklyResetStats = WeeklyResetQuotaCalculator.update(
                previousUsage = planKey.cachedUsage.takeIf {
                    planKey.queryStatus == PlanUsageQueryStatus.ACTIVE
                },
                previousStats = planKey.weeklyResetStats,
                currentUsage = usage,
                observedAt = checkedAt
            ),
            lastCheckedAt = checkedAt,
            queryStatus = PlanUsageQueryStatus.ACTIVE
        )
    }

    /**
     * 应用订阅过期结果，仅更新确定状态和检查时间，保留最后有效快照供用户追溯。
     * @param planKey 查询前的完整 Key 缓存
     * @param checkedAt 本次确认订阅过期的时间
     */
    fun applyExpired(planKey: SavedPlanKey, checkedAt: Long): SavedPlanKey {
        return planKey.copy(
            lastCheckedAt = checkedAt,
            queryStatus = PlanUsageQueryStatus.EXPIRED
        )
    }

    /**
     * 应用 Key 失效结果；401 是确定的鉴权结论，因此记录检查时间但不覆盖历史额度。
     * @param planKey 查询前的完整 Key 缓存
     * @param checkedAt 本次确认 Key 失效的时间
     */
    fun applyInvalidApiKey(planKey: SavedPlanKey, checkedAt: Long): SavedPlanKey {
        return planKey.copy(
            lastCheckedAt = checkedAt,
            queryStatus = PlanUsageQueryStatus.INVALID_API_KEY
        )
    }
}
