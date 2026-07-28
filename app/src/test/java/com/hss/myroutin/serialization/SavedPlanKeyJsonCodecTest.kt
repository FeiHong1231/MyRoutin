package com.hss.myroutin.serialization

import com.hss.myroutin.model.PlanUsageQueryStatus
import com.hss.myroutin.model.PlanUsageSnapshot
import com.hss.myroutin.model.SavedPlanKey
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 说明：验证当前缓存往返和旧版分散时间、状态、置顶顺序迁移到收敛 JSON 格式。
 *
 * @作者 huangssh
 * @版本 2.3
 */
class SavedPlanKeyJsonCodecTest {

    @Test
    fun legacyFlatPeriod_migratesToSingleFallbackObject() {
        val decoded = SavedPlanKeyJsonCodec.decode(
            """
            [{
                "id":"legacy-id",
                "name":"Legacy Key",
                "apiKey":"plan-legacy-key",
                "createdAt":100,
                "sortOrder":0,
                "lastUpdatedAt":200,
                "cachedStartAt":"2026-01-01T00:00:00Z",
                "cachedEndAt":"2026-02-01T00:00:00Z",
                "cachedDayWindowStartAt":"2026-01-02T00:00:00Z",
                "cachedDayWindowEndAt":"2026-01-02T05:00:00Z",
                "cachedWeekWindowStartAt":"2026-01-01T00:00:00Z",
                "cachedWeekWindowEndAt":"2026-01-08T00:00:00Z",
                "cachedUsage":null,
                "lastCheckedAt":300,
                "availability":"DAILY_QUOTA_EXHAUSTED"
            }]
            """.trimIndent()
        )

        assertTrue(decoded.requiresRewrite)
        val key = decoded.keys.single()
        assertNull(key.cachedUsage)
        assertEquals(PlanUsageQueryStatus.UNKNOWN, key.queryStatus)
        assertEquals("2026-01-01T00:00:00Z", key.legacyPeriod?.startAt)
        assertEquals("2026-01-02T05:00:00Z", key.legacyPeriod?.dayWindowEndAt)

        val migratedJson = SavedPlanKeyJsonCodec.encode(decoded.keys)
        val migratedKeyJson = JSONArray(migratedJson).getJSONObject(0)
        assertFalse(migratedKeyJson.has("cachedStartAt"))
        assertFalse(migratedKeyJson.has("cachedEndAt"))
        assertEquals("UNKNOWN", migratedKeyJson.getString("queryStatus"))
        assertEquals(
            "2026-01-08T00:00:00Z",
            migratedKeyJson.getJSONObject("legacyPeriod").getString("weekWindowEndAt")
        )
        assertFalse(SavedPlanKeyJsonCodec.decode(migratedJson).requiresRewrite)
    }

    @Test
    fun currentSnapshot_roundTripsWithoutDuplicatePeriodFields() {
        val original = SavedPlanKey(
            id = "current-id",
            name = "Current Key",
            apiKey = "plan-current-key",
            createdAt = 100L,
            sortOrder = 2,
            lastUpdatedAt = 200L,
            cachedUsage = createUsage(),
            lastCheckedAt = 300L,
            queryStatus = PlanUsageQueryStatus.ACTIVE
        )

        val encoded = SavedPlanKeyJsonCodec.encode(listOf(original))
        val decoded = SavedPlanKeyJsonCodec.decode(encoded)

        assertFalse(decoded.requiresRewrite)
        assertEquals(original, decoded.keys.single())
        val keyJson = JSONArray(encoded).getJSONObject(0)
        assertFalse(keyJson.has("cachedStartAt"))
        assertFalse(keyJson.has("cachedWeekWindowEndAt"))
        assertTrue(keyJson.isNull("legacyPeriod"))
    }

