package com.bastion.desktop.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bastion.desktop.di.AppContainer
import com.bastion.desktop.ui.bitwarden.BitwardenTab
import com.bastion.desktop.ui.kdbx.KdbxTab
import com.bastion.desktop.ui.settings.SettingsTab

/** 主导航 Tab。 */
enum class MainTab(val label: String, val icon: String) {
    Bitwarden("Bitwarden", "\uD83D\uDD10"),
    Kdbx("本地 KDBX", "\uD83D\uDD12"),
    Settings("设置", "⚙")
}

/**
 * 主应用外壳：左侧 NavigationRail + 内容区。
 */
@Composable
fun BastionApp() {
    var currentTab by rememberSaveable { mutableStateOf(MainTab.Bitwarden) }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail {
                    MainTab.entries.forEach { tab ->
                        NavigationRailItem(
                            selected = currentTab == tab,
                            onClick = { currentTab = tab },
                            icon = {
                                Text(tab.icon)
                            },
                            label = { Text(tab.label) }
                        )
                    }
                }

                Scaffold(Modifier.fillMaxSize()) { innerPadding ->
                    BoxWithContent(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        tab = currentTab
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxWithContent(modifier: Modifier = Modifier, tab: MainTab) {
    val repository = AppContainer.bitwardenRepository
    val cryptoManager = AppContainer.cryptoManager

    when (tab) {
        MainTab.Bitwarden -> BitwardenTab(repository = repository)
        MainTab.Kdbx -> KdbxTab(cryptoManager = cryptoManager)
        MainTab.Settings -> SettingsTab(repository = repository)
    }
}
