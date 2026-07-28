package com.hss.myroutin.logic

import com.hss.myroutin.model.PlanUsageLegacyPeriod
import com.hss.myroutin.model.PlanUsageQueryStatus
import com.hss.myroutin.model.PlanUsageSnapshot
import com.hss.myroutin.model.SavedPlanKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 说明：验证确定查询结果只更新对应状态，订阅过期或 Key 失效时保留最后有效快照。
 *
 * @作者 huangssh
 * @版本 2.3
 */
class PlanUsageCachePolicyTest {

    @Test
    fun expiredResult_preservesLastAvailableSnapshot() {
        val usage = createUsage(dailyUsedUsd = 97.5, weeklyUsedUsd = 22.6)
        val original = createPlanKey(usage)

        val updated = PlanUsageCachePolicy.applyExpired(original, CHECKED_AT)

        assertSame(usage, updated.cachedUsage)
        assertEquals(original.lastUpdatedAt, updated.lastUpdatedAt)
        assertEquals(CHECKED_AT, updated.lastCheckedAt ?: -1L)
        assertEquals(PlanUsageQueryStatus.EXPIRED, updated.queryStatus)
    }

    @Test
    fun invalidApiKeyResult_preservesLastAvailableSnapshot() {
        val usage = createUsage()
        val original = createPlanKey(usage)

        val updated = PlanUsageCachePolicy.applyInvalidApiKey(original, CHECKED_AT)

        assertSame(usage, updated.cachedUsage)
        assertEquals(original.lastUpdatedAt, updated.lastUpdatedAt)
        assertEquals(CHECKED_AT, updated.lastCheckedAt ?: -1L)
        assertEquals(PlanUsageQueryStatus.INVALID_API_KEY, updated.queryStatus)
    }

    @Test
    fun expiredResult_withoutSnapshotDoesNotCreateUsageData() {
        val original = SavedPlanKey(
            id = "key-id",
            name = "Test Key",
            apiKey = "plan-test-key",
            createdAt = LAST_UPDATED_AT
        )

        val updated = PlanUsageCachePolicy.applyExpired(original, CHECKED_AT)

        assertNull(updated.cachedUsage)
        assertNull(updated.lastUpdatedAt)
        assertEquals(CHECKED_AT, updated.lastCheckedAt ?: -1L)
        assertEquals(PlanUsageQueryStatus.EXPIRED, updated.queryStatus)
    }

    @Test
    fun expiredResult_preservesMigratedLegacyPeriod() {
        val legacyPeriod = PlanUsageLegacyPeriod(
            startAt = "2020-01-01T00:00:00Z",
            endAt = "2020-02-01T00:00:00Z",
            dayWindowStartAt = null,
            dayWindowEndAt = null,
            weekWindowStartAt = null,
            weekWindowEndAt = null
        )
        val original = SavedPlanKey(
            id = "key-id",
            name = "Test Key",
            apiKey = "plan-test-key",
            createdAt = LAST_UPDATED_AT,
            lastUpdatedAt = LAST_UPDATED_AT,
            legacyPeriod = legacyPeriod
        )

        val updated = PlanUsageCachePolicy.applyExpired(original, CHECKED_AT)

        assertSame(legacyPeriod, updated.legacyPeriod)
        assertEquals(PlanUsageQueryStatus.EXPIRED, updated.queryStatus)
    }

    @Test
    fun availableResult_replacesSnapshotAndRestoresActiveStatus() {
        val original = createPlanKey(createUsage()).copy(
            legacyPeriod = PlanUsageLegacyPeriod(
                startAt = "legacy",
                endAt = null,
                dayWindowStartAt = null,
                dayWindowEndAt = null,
                weekWindowStartAt = null,
                weekWindowEndAt = null
            ),
            queryStatus = PlanUsageQueryStatus.EXPIRED
        )
        val latestUsage = createUsage(dailyUsedUsd = 10.0, weeklyUsedUsd = 30.0)

        val updated = PlanUsageCachePolicy.applyAvailableUsage(
            original,
            latestUsage,
            CHECKED_AT
        )

        assertSame(latestUsage, updated.cachedUsage)
        assertNull(updated.legacyPeriod)
        assertEquals(CHECKED_AT, updated.lastUpdatedAt ?: -1L)
        assertEquals(CHECKED_AT, updated.lastCheckedAt ?: -1L)
        assertEquals(PlanUsageQueryStatus.ACTIVE, updated.queryStatus)
    }

    /** 构造包含最后有效快照的 Key，测试只关注缓存状态转换。 */
    private fun createPlanKey(usage: PlanUsageSnapshot): SavedPlanKey {
        return SavedPlanKey(
            id = "key-id",
            name = "Test Key",
            apiKey = "plan-test-key",
            createdAt = LAST_UPDATED_AT,
            lastUpdatedAt = LAST_UPDATED_AT,
            cachedUsage = usage,
            lastCheckedAt = LAST_UPDATED_AT,
            queryStatus = PlanUsageQueryStatus.ACTIVE
        )
    }

    /** 构造有效订阅快照，额度比例只作为历史展示数据，不参与订阅状态判断。 */
    private fun createUsage(
        dailyUsedUsd: Double = 0.0,
        weeklyUsedUsd: Double = 0.0
    ): PlanUsageSnapshot {
        return PlanUsageSnapshot(
            planName = "Test Plan",
            type = 1,
            status = 1,
            startAt = "2020-01-01T00:00:00Z",
            endAt = "2099-01-01T00:00:00Z",
            dailyLimitUsd = 100.0,
            weeklyLimitUsd = 100.0,
            dailyUsedUsd = dailyUsedUsd,
            weeklyUsedUsd = weeklyUsedUsd,
            dailyRemainingUsd = 100.0 - dailyUsedUsd,
            weeklyRemainingUsd = 100.0 - weeklyUsedUsd,
            dayWindowStartAt = null,
            dayWindowEndAt = null,
            weekWindowStartAt = null,
            weekWindowEndAt = null,
            totalTokens = null,
            consumedTokens = null,
            remainingTokens = null,
            allowedModels = emptyList(),
            allowedGroups = emptyList(),
            groupNames = emptyMap(),
            groupMultipliers = emptyMap()
        )
    }

    private companion object {
        private const val LAST_UPDATED_AT = 1_600_000_000_000L
        private const val CHECKED_AT = 1_700_000_000_000L
    }
}
