package com.hss.myroutin.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 说明：验证 Content-Range 和强 ETag 的续传边界，避免错误响应被追加到旧 APK 分片。
 *
 * @作者 huangssh
 * @版本 2.3
 */
class UpdateResumeProtocolTest {

    @Test
    fun parseContentRange_acceptsValidKnownAndUnknownTotals() {
        assertEquals(
            HttpByteContentRange(startByte = 10L, endByte = 19L, totalBytes = 100L),
            UpdateResumeProtocol.parseContentRange("bytes 10-19/100")
        )
        assertEquals(
            HttpByteContentRange(startByte = 10L, endByte = 19L, totalBytes = null),
            UpdateResumeProtocol.parseContentRange("bytes 10-19/*")
        )
    }

    @Test
    fun parseContentRange_rejectsMalformedOrOutOfBoundsValues() {
        listOf(
            null,
            "",
            "items 0-9/10",
            "bytes 9-0/10",
            "bytes 0-10/10",
            "bytes 0-9/0",
            "bytes a-b/10"
        ).forEach { header ->
            assertNull(header, UpdateResumeProtocol.parseContentRange(header))
        }
    }

    @Test
    fun normalizeStrongEntityTag_rejectsWeakMalformedAndInjectedValues() {
        assertEquals("\"release-v1\"", UpdateResumeProtocol.normalizeStrongEntityTag("  \"release-v1\"  "))
        assertNull(UpdateResumeProtocol.normalizeStrongEntityTag("W/\"release-v1\""))
        assertNull(UpdateResumeProtocol.normalizeStrongEntityTag("release-v1"))
        assertNull(UpdateResumeProtocol.normalizeStrongEntityTag("\"release-v1\"\r\nInjected: true"))
    }

    @Test
    fun validResumeResponse_requiresMatchingStartTotalAndCachedEntityTag() {
        assertTrue(
            UpdateResumeProtocol.isValidResumeResponse(
                contentRangeHeader = "bytes 5-9/10",
                responseEntityTag = "\"release-v1\"",
                expectedTotalBytes = 10L,
                resumeBytes = 5L,
                cachedEntityTag = "\"release-v1\""
            )
        )
        assertFalse(
            UpdateResumeProtocol.isValidResumeResponse(
                contentRangeHeader = "bytes 4-9/10",
                responseEntityTag = "\"release-v1\"",
                expectedTotalBytes = 10L,
                resumeBytes = 5L,
                cachedEntityTag = "\"release-v1\""
            )
        )
        assertFalse(
            UpdateResumeProtocol.isValidResumeResponse(
                contentRangeHeader = "bytes 5-9/11",
                responseEntityTag = "\"release-v1\"",
                expectedTotalBytes = 10L,
                resumeBytes = 5L,
                cachedEntityTag = "\"release-v1\""
            )
        )
        assertFalse(
            UpdateResumeProtocol.isValidResumeResponse(
                contentRangeHeader = "bytes 5-9/10",
                responseEntityTag = "\"release-v2\"",
                expectedTotalBytes = 10L,
                resumeBytes = 5L,
                cachedEntityTag = "\"release-v1\""
            )
        )
    }
}
