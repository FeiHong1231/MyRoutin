package com.hss.myroutin.store

import android.content.Context
import android.content.SharedPreferences
import com.hss.myroutin.model.SavedPlanKey
import com.hss.myroutin.serialization.SavedPlanKeyJsonCodec

/**
 * 说明：区分正常空数据和无法认证或解析的本机缓存，避免页面把异常情况误展示为“没有 Key”。
 *
 * @作者 huangssh
 * @版本 2.2
 */
sealed interface PlanUsageKeyLoadResult {

    /** 本机密文认证和 JSON 解析均成功，可安全使用已读取的 Key。 */
    data class Loaded(val keys: List<SavedPlanKey>) : PlanUsageKeyLoadResult

    /** 当前设备没有保存过 Key，不需要向用户展示异常提示。 */
    object Empty : PlanUsageKeyLoadResult

    /** 密文可能来自其他设备、已损坏或当前 Keystore 已失效，禁止继续加载。 */
    object Unreadable : PlanUsageKeyLoadResult
}

/**
 * 说明：订阅 Key 列表的本机加密存储入口，只负责密文读写，JSON 格式和迁移由纯 Codec 处理。
 *
 * @作者 huangssh
 * @版本 2.3
 */
class PlanUsageKeyStore(context: Context) {

    /** SharedPreferences 仅承载密文，实际 AES 密钥由 Android Keystore 管理且不可导出。 */
    private val preferences: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 统一加密整份 JSON，避免 Key 与缓存字段被拆分后遗漏保护。 */
    private val cipher = KeystoreAesGcmCipher()

    init {
        clearLegacyPlainTextData()
    }

    /**
     * 仅接受认证通过且完整可解析的密文；旧 JSON 成功迁移后立即回写收敛格式。
     */
    fun loadKeys(): PlanUsageKeyLoadResult {
        val encryptedPayload = preferences.getString(PREF_KEY_ENCRYPTED_PLAN_KEYS, null)
        if (encryptedPayload.isNullOrBlank()) {
            return PlanUsageKeyLoadResult.Empty
        }
        return try {
            val decodedKeys = SavedPlanKeyJsonCodec.decode(cipher.decrypt(encryptedPayload))
            if (decodedKeys.requiresRewrite) {
                saveKeys(decodedKeys.keys)
            }
            PlanUsageKeyLoadResult.Loaded(decodedKeys.keys)
        } catch (_: Exception) {
            PlanUsageKeyLoadResult.Unreadable
        }
    }

    /**
     * 加密后写入整个列表，确保名称、排序、状态和额度快照始终保持一致。
     * @param keys 当前全部订阅 Key
     */
    fun saveKeys(keys: List<SavedPlanKey>) {
        preferences.edit()
            .putString(
                PREF_KEY_ENCRYPTED_PLAN_KEYS,
                cipher.encrypt(SavedPlanKeyJsonCodec.encode(keys))
            )
            .apply()
    }

    /** 当前尚未对外分发，直接清除历史明文字段，避免长期凭证残留。 */
    private fun clearLegacyPlainTextData() {
        preferences.edit()
            .remove(PREF_KEY_PLAN_KEYS)
            .remove(PREF_KEY_API_KEY)
            .remove(PREF_KEY_DAY_WINDOW_END_AT)
            .remove(PREF_KEY_WEEK_WINDOW_END_AT)
            .apply()
    }

    private companion object {
        private const val PREFS_NAME = "plan_usage_input_cache"
        private const val PREF_KEY_ENCRYPTED_PLAN_KEYS = "encrypted_plan_keys_v1"
        private const val PREF_KEY_PLAN_KEYS = "plan_keys"
        private const val PREF_KEY_API_KEY = "api_key"
        private const val PREF_KEY_DAY_WINDOW_END_AT = "day_window_end_at"
        private const val PREF_KEY_WEEK_WINDOW_END_AT = "week_window_end_at"
    }
}
