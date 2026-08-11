package com.hss.myroutin.store

import android.content.Context
import com.hss.myroutin.model.RoutinRecentGroup

/**
 * 说明：保存最近一次 Routin 分组同步结果，登录会话仍由 WebView Cookie 管理。
 *
 * @作者 huangssh
 * @版本 5.1
 */
class RoutinRecentGroupStore(context: Context) {

    /** 仅保存可展示的分组、计费倍率和时间，不把网页登录 Token 或密码写入本地。 */
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    /** 读取完整且可展示的同步结果；任一关键字段缺失都按暂无数据处理。 */
    fun load(): RoutinRecentGroup? {
        val groupName = preferences.getString(KEY_GROUP_NAME, null)?.trim().orEmpty()
        val requestTime = preferences.getString(KEY_REQUEST_TIME, null)?.trim().orEmpty()
        val multiplier = preferences.getString(KEY_MULTIPLIER, null)?.toDoubleOrNull()
        return if (groupName.isNotEmpty() && requestTime.isNotEmpty() && multiplier != null) {
            RoutinRecentGroup(groupName, multiplier, requestTime)
        } else {
            null
        }
    }

    /** 同步成功后覆盖上一条结果，失败时由调用方不写入以保留旧提示。 */
    fun save(group: RoutinRecentGroup) {
        preferences.edit()
            .putString(KEY_GROUP_NAME, group.groupName)
            .putString(KEY_MULTIPLIER, group.multiplier.toString())
            .putString(KEY_REQUEST_TIME, group.requestTime)
            .apply()
    }

    private companion object {
        private const val PREFERENCES_NAME = "routin_recent_group"
        private const val KEY_GROUP_NAME = "group_name"
        private const val KEY_MULTIPLIER = "multiplier"
        private const val KEY_REQUEST_TIME = "request_time"
    }
}
