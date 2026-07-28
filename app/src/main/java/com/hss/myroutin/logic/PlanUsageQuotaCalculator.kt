package com.hss.myroutin.logic

import com.hss.myroutin.model.PlanUsageSnapshot

/**
 * 说明：集中计算订阅周期额度、资源包 token 比例和分组倍率趋势，不依赖 Android View 或资源。
 *
 * @作者 huangssh
 * @版本 2.3
 */
internal object PlanUsageQuotaCalculator {

    /**
     * 汇总周期额度的展示已用值、已用比例和预警状态。
     * @param usedUsd 服务端已用金额
     * @param limitUsd 服务端总额度
     * @param remainingUsd 服务端剩余额度
     */
    fun calculateCycleQuota(
        usedUsd: Double?,
        limitUsd: Double?,
        remainingUsd: Double?
    ): CycleQuotaResult {
        val displayUsedUsd = resolveDisplayUsedUsd(usedUsd, limitUsd, remainingUsd)
        val usedRate = calculateUsedRate(displayUsedUsd, limitUsd)
        val isExhausted = limitUsd != null && limitUsd > 0.0 &&
            remainingUsd != null && remainingUsd <= 0.0
        return CycleQuotaResult(
            displayUsedUsd = displayUsedUsd,
            usedRate = usedRate,
            isWarning = isExhausted || isOverWarningThreshold(usedRate)
        )
    }

    /**
     * 从资源包快照中汇总 token 总量、已用比例和预警状态，避免调用方拆传同一 Bean 的字段。
     * @param usage 当前 Key 返回的完整用量快照
     */
    fun calculateTokenQuota(usage: PlanUsageSnapshot): TokenQuotaResult {
        val declaredTotalTokens = usage.totalTokens
        val consumedTokens = usage.consumedTokens
        val remainingTokens = usage.remainingTokens
        val totalTokens = when {
            declaredTotalTokens != null && declaredTotalTokens > 0L -> declaredTotalTokens
            consumedTokens != null && remainingTokens != null -> consumedTokens + remainingTokens
            else -> declaredTotalTokens
        }
        val usedRate = if (consumedTokens == null || totalTokens == null || totalTokens <= 0L) {
            null
        } else {
            (consumedTokens.toDouble() / totalTokens.toDouble()).coerceIn(0.0, 1.0)
        }
        return TokenQuotaResult(
            totalTokens = totalTokens,
            usedRate = usedRate,
            isWarning = isOverWarningThreshold(usedRate)
        )
    }

    /**
     * 比较接口倍率与套餐默认倍率，仅返回展示层需要的高低趋势。
     * @param groupId 服务端返回的分组 ID
     * @param groupName 服务端返回的分组名称
     * @param multiplier 当前 Key 对应的分组倍率
     */
    fun resolveGroupMultiplierTrend(
        groupId: String,
        groupName: String,
        multiplier: Double?
    ): GroupMultiplierTrend? {
        val defaultMultiplier = when {
            groupId == GROUP_ID_CODEX_PRO || groupName == GROUP_NAME_CODEX_PRO ->
                DEFAULT_CODEX_PRO_GROUP_MULTIPLIER
            groupId == GROUP_ID_CODEX || groupName == GROUP_NAME_CODEX ->
                DEFAULT_CODEX_GROUP_MULTIPLIER
            else -> return null
        }
        return when {
            multiplier == null || multiplier == defaultMultiplier -> null
            multiplier < defaultMultiplier -> GroupMultiplierTrend.LOWER
            else -> GroupMultiplierTrend.HIGHER
        }
    }

    /** 周期接口只给剩余额度时，用总额度减剩余额度补出展示已用值。 */
    private fun resolveDisplayUsedUsd(
        usedUsd: Double?,
        limitUsd: Double?,
        remainingUsd: Double?
    ): Double? {
        if (usedUsd != null) {
            return usedUsd
        }
        if (limitUsd != null && remainingUsd != null && limitUsd > 0.0) {
            return (limitUsd - remainingUsd).coerceIn(0.0, limitUsd)
        }
        return usedUsd
    }

    /** 非正总额度没有有效比例，有效比例统一限制在 0 到 1。 */
    private fun calculateUsedRate(usedUsd: Double?, limitUsd: Double?): Double? {
        if (usedUsd == null || limitUsd == null || limitUsd <= 0.0) {
            return null
        }
        return (usedUsd / limitUsd).coerceIn(0.0, 1.0)
    }

    /** 已用比例严格超过 80% 时进入预警，保持原有边界语义。 */
    private fun isOverWarningThreshold(usedRate: Double?): Boolean {
        return usedRate != null && usedRate > PROGRESS_WARNING_THRESHOLD
    }

    /** 周期额度绑定所需的纯计算结果，剩余额度仍使用服务端原始值展示。 */
    data class CycleQuotaResult(
        val displayUsedUsd: Double?,
        val usedRate: Double?,
        val isWarning: Boolean
    )

    /** 资源包绑定所需的纯计算结果，总量可由已用和剩余 token 补齐。 */
    data class TokenQuotaResult(
        val totalTokens: Long?,
        val usedRate: Double?,
        val isWarning: Boolean
    )

    /** 分组倍率相对默认套餐倍率的方向，由 Adapter 映射为对应语义色。 */
    enum class GroupMultiplierTrend {
        LOWER,
        HIGHER
    }

    private const val PROGRESS_WARNING_THRESHOLD = 0.8
    private const val GROUP_ID_CODEX_PRO = "ffa027fc-8402-4b99-8db2-66eefc87325f"
    private const val GROUP_ID_CODEX = "ffa2f93c-6a1f-4bbd-a968-632ae3654465"
    private const val GROUP_NAME_CODEX_PRO = "Codex Pro"
    private const val GROUP_NAME_CODEX = "Codex"
    private const val DEFAULT_CODEX_PRO_GROUP_MULTIPLIER = 2.0
    private const val DEFAULT_CODEX_GROUP_MULTIPLIER = 1.0
}
