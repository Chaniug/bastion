package com.bastion.app.platform

import java.time.LocalTime
import java.time.format.DateTimeFormatter

actual object Logger {
    private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    actual fun d(tag: String, message: String) = print("D", tag, message)
    actual fun i(tag: String, message: String) = print("I", tag, message)
    actual fun w(tag: String, message: String) = print("W", tag, message)
    actual fun e(tag: String, message: String, throwable: Throwable?) {
        print("E", tag, message)
        throwable?.printStackTrace()
    }

    private fun print(level: String, tag: String, message: String) {
        val time = LocalTime.now().format(fmt)
        System.out.println("$time $level/$tag: $message")
    }
}
