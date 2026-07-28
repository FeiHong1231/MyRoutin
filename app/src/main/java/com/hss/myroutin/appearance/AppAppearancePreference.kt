package com.hss.myroutin.appearance

import android.content.Context
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import com.hss.myroutin.R

/**
 * 说明：管理应用外观偏好，确保手动选择在重启后仍生效，并允许用户恢复跟随系统。
 *
 * @param storageValue 写入 SharedPreferences 的稳定值，不随展示语言变化
 * @param displayNameResId 当前外观模式对应的本地化名称资源
 * @作者 huangssh
 * @版本 2.2
 */
enum class AppearanceMode(
    val storageValue: String,
    @StringRes val displayNameResId: Int
) {
    /** 不覆盖系统深浅色设置，作为新安装用户的默认模式。 */
    FOLLOW_SYSTEM("follow_system", R.string.appearance_follow_system),

    /** 固定使用浅色界面，忽略系统的深色模式。 */
    LIGHT("light", R.string.appearance_light),

    /** 固定使用深色界面，忽略系统的浅色模式。 */
    DARK("dark", R.string.appearance_dark)
}

/**
 * 说明：外观偏好存储与 AppCompat 夜间模式的统一入口，避免 Activity 自行维护持久化状态。
 *
 * @作者 huangssh
 * @版本 2.1
 */
object AppAppearancePreference {

    /** 外观偏好独立存放，避免与加密 Key 数据共用同一个存储文件。 */
    private const val PREFS_NAME = "app_appearance_preferences"

    /** 存储用户主动选择的模式；缺失时按跟随系统处理。 */
    private const val KEY_APPEARANCE_MODE = "appearance_mode"

    /**
     * 应用启动时恢复已保存的外观模式，应在首个 Activity 创建前调用。
     * @param context 用于读取应用级偏好的 Context
     */
    fun applySavedMode(context: Context) {
        applyMode(getSelectedMode(context))
    }

    /**
     * 读取当前用户选择；旧版本未保存该值时，默认返回跟随系统。
     * @param context 用于读取应用级偏好的 Context
     * @return 当前生效的外观偏好
     */
    fun getSelectedMode(context: Context): AppearanceMode {
        val storedValue = getPreferences(context).getString(KEY_APPEARANCE_MODE, null)
        return AppearanceMode.values().firstOrNull { it.storageValue == storedValue }
            ?: AppearanceMode.FOLLOW_SYSTEM
    }

    /**
     * 保存用户选择并立即更新 AppCompat 的夜间模式，使当前页面自动按新主题重建。
     * @param context 用于写入应用级偏好的 Context
     * @param appearanceMode 用户选择的目标外观模式
     */
    fun saveAndApply(context: Context, appearanceMode: AppearanceMode) {
        getPreferences(context)
            .edit()
            .putString(KEY_APPEARANCE_MODE, appearanceMode.storageValue)
            .apply()
        applyMode(appearanceMode)
    }

    /**
     * 将业务外观枚举映射到 AppCompat 模式；仅在状态变化时设置，避免重复触发页面重建。
     * @param appearanceMode 当前要应用的外观模式
     */
    private fun applyMode(appearanceMode: AppearanceMode) {
        val nightMode = when (appearanceMode) {
            AppearanceMode.FOLLOW_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            AppearanceMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            AppearanceMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        if (AppCompatDelegate.getDefaultNightMode() != nightMode) {
            AppCompatDelegate.setDefaultNightMode(nightMode)
        }
    }

    /**
     * 统一使用 applicationContext，避免偏好管理器意外持有 Activity 实例。
     * @param context 任意可用的应用 Context
     * @return 外观偏好对应的 SharedPreferences
     */
    private fun getPreferences(context: Context) = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
}
