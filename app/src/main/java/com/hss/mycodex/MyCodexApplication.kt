package com.hss.mycodex

import android.app.Application
import android.content.Context

/**
 * 说明：提供应用级 Context，供本地工具页的 Toast 等轻量组件安全复用。
 *
 * @作者 huangssh
 * @版本 1.0
 */
class MyCodexApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
    }

    companion object {
        /**
         * 应用启动后初始化的全局 Context，只保存 applicationContext 避免持有页面实例。
         */
        lateinit var appContext: Context
            private set
    }
}
