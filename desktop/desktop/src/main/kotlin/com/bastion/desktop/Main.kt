package com.bastion.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.bastion.app.platform.Logger
import com.bastion.desktop.ui.BastionApp

fun main() = application {
    Logger.i("BastionDesktop", "Bastion Desktop starting")
    Window(
        onCloseRequest = ::exitApplication,
        title = "Bastion",
        state = rememberWindowState(size = DpSize(1080.dp, 720.dp)),
    ) {
        BastionApp()
    }
}
