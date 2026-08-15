package com.bastion.desktop.ui.bitwarden

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.bastion.app.bitwarden.repository.BitwardenRepository
import com.bastion.app.data.PasswordEntry
import kotlinx.coroutines.launch

/**
 * Bitwarden Tab：
 * 1. 未登录 → 登录表单（邮箱/主密码/服务器/两步验证）
 * 2. 已登录 → 条目列表 + 工具栏（同步/上传/退出）
 * 3. 编辑条目对话框；冲突备份列表
 */
@Composable
fun BitwardenTab(repository: BitwardenRepository) {
    val scope = rememberCoroutineScope()

    // 登录状态
    var loginUiState by remember {
        mutableStateOf(LoginUiState())
    }
    var activeVaultId by remember { mutableStateOf<Long?>(null) }
    var vaults by remember { mutableStateOf(emptyList<com.bastion.app.data.bitwarden.BitwardenVault>()) }
    var loading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // 刷新 vault 列表
    LaunchedEffect(Unit) {
        vaults = repository.getAllVaults()
        activeVaultId = repository.getActiveVault()?.id
    }

    // 无 vault → 登录页
    if (activeVaultId == null) {
        LoginForm(
            state = loginUiState,
            onStateChange = { loginUiState = it },
            loading = loading,
            statusMessage = statusMessage,
            onLogin = { email, password, server ->
                loading = true
                statusMessage = null
                scope.launch {
                    val result = repository.login(email, password, server)
                    loading = false
                    when (result) {
                        is BitwardenRepository.RepositoryLoginResult.Success -> {
                            activeVaultId = result.vault.id
                            vaults = repository.getAllVaults()
                        }
                        is BitwardenRepository.RepositoryLoginResult.TwoFactorRequired -> {
                            loginUiState = loginUiState.copy(
                                require2fa = true,
                                providers = result.providers,
                                // 默认选中服务器返回的第一个可用方式（服务器按优先级排序）
                                selected2faProvider = result.providers.firstOrNull() ?: 0,
                                twoFactorState = result.state
                            )
                        }
                        is BitwardenRepository.RepositoryLoginResult.CaptchaRequired -> {
                            statusMessage = "需要验证码（暂不支持自动化，请在服务器端完成）"
                        }
                        is BitwardenRepository.RepositoryLoginResult.Error -> {
                            statusMessage = result.message + if (result.message.contains("403")) {
                                "\n提示：请求被 Bitwarden 风控拦截（403）。应用已自动尝试备用指纹；若仍失败，" +
                                    "请检查系统代理/VPN 是否被拦截，或更换网络后重试。"
                            } else {
                                ""
                            }
                        }
                    }
                }
            },
            onTwoFactor = { provider, code ->
                loading = true
                statusMessage = null
                scope.launch {
                    val state = loginUiState.twoFactorState
                    val result = if (state != null) {
                        repository.loginWithTwoFactor(state, provider, code)
                    } else {
                        BitwardenRepository.RepositoryLoginResult.Error("两步验证会话已失效，请重新登录")
                    }
                    loading = false
                    when (result) {
                        is BitwardenRepository.RepositoryLoginResult.Success -> {
                            activeVaultId = result.vault.id
                            vaults = repository.getAllVaults()
                        }
                        is BitwardenRepository.RepositoryLoginResult.Error -> {
                            statusMessage = result.message + if (result.message.contains("403")) {
                                "\n提示：请求被 Bitwarden 风控拦截（403）。应用已自动尝试备用指纹；若仍失败，" +
                                    "请检查系统代理/VPN 是否被拦截，或更换网络后重试。"
                            } else {
                                ""
                            }
                        }
                        is BitwardenRepository.RepositoryLoginResult.TwoFactorRequired -> {
                            loginUiState = loginUiState.copy(twoFactorState = result.state)
                        }
                        is BitwardenRepository.RepositoryLoginResult.CaptchaRequired -> {
                            statusMessage = "需要验证码（暂不支持自动化）"
                        }
                    }
                }
            }
        )
        return
    }

    // 已登录 → 主界面
    val vaultId = activeVaultId!!
    BitwardenVaultScreen(
        repository = repository,
        vaultId = vaultId,
        onLogout = {
            scope.launch {
                repository.logout(vaultId)
                activeVaultId = null
                vaults = repository.getAllVaults()
            }
        }
    )
}

