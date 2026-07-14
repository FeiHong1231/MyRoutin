package com.hss.mycodex.widget

import android.content.Context
import android.content.res.Resources

/**
 * 代码创建页面时统一把 dp 转成 px，保持和参考项目的尺寸写法一致。
 */
val Number.dp: Int
    get() = (toFloat() * Resources.getSystem().displayMetrics.density + 0.5f).toInt()

/**
 * 需要跟随指定 Context 显示指标时使用，避免多窗口或特殊资源配置下取错密度。
 * @param context 当前页面或控件使用的资源上下文
 */
fun Number.dp(context: Context): Int {
    return (toFloat() * context.resources.displayMetrics.density + 0.5f).toInt()
}
