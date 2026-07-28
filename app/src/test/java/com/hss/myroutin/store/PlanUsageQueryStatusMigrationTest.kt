package com.hss.myroutin.store

import com.hss.myroutin.model.PlanUsageQueryStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 说明：验证旧可用状态迁移到新查询状态时不会把历史额度推断结果误判为订阅过期。
 *
 * @作者 huangssh
 * @版本 2.3
 */
class PlanUsageQueryStatusMigrationTest {

    @Test
    fun currentStatus_takesPriorityOverLegacyAvailability() {
        val status = resolveStoredPlanUsageQueryStatus(
            currentStatus = "INVALID_API_KEY",
            legacyAvailability = "AVAILABLE",
            hasCachedUsage = true
        )

        assertEquals(PlanUsageQueryStatus.INVALID_API_KEY, status)
    }

    @Test
    fun legacyAvailableAndExpired_preserveDefinitiveMeaning() {
        assertEquals(
            PlanUsageQueryStatus.ACTIVE,
            resolveStoredPlanUsageQueryStatus(null, "AVAILABLE", hasCachedUsage = true)
        )
        assertEquals(
            PlanUsageQueryStatus.EXPIRED,
            resolveStoredPlanUsageQueryStatus(null, "EXPIRED", hasCachedUsage = true)
        )
    }

    @Test
    fun legacyQuotaStatuses_migrateToUnknown() {
        val legacyQuotaStatuses = listOf(
            "DAILY_QUOTA_EXHAUSTED",
            "WEEKLY_QUOTA_EXHAUSTED",
            "DAILY_AND_WEEKLY_QUOTA_EXHAUSTED",
            "QUOTA_EXHAUSTED",
            "UNAVAILABLE"
        )

        legacyQuotaStatuses.forEach { legacyStatus ->
            assertEquals(
                PlanUsageQueryStatus.UNKNOWN,
                resolveStoredPlanUsageQueryStatus(null, legacyStatus, hasCachedUsage = true)
            )
        }
    }

    @Test
    fun cacheWithoutStatus_usesSnapshotOnlyForPreStatusData() {
        assertEquals(
            PlanUsageQueryStatus.ACTIVE,
            resolveStoredPlanUsageQueryStatus(null, null, hasCachedUsage = true)
        )
        assertEquals(
            PlanUsageQueryStatus.UNKNOWN,
            resolveStoredPlanUsageQueryStatus(null, null, hasCachedUsage = false)
        )
    }
}