    @Test
    fun legacySnapshot_winsOverDuplicateFlatPeriod() {
        val decoded = SavedPlanKeyJsonCodec.decode(
            """
            [{
                "id":"snapshot-id",
                "apiKey":"plan-snapshot-key",
                "createdAt":100,
                "sortOrder":0,
                "cachedStartAt":"1999-01-01T00:00:00Z",
                "cachedUsage":{
                    "planName":"成长版",
                    "startAt":"2026-01-01T00:00:00Z",
                    "dailyLimitUsd":60,
                    "dailyUsedUsd":10,
                    "allowedModels":[],
                    "allowedGroups":[],
                    "groupNames":{},
                    "groupMultipliers":{}
                },
                "availability":"AVAILABLE"
            }]
            """.trimIndent()
        )

        val key = decoded.keys.single()
        assertTrue(decoded.requiresRewrite)
        assertEquals("2026-01-01T00:00:00Z", key.cachedUsage?.startAt)
        assertNull(key.legacyPeriod)
        assertEquals(PlanUsageQueryStatus.ACTIVE, key.queryStatus)
    }

    @Test
    fun missingSortOrder_preservesLegacyPinnedOrderAndRewrites() {
        val decoded = SavedPlanKeyJsonCodec.decode(
            """
            [
                {"id":"normal","apiKey":"key-normal","createdAt":100,"isPinned":false},
                {"id":"pinned","apiKey":"key-pinned","createdAt":200,"isPinned":true}
            ]
            """.trimIndent()
        )

        assertTrue(decoded.requiresRewrite)
        assertEquals(listOf("pinned", "normal"), decoded.keys.map(SavedPlanKey::id))
        assertEquals(listOf(0, 1), decoded.keys.map(SavedPlanKey::sortOrder))
    }

    @Test
    fun legacyAndUnknownStatusValues_migrateConservatively() {
        assertEquals(
            PlanUsageQueryStatus.INVALID_API_KEY,
            resolveStoredPlanUsageQueryStatus(
                currentStatus = "INVALID_API_KEY",
                legacyAvailability = "AVAILABLE",
                hasCachedUsage = true
            )
        )
        assertEquals(
            PlanUsageQueryStatus.ACTIVE,
            resolveStoredPlanUsageQueryStatus(null, "AVAILABLE", hasCachedUsage = true)
        )
        assertEquals(
            PlanUsageQueryStatus.UNKNOWN,
            resolveStoredPlanUsageQueryStatus(null, "AVAILABLE", hasCachedUsage = false)
        )
        assertEquals(
            PlanUsageQueryStatus.EXPIRED,
            resolveStoredPlanUsageQueryStatus(null, "EXPIRED", hasCachedUsage = false)
        )
        listOf(
            "DAILY_QUOTA_EXHAUSTED",
            "WEEKLY_QUOTA_EXHAUSTED",
            "DAILY_AND_WEEKLY_QUOTA_EXHAUSTED",
            "QUOTA_EXHAUSTED",
            "UNAVAILABLE"
        ).forEach { legacyStatus ->
            assertEquals(
                PlanUsageQueryStatus.UNKNOWN,
                resolveStoredPlanUsageQueryStatus(null, legacyStatus, hasCachedUsage = true)
            )
        }
    }

    /** 构造覆盖额度、窗口、列表和映射字段的完整快照，验证共享 Codec 不遗漏缓存字段。 */
    private fun createUsage(): PlanUsageSnapshot {
        return PlanUsageSnapshot(
            planName = "成长版",
            type = 1,
            status = 1,
            startAt = "2026-01-01T00:00:00Z",
            endAt = "2026-02-01T00:00:00Z",
            dailyLimitUsd = 60.0,
            weeklyLimitUsd = 410.0,
            dailyUsedUsd = 10.5,
            weeklyUsedUsd = 20.5,
            dailyRemainingUsd = 49.5,
            weeklyRemainingUsd = 389.5,
            dayWindowStartAt = "2026-01-02T00:00:00Z",
            dayWindowEndAt = "2026-01-02T05:00:00Z",
            weekWindowStartAt = "2026-01-01T00:00:00Z",
            weekWindowEndAt = "2026-01-08T00:00:00Z",
            totalTokens = 1_000L,
            consumedTokens = 200L,
            remainingTokens = 800L,
            allowedModels = listOf("gpt-test"),
            allowedGroups = listOf("group-id"),
            groupNames = mapOf("group-id" to "Codex"),
            groupMultipliers = mapOf("group-id" to 1.0)
        )
    }
}
