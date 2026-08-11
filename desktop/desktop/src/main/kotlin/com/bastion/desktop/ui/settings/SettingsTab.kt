package com.bastion.desktop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bastion.app.bitwarden.repository.BitwardenRepository
import com.bastion.app.platform.PathProvider
import com.bastion.desktop.di.AppContainer
import com.bastion.desktop.platform.OneDriveAuthException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置 Tab：
 * - Bitwarden 账号列表（退出/切换）
 * - OneDrive 账号（登录/登出）
 * - 同步偏好（自动同步、仅 Wi-Fi）
 * - 应用信息（数据目录、版本）
 */
@Composable
fun SettingsTab(repository: BitwardenRepository) {
    val scope = rememberCoroutineScope()
    val vaults by repository.getAllVaultsFlow().collectAsState(initial = emptyList())
    var autoSync by remember { mutableStateOf(repository.isAutoSyncEnabled) }
    var wifiOnly by remember { mutableStateOf(repository.isSyncOnWifiOnly) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // OneDrive 状态
    val oneDriveManager = AppContainer.oneDriveSessionManager
    var oneDriveEmail by remember { mutableStateOf(oneDriveManager.currentUserEmail()) }
    var oneDriveBusy by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        // === Bitwarden 账号 ===
        Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Bitwarden 账号", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                if (vaults.isEmpty()) {
                    Text("尚未登录", style = MaterialTheme.typography.bodyMedium)
                } else {
                    vaults.forEach { vault ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(vault.email, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    vault.serverUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (vault.isLocked) {
                                Text("已锁定", color = MaterialTheme.colorScheme.tertiary)
                            } else {
                                Text("已解锁", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // === OneDrive 账号 ===
        Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("OneDrive（KDBX 云同步）", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                if (oneDriveEmail.isNullOrBlank()) {
                    Text("未连接 OneDrive", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            oneDriveBusy = true
                            statusMessage = null
                            scope.launch {
                                try {
                                    val session = withContext(Dispatchers.IO) {
                                        AppContainer.oneDriveAuth.login()
                                    }
                                    oneDriveManager.saveSession(session)
                                    oneDriveEmail = session.userEmail.ifBlank { "OneDrive 账号" }
                                    statusMessage = "OneDrive 登录成功"
                                } catch (e: OneDriveAuthException) {
                                    statusMessage = "OneDrive 登录失败：${e.message}"
                                } catch (e: Exception) {
                                    statusMessage = "OneDrive 登录失败：${e.message}"
                                } finally {
                                    oneDriveBusy = false
                                }
                            }
                        },
                        enabled = !oneDriveBusy
                    ) {
                        Text(if (oneDriveBusy) "等待浏览器授权…" else "登录 OneDrive")
                    }
                } else {
                    val email = oneDriveEmail ?: ""
                    Text(email, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "用于把本地 KDBX 库同步到 OneDrive（需在 Azure 注册桌面应用，见 README）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        oneDriveManager.clearSession()
                        oneDriveEmail = null
                        statusMessage = "已登出 OneDrive"
                    }) {
                        Text("登出 OneDrive")
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // === 同步偏好 ===
        Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("同步偏好", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("自动同步", style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = autoSync,
                        onCheckedChange = {
                            autoSync = it
                            repository.isAutoSyncEnabled = it
                        }
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("仅 Wi-Fi 时同步（桌面端通常忽略）", style = MaterialTheme.typography.bodyLarge)
                    Checkbox(
                        checked = wifiOnly,
                        onCheckedChange = {
                            wifiOnly = it
                            repository.isSyncOnWifiOnly = it
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // === 应用信息 ===
        Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("关于", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("Bastion Desktop 0.1.0", style = MaterialTheme.typography.bodyLarge)
                Text("Bitwarden 同步器 / KDBX 编辑器 / OneDrive 同步", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "数据目录：${PathProvider.dataDir}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        statusMessage?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.primary)
        }
    }
}
