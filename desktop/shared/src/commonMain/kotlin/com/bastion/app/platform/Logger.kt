package com.bastion.app.platform

/**
 * 平台日志抽象。桌面端写控制台 + 可选日志文件，安卓端走 android.util.Log。
 */
expect object Logger {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}
