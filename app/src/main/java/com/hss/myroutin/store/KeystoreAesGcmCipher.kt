package com.hss.myroutin.store

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 说明：使用 Android Keystore 持有不可导出的 AES 密钥，为本机敏感存储提供认证加密能力。
 *
 * @作者 huangssh
 * @版本 2.1
 */
class KeystoreAesGcmCipher {

    /**
     * 将明文序列化为“版本:随机 IV:密文”格式；每次写入使用新的 IV，避免重复加密产生相同密文。
     * @param plainText 需要保护的 UTF-8 文本
     */
    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encryptedBytes = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        return listOf(
            PAYLOAD_VERSION,
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
        ).joinToString(PAYLOAD_SEPARATOR)
    }

    /**
     * 校验并解密本机生成的密文；认证失败时直接抛出异常，禁止将被篡改的数据作为有效 Key 使用。
     * @param encryptedPayload 持久化的加密载荷
     */
    fun decrypt(encryptedPayload: String): String {
        val payloadParts = encryptedPayload.split(PAYLOAD_SEPARATOR)
        require(payloadParts.size == PAYLOAD_PART_COUNT && payloadParts[0] == PAYLOAD_VERSION) {
            "本地加密数据格式无效"
        }
        val initializationVector = Base64.decode(payloadParts[1], Base64.NO_WRAP)
        val encryptedBytes = Base64.decode(payloadParts[2], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateSecretKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, initializationVector)
        )
        return String(cipher.doFinal(encryptedBytes), StandardCharsets.UTF_8)
    }

    /**
     * 密钥只在首次使用时由系统生成并保留在 Android Keystore，应用无法读取或导出密钥原文。
     */
    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(AES_KEY_SIZE_BITS)
                    .build()
            )
        }.generateKey()
    }

    private companion object {
        /** 密钥别名按存储格式版本区分，后续升级不会误用不兼容的旧密钥。 */
        private const val KEY_ALIAS = "com.hss.myroutin.plan_usage_store.v1"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val AES_KEY_SIZE_BITS = 256
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val PAYLOAD_VERSION = "1"
        private const val PAYLOAD_SEPARATOR = ":"
        private const val PAYLOAD_PART_COUNT = 3
    }
}
