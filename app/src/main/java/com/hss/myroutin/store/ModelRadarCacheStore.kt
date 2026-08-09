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

    /** 读取 SharedPreferences 和 APK assets 都使用 Application Context，避免持有页面实例。 */
    private val applicationContext = context.applicationContext

    /** 公开雷达数据无需加密，但与订阅 Key 的加密缓存严格分库存放。 */
    private val preferences = applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    /** 缓存损坏或版本不兼容时返回空值，后续由网络请求重新生成。 */
    fun load(): ModelRadarSnapshot? {
        val json = preferences.getString(KEY_SNAPSHOT, null) ?: return null
        return runCatching { ModelRadarJsonCodec.decodeSnapshot(json) }.getOrNull()
    }

    /**
     * 读取随 APK 发布的聚合快照，为首次安装且无法访问 CodexRadar 的用户提供基础数据。
     * @return assets 快照有效时返回领域对象，文件缺失或损坏时返回空
     */
    fun loadBundled(): ModelRadarSnapshot? {
        return runCatching {
            applicationContext.assets.open(BUNDLED_SNAPSHOT_ASSET)
                .bufferedReader()
                .use { reader -> ModelRadarJsonCodec.decodeSnapshot(reader.readText()) }
        }.getOrNull()
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
        /** 发布前由 tools/update_model_radar_asset.py 从最新聚合缓存更新。
         * python3 tools/update_model_radar_asset.py */
        private const val BUNDLED_SNAPSHOT_ASSET = "model_radar_snapshot.json"
    }
}