private data class LoginUiState(
    val email: String = "",
    val masterPassword: String = "",
    val serverUrl: String = "https://vault.bitwarden.com",
    val require2fa: Boolean = false,
    val providers: List<Int> = emptyList(),
    val selected2faProvider: Int = 0,
    val twoFactorCode: String = "",
    val twoFactorState: com.bastion.app.bitwarden.service.LoginResult.TwoFactorRequired? = null
)

/** Bitwarden 两步验证方式名称（providerId → 显示名）。 */
private fun twoFactorProviderLabel(provider: Int): String = when (provider) {
    0 -> "验证器应用"
    1 -> "邮箱验证码"
    2 -> "Duo"
    3 -> "YubiKey"
    4 -> "安全密钥(U2F)"
    else -> "验证方式 $provider"
}

/**
 * 登录表单。
 */
@Composable
private fun LoginForm(
    state: LoginUiState,
    onStateChange: (LoginUiState) -> Unit,
    loading: Boolean,
    statusMessage: String?,
    onLogin: (email: String, password: String, server: String) -> Unit,
    onTwoFactor: (provider: Int, code: String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(Modifier.width(420.dp), elevation = CardDefaults.cardElevation(4.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text("连接到 Bitwarden", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = state.email,
                    onValueChange = { onStateChange(state.copy(email = it)) },
                    label = { Text("邮箱") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.masterPassword,
                    onValueChange = { onStateChange(state.copy(masterPassword = it)) },
                    label = { Text("主密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.serverUrl,
                    onValueChange = { onStateChange(state.copy(serverUrl = it)) },
                    label = { Text("服务器地址（自托管填写）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (state.require2fa) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Text("两步验证", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    if (state.providers.isNotEmpty()) {
                        // 验证方式选择：以前硬编码 provider=0（验证器应用），
                        // 邮箱验证码/YubiKey 等账户会报 "Two-step token is invalid"。
                        Text(
                            "验证方式",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.providers.forEach { provider ->
                                FilterChip(
                                    selected = state.selected2faProvider == provider,
                                    onClick = {
                                        onStateChange(state.copy(selected2faProvider = provider))
                                    },
                                    label = { Text(twoFactorProviderLabel(provider)) }
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedTextField(
                        value = state.twoFactorCode,
                        onValueChange = { onStateChange(state.copy(twoFactorCode = it)) },
                        label = { Text("验证码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { onTwoFactor(state.selected2faProvider, state.twoFactorCode.trim()) },
                        enabled = !loading && state.twoFactorCode.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("提交验证码")
                    }
                }

                statusMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        onLogin(state.email.trim(), state.masterPassword, state.serverUrl.trim())
                    },
                    enabled = !loading &&
                        state.email.isNotBlank() && state.masterPassword.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (loading) {
                        CircularProgressIndicator(Modifier.width(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("连接中…")
                    } else {
                        Text("连接并同步")
                    }
                }
            }
        }
    }
}

/**
 * 已登录后的主界面：工具栏 + 条目列表 + 编辑对话框。
 */
@Composable
private fun BitwardenVaultScreen(
    repository: BitwardenRepository,
    vaultId: Long,
    onLogout: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val entries by repository.observeEntries(vaultId).collectAsState(initial = emptyList())
    var searchQuery by remember { mutableStateOf("") }
    var editingEntry by remember { mutableStateOf<PasswordEntry?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // 工具栏
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("搜索条目") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                syncing = true
                statusMessage = null
                scope.launch {
                    val result = repository.sync(vaultId)
                    syncing = false
                    when (result) {
                        is BitwardenRepository.SyncResult.Success ->
                            statusMessage = "同步完成：新增 ${result.remoteAddedCount}，更新 ${result.remoteUpdatedCount}，冲突 ${result.conflictCount}"
                        is BitwardenRepository.SyncResult.Error ->
                            statusMessage = "同步失败：${result.message}"
                        is BitwardenRepository.SyncResult.EmptyVaultBlocked ->
                            statusMessage = "同步被阻止：${result.reason}"
                    }
                }
            }, enabled = !syncing) {
                Text(if (syncing) "同步中…" else "立即同步")
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                scope.launch {
                    when (val result = repository.uploadLocalChanges(vaultId)) {
                        is BitwardenRepository.UploadChangesResult.Success ->
                            statusMessage = "上传完成：${result.uploadedCount} 条"
                        is BitwardenRepository.UploadChangesResult.Error ->
                            statusMessage = "上传失败：${result.message}"
                    }
                }
            }) {
                Text("上传修改")
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                editingEntry = PasswordEntry(bitwardenVaultId = vaultId)
            }) {
                Text("新建条目")
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onLogout) {
                Text("退出")
            }
        }

        statusMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(12.dp))

        // 条目列表
        val filtered = remember(searchQuery, entries) {
            if (searchQuery.isBlank()) entries
            else entries.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                    it.username.contains(searchQuery, ignoreCase = true) ||
                    it.website.contains(searchQuery, ignoreCase = true)
            }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(filtered, key = { it.id }) { entry ->
                PasswordEntryRow(entry) {
                    editingEntry = entry
                }
                HorizontalDivider()
            }
        }
    }

    editingEntry?.let { entry ->
        EntryEditDialog(
            entry = entry,
            onDismiss = { editingEntry = null },
            onSave = { updated ->
                scope.launch {
                    repository.saveEntry(updated)
                    editingEntry = null
                    statusMessage = "已保存，等待上传"
                }
            },
            onDelete = {
                scope.launch {
                    repository.deleteEntry(entry)
                    editingEntry = null
                    statusMessage = "已删除，等待上传"
                }
            }
        )
    }
}

@Composable
private fun PasswordEntryRow(entry: PasswordEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(entry.title.ifBlank { "未命名" }, style = MaterialTheme.typography.titleMedium)
            if (entry.username.isNotBlank()) {
                Text(entry.username, style = MaterialTheme.typography.bodySmall)
            }
            if (entry.website.isNotBlank()) {
                Text(entry.website, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
        if (entry.bitwardenLocalModified) {
            Text("未同步", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelSmall)
        }
        TextButton(onClick = onClick) { Text("编辑") }
    }
}

/**
 * 条目编辑对话框。
 */
@Composable
private fun EntryEditDialog(
    entry: PasswordEntry,
    onDismiss: () -> Unit,
    onSave: (PasswordEntry) -> Unit,
    onDelete: () -> Unit
) {
    var title by remember { mutableStateOf(entry.title) }
    var username by remember { mutableStateOf(entry.username) }
    var password by remember { mutableStateOf(entry.password) }
    var website by remember { mutableStateOf(entry.website) }
    var notes by remember { mutableStateOf(entry.notes) }
    var showPassword by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entry.id == 0L) "新建条目" else "编辑条目") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = if (showPassword) {
                        androidx.compose.ui.text.input.VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showPassword, onCheckedChange = { showPassword = it })
                    Text("显示密码")
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = website,
                    onValueChange = { website = it },
                    label = { Text("网址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("备注") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Row {
                if (entry.id != 0L) {
                    TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    onSave(
                        entry.copy(
                            title = title,
                            username = username,
                            password = password,
                            website = website,
                            notes = notes
                        )
                    )
                }) {
                    Text("保存")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
