package com.hss.myroutin.store

import android.content.Context
import com.hss.myroutin.model.ModelRadarSnapshot
import com.hss.myroutin.serialization.ModelRadarJsonCodec

/**
 * 说明：保存公开模型雷达的紧凑快照，使断网或第三方接口异常时仍可展示最近一次成功数据。
 *
 * @作者 huangssh
 * @版本 3.0
 */
internal class ModelRadarCacheStore(context: Context) {

    /** 公开雷达数据无需加密，但与订阅 Key 的加密缓存严格分库存放。 */
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    /** 缓存损坏或版本不兼容时返回空值，后续由网络请求重新生成。 */
    fun load(): ModelRadarSnapshot? {
        val json = preferences.getString(KEY_SNAPSHOT, null) ?: return null
        return runCatching { ModelRadarJsonCodec.decodeSnapshot(json) }.getOrNull()
    }

    /**
     * 只写入聚合后的页面快照，不保存 CodexRadar 完整任务、贡献者或历史运行明细。
     * @param snapshot 本次成功聚合的雷达快照
     */
    fun save(snapshot: ModelRadarSnapshot) {
        preferences.edit()
            .putString(KEY_SNAPSHOT, ModelRadarJsonCodec.encodeSnapshot(snapshot))
            .apply()
    }

    private companion object {
        private const val PREFERENCES_NAME = "model_radar_cache"
        private const val KEY_SNAPSHOT = "snapshot_v2"
    }
}
