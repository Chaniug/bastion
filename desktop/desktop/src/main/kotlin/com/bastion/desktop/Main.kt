package com.bastion.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.bastion.app.platform.Logger

fun main() = application {
    Logger.i("BastionDesktop", "Bastion Desktop starting")
    Window(
        onCloseRequest = ::exitApplication,
        title = "Bastion",
        state = rememberWindowState(size = DpSize(1080.dp, 720.dp)),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            PlaceholderScreen()
        }
    }
}

@Composable
private fun PlaceholderScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Bastion Desktop — Phase 0 骨架就绪")
    }
}
