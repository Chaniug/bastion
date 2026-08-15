package com.bastion.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.bastion.app.bitwarden.service.BitwardenDiagLogger
import com.bastion.app.platform.Logger
import com.bastion.desktop.di.AppContainer
import com.bastion.desktop.ui.BastionApp

fun main() = application {
    // 初始化 Bitwarden 登录诊断落盘日志（%APPDATA%\BastionDesktop\bitwarden_logs\）
    // 必须在任何登录请求前调用，否则诊断日志只打到 stdout 不会落盘
    BitwardenDiagLogger.initialize()
    Logger.i("BastionDesktop", "Bastion Desktop starting")
    AppContainer.syncScheduler.start()
    Window(
        onCloseRequest = {
            AppContainer.syncScheduler.stop()
            exitApplication()
        },
        title = "Bastion",
        state = rememberWindowState(size = DpSize(1080.dp, 720.dp)),
    ) {
        BastionApp()
    }
}
