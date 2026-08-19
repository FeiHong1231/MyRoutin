package com.hss.myroutin.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 说明：验证套餐到期告警的单位切换和边界展示，避免小时/分钟阈值回归。
 *
 * @作者 huangssh
 * @版本 5.1
 */
class PlanUsageFormatterTest {

    @Test
    fun resolveExpiryCountdown_usesDaysHoursAndMinutes() {
        assertEquals(
            PlanUsageFormatter.ExpiryCountdown.Remaining(2L, PlanUsageFormatter.ExpiryUnit.DAY),
            PlanUsageFormatter.resolveExpiryCountdown("1970-01-03T00:00:00Z", NOW_MILLIS)
        )
        assertEquals(
            PlanUsageFormatter.ExpiryCountdown.Remaining(23L, PlanUsageFormatter.ExpiryUnit.HOUR),
            PlanUsageFormatter.resolveExpiryCountdown("1970-01-01T23:00:00Z", NOW_MILLIS)
        )
        assertEquals(
            PlanUsageFormatter.ExpiryCountdown.Remaining(59L, PlanUsageFormatter.ExpiryUnit.MINUTE),
            PlanUsageFormatter.resolveExpiryCountdown("1970-01-01T00:59:00Z", NOW_MILLIS)
        )
    }

    @Test
    fun resolveExpiryCountdown_subMinuteRemainingStillShowsOneMinute() {
        assertEquals(
            PlanUsageFormatter.ExpiryCountdown.Remaining(1L, PlanUsageFormatter.ExpiryUnit.MINUTE),
            PlanUsageFormatter.resolveExpiryCountdown("1970-01-01T00:00:30Z", NOW_MILLIS)
        )
    }

    @Test
    fun resolveExpiryCountdown_distinguishesExpiredAndInvalidTime() {
        assertEquals(
            PlanUsageFormatter.ExpiryCountdown.Expired,
            PlanUsageFormatter.resolveExpiryCountdown("1969-12-31T23:59:59Z", NOW_MILLIS)
        )
        assertNull(PlanUsageFormatter.resolveExpiryCountdown("not-a-time", NOW_MILLIS))
    }

    @Test
    fun isExpiryWithinWarningWindow_onlyIncludesFutureEightDays() {
        assertTrue(
            PlanUsageFormatter.isExpiryWithinWarningWindow("1970-01-09T00:00:00Z", NOW_MILLIS)
        )
        assertFalse(
            PlanUsageFormatter.isExpiryWithinWarningWindow("1970-01-09T00:00:01Z", NOW_MILLIS)
        )
        assertFalse(
            PlanUsageFormatter.isExpiryWithinWarningWindow("1969-12-31T23:59:59Z", NOW_MILLIS)
        )
    }

    private companion object {
        private const val NOW_MILLIS = 0L
    }
}
