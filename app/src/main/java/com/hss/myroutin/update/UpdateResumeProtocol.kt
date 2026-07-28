package com.hss.myroutin.update

/**
 * 说明：HTTP 断点续传协议规则，集中解析 Content-Range、规范 ETag 并判断响应能否安全追加。
 *
 * @作者 huangssh
 * @版本 2.3
 */
internal object UpdateResumeProtocol {

    /**
     * 校验断点响应确实从本地文件末尾开始，并在存在强 ETag 时确认仍是同一个远端实体。
     * @param contentRangeHeader 服务端返回的 Content-Range
     * @param responseEntityTag 服务端本次响应的 ETag
     * @param expectedTotalBytes 更新清单声明的 APK 总字节数
     * @param resumeBytes 本地临时文件当前长度
     * @param cachedEntityTag 本地分片记录的强 ETag
     * @return 当前 206 响应能否安全追加到已有分片
     */
    fun isValidResumeResponse(
        contentRangeHeader: String?,
        responseEntityTag: String?,
        expectedTotalBytes: Long?,
        resumeBytes: Long,
        cachedEntityTag: String?
    ): Boolean {
        val contentRange = parseContentRange(contentRangeHeader) ?: return false
        if (contentRange.startByte != resumeBytes) {
            return false
        }
        if (expectedTotalBytes != null && contentRange.totalBytes != expectedTotalBytes) {
            return false
        }
        if (cachedEntityTag != null && normalizeStrongEntityTag(responseEntityTag) != cachedEntityTag) {
            return false
        }
        return true
    }

    /**
     * 解析 HTTP 字节范围，拒绝倒序、越过总大小或无法转换为 Long 的响应头。
     * @param headerValue 服务端返回的 Content-Range
     * @return 通过结构和边界校验的字节范围
     */
    fun parseContentRange(headerValue: String?): HttpByteContentRange? {
        val matchResult = headerValue
            ?.trim()
            ?.let(CONTENT_RANGE_PATTERN::matchEntire)
            ?: return null
        val startByte = matchResult.groupValues[1].toLongOrNull() ?: return null
        val endByte = matchResult.groupValues[2].toLongOrNull() ?: return null
        val rawTotalBytes = matchResult.groupValues[3]
        val totalBytes = if (rawTotalBytes == UNKNOWN_CONTENT_RANGE_TOTAL) {
            null
        } else {
            rawTotalBytes.toLongOrNull() ?: return null
        }
        if (
            endByte < startByte ||
            (totalBytes != null && (totalBytes <= 0L || endByte >= totalBytes))
        ) {
            return null
        }
        return HttpByteContentRange(
            startByte = startByte,
            endByte = endByte,
            totalBytes = totalBytes
        )
    }

    /**
     * 只接受长度受限且不含换行的强 ETag，弱校验器不能用于 If-Range 字节续传。
     * @param rawEntityTag 响应头或本地文件中的原始 ETag
     * @return 可安全保存及写入请求头的强 ETag
     */
    fun normalizeStrongEntityTag(rawEntityTag: String?): String? {
        val entityTag = rawEntityTag?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return entityTag.takeIf {
            it.length <= MAX_ENTITY_TAG_LENGTH &&
                !it.startsWith(WEAK_ENTITY_TAG_PREFIX, ignoreCase = true) &&
                it.startsWith(ENTITY_TAG_QUOTE) &&
                it.endsWith(ENTITY_TAG_QUOTE) &&
                !it.contains('\r') &&
                !it.contains('\n')
        }
    }

    private const val ENTITY_TAG_QUOTE = "\""
    private const val WEAK_ENTITY_TAG_PREFIX = "W/"
    private const val UNKNOWN_CONTENT_RANGE_TOTAL = "*"
    private const val MAX_ENTITY_TAG_LENGTH = 256
    private val CONTENT_RANGE_PATTERN = Regex(
        pattern = "^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$",
        option = RegexOption.IGNORE_CASE
    )
}

/**
 * 说明：通过校验的 HTTP 字节响应范围，供断点起点和远端文件总大小核对使用。
 *
 * @作者 huangssh
 * @版本 2.3
 */
internal data class HttpByteContentRange(
    val startByte: Long,
    val endByte: Long,
    val totalBytes: Long?
)
