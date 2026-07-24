package com.hss.myroutin.widget

import android.widget.Toast
import com.hss.myroutin.MyRoutinApplication

/**
 * 说明：本地工具页统一 Toast 入口，避免页面内重复维护 Toast 上下文和防连点逻辑。
 *
 * @作者 huangssh
 * @版本 1.0
 */
object MyToastD {

    /**
     * 复用同一个 Toast 实例，连续提示时先取消旧提示，减少多次点击造成的提示堆积。
     */
    private var toast: Toast? = null

    /**
     * 展示默认时长提示。
     * @param text 需要展示的提示文案
     */
    fun show(text: String) {
        show(text, Toast.LENGTH_SHORT)
    }

    /**
     * 展示指定时长提示。
     * @param text 需要展示的提示文案
     * @param duration Toast.LENGTH_SHORT 或 Toast.LENGTH_LONG
     */
    fun show(text: String, duration: Int) {
        if (text.isBlank()) {
            return
        }
        toast?.cancel()
        toast = Toast.makeText(MyRoutinApplication.appContext, text, duration).apply {
            show()
        }
    }
}
