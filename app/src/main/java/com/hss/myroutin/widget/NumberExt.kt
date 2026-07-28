package com.hss.myroutin.widget

import android.content.res.Resources

/**
 * 代码创建页面时统一把 dp 转成 px，保持和参考项目的尺寸写法一致。
 */
val Number.dp: Int
    get() = (toFloat() * Resources.getSystem().displayMetrics.density + 0.5f).toInt()
