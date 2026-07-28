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
