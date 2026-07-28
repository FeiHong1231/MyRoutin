package com.hss.myroutin.update

import java.io.File
import java.security.MessageDigest

/**
 * 说明：APK SHA-256 完整性校验器，可在续传时先纳入旧分片，再连续接收新下载字节。
 *
 * @作者 huangssh
 * @版本 2.3
 */
internal class UpdateApkIntegrityVerifier {

    /**
     * 创建一次下载对应的摘要会话；续传时旧分片必须先进入摘要，最终才能校验完整 APK。
     * @param existingPartialFile 本次会继续追加的已有分片，完整下载时为 null
     * @return 可持续接收字节并完成校验的摘要会话
     */
    fun createSession(existingPartialFile: File?): UpdateIntegritySession {
        val session = UpdateIntegritySession(MessageDigest.getInstance(SHA_256_ALGORITHM))
        existingPartialFile?.inputStream()?.buffered()?.use { inputStream ->
            val buffer = ByteArray(DIGEST_BUFFER_SIZE)
            while (true) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead < 0) {
                    break
                }
                session.update(buffer, bytesRead)
            }
        }
        return session
    }

    private companion object {
        private const val SHA_256_ALGORITHM = "SHA-256"
        private const val DIGEST_BUFFER_SIZE = 8 * 1024
    }
}

/**
 * 说明：单次 APK 下载的增量摘要状态，digest 只能在所有字节写入完成后消费一次。
 *
 * @param digest 当前下载独占的 SHA-256 实例
 * @作者 huangssh
 * @版本 2.3
 */
internal class UpdateIntegritySession(
    private val digest: MessageDigest
) {

    /**
     * 将刚写入 APK 分片的有效字节同步加入摘要。
     * @param buffer 下载复用缓冲区
     * @param byteCount 本次实际读取的有效字节数
     */
    fun update(buffer: ByteArray, byteCount: Int) {
        digest.update(buffer, 0, byteCount)
    }

    /**
     * 完成摘要并和清单声明值比较，忽略十六进制大小写但不接受其他格式。
     * @param expectedSha256 更新清单声明的 SHA-256
     * @return 完整 APK 是否通过摘要校验
     */
    fun matches(expectedSha256: String): Boolean {
        return digest.digest().toHexString().equals(expectedSha256, ignoreCase = true)
    }

    /** 将摘要字节转为固定小写十六进制，和 Release 清单字段逐字节比对。 */
    private fun ByteArray.toHexString(): String {
        return joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
