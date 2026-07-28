package com.hss.myroutin.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 说明：验证接口状态码和空正文按照新契约映射，避免把服务异常误报为订阅过期。
 *
 * @作者 huangssh
 * @版本 2.3
 */
class PlanUsageRepositoryResponseTest {

    private val repository = PlanUsageRepository()

    @Test
    fun availableBody_mapsThroughSharedSnapshotCodec() {
        val result = repository.mapHttpResponse(
            200,
            """
            {
                "planName":"成长版",
                "dailyLimitUsd":60,
                "dailyUsedUsd":60.44455,
                "dailyRemainingUsd":-0.44455,
                "allowedModels":["gpt-test"],
                "allowedGroups":["group-id"],
                "groupNames":{"group-id":"Codex"},
                "groupMultipliers":{"group-id":1}
            }
            """.trimIndent()
        )

        assertTrue(result is PlanUsageQueryResult.Available)
        val usage = (result as PlanUsageQueryResult.Available).usage
        assertEquals("成长版", usage.planName)
        assertEquals(60.44455, usage.dailyUsedUsd ?: 0.0, 0.0)
        assertEquals(-0.44455, usage.dailyRemainingUsd ?: 0.0, 0.0)
        assertEquals(listOf("gpt-test"), usage.allowedModels)
        assertEquals(1.0, usage.groupMultipliers["group-id"] ?: 0.0, 0.0)
    }

    @Test
    fun literalNullBody_mapsToExpired() {
        val result = repository.mapHttpResponse(200, "  null  ")

        assertSame(PlanUsageQueryResult.Expired, result)
    }

    @Test
    fun emptySuccessBody_mapsToInvalidResponse() {
        val result = repository.mapHttpResponse(200, "  ")

        assertTrue(result is PlanUsageQueryResult.Failure)
        assertSame(
            PlanUsageQueryError.InvalidResponse,
            (result as PlanUsageQueryResult.Failure).error
        )
    }

    @Test
    fun unauthorizedResponse_mapsToInvalidApiKey() {
        val result = repository.mapHttpResponse(401, "{\"code\":\"invalid_api_key\"}")

        assertTrue(result is PlanUsageQueryResult.Failure)
        assertSame(
            PlanUsageQueryError.InvalidApiKey,
            (result as PlanUsageQueryResult.Failure).error
        )
    }

    @Test
    fun otherHttpFailure_preservesResponseCode() {
        val result = repository.mapHttpResponse(503, "service unavailable")

        assertTrue(result is PlanUsageQueryResult.Failure)
        val error = (result as PlanUsageQueryResult.Failure).error
        assertTrue(error is PlanUsageQueryError.Http)
        assertEquals(503, (error as PlanUsageQueryError.Http).responseCode)
    }
}
