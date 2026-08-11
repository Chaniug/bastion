package com.bastion.desktop.ui.kdbx

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bastion.app.kdbx.KdbxOneDriveSyncEngine
import com.bastion.app.kdbx.KeePassEntryData
import com.bastion.app.kdbx.KeePassGroupInfo
import com.bastion.app.kdbx.KeePassKdbxService
import com.bastion.app.kdbx.OneDriveDownloadResult
import com.bastion.app.kdbx.OneDriveKeePassFileSource
import com.bastion.app.kdbx.OneDriveSyncResult
import com.bastion.app.kdbx.OpenedDatabase
import com.bastion.app.security.DesktopCryptoManager
import com.bastion.desktop.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * 本地 KDBX Tab：
 * 1. 打开文件（密码输入） / 新建库
 * 2. 左分组树 / 右条目表
 * 3. 条目编辑对话框；保存/删除
 */
@Composable
fun KdbxTab(cryptoManager: DesktopCryptoManager) {
    val service = remember { KeePassKdbxService() }
    var opened by remember { mutableStateOf<OpenedDatabase?>(null) }
    var passwordInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showOpenDialog by remember { mutableStateOf(false) }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var selectedGroupPath by remember { mutableStateOf("Root") }
    var editingEntry by remember { mutableStateOf<KeePassEntryData?>(null) }
    var groups by remember { mutableStateOf<List<KeePassGroupInfo>>(emptyList()) }
    var entries by remember { mutableStateOf<List<KeePassEntryData>>(emptyList()) }
    var showNewGroupDialog by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }

    // OneDrive 同步状态
    val scope = rememberCoroutineScope()
    val syncEngine = remember { KdbxOneDriveSyncEngine(service) }
    val oneDriveManager = AppContainer.oneDriveSessionManager
    var oneDriveEmail by remember { mutableStateOf(oneDriveManager.currentUserEmail()) }
    var oneDriveBusy by remember { mutableStateOf(false) }
    var syncStatus by remember { mutableStateOf<String?>(null) }
    var pendingUploadVersion by remember { mutableStateOf<String?>(null) }
    var showConflictDialog by remember { mutableStateOf(false) }

    /** 强制覆盖上传（expectedVersion = null），供冲突对话框与卡片共用。 */
    fun forceUpload(db: OpenedDatabase) {
        showConflictDialog = false
        oneDriveBusy = true
        syncStatus = null
        scope.launch {
            try {
                val source = OneDriveKeePassFileSource(
                    authTokenProvider = { oneDriveManager.tokenProvider() },
                    remotePath = "/bastion/${db.file.name}"
                )
                when (val result = syncEngine.upload(db, source, expectedVersion = null)) {
                    is OneDriveSyncResult.Success -> {
                        pendingUploadVersion = result.remoteEtag
                        syncStatus = "强制覆盖成功：${result.bytesWritten} 字节"
                    }
                    is OneDriveSyncResult.Error -> {
                        syncStatus = "强制覆盖失败：${result.message}"
                    }
                    else -> syncStatus = "强制覆盖未完成"
                }
            } catch (e: Exception) {
                syncStatus = "强制覆盖失败：${e.message}"
            } finally {
                oneDriveBusy = false
            }
        }
    }

    fun refresh(db: OpenedDatabase) {
        groups = service.listGroups(db.database)
        entries = service.listEntriesInGroup(db.database, selectedGroupPath)
    }

    // ===== 未打开 =====
    if (opened == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(Modifier.width(460.dp), elevation = CardDefaults.cardElevation(4.dp)) {
                Column(Modifier.padding(24.dp)) {
                    Text("本地 KDBX 数据库", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "打开或新建 KeePass KDBX 文件，像 KeePassXC 一样管理本地密码库。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = {
                            passwordInput = ""
                            errorMessage = null
                            val chooser = JFileChooser().apply {
                                fileFilter = FileNameExtensionFilter("KeePass Database (*.kdbx)", "kdbx")
                                dialogTitle = "选择 KDBX 文件"
                            }
                            val result = chooser.showOpenDialog(null)
                            if (result == JFileChooser.APPROVE_OPTION) {
                                showOpenDialog = true
                                selectedFile = chooser.selectedFile
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("打开 KDBX 文件…")
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val chooser = JFileChooser().apply {
                                fileFilter = FileNameExtensionFilter("KeePass Database (*.kdbx)", "kdbx")
                                dialogTitle = "新建 KDBX 数据库"
                            }
                            val result = chooser.showSaveDialog(null)
                            val file = chooser.selectedFile
                            if (result == JFileChooser.APPROVE_OPTION && file != null) {
                                val target = if (file.extension.equals("kdbx", true)) file else File(file.absolutePath + ".kdbx")
                                try {
                                    val db = service.create(target, "change-me")
                                    opened = db
                                    selectedGroupPath = "Root"
                                    refresh(db)
                                } catch (e: Exception) {
                                    errorMessage = e.message
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("新建库（默认密码 change-me）")
                    }
                    errorMessage?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // 打开文件对话框（选完文件后输入密码）
        if (showOpenDialog) {
            AlertDialog(
                onDismissRequest = { showOpenDialog = false },
                title = { Text("打开数据库") },
                text = {
                    Column {
                        Text(selectedFile?.name ?: "", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = { passwordInput = it },
                            label = { Text("主密码") },
                            singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        try {
                            val file = selectedFile ?: return@Button
                            val db = service.open(file, passwordInput)
                            opened = db
                            selectedGroupPath = "Root"
                            showOpenDialog = false
                            refresh(db)
                        } catch (e: Exception) {
                            errorMessage = e.message
                        }
                    }) {
                        Text("打开")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showOpenDialog = false }) { Text("取消") }
                }
            )
        }
        return
    }

    val db = opened!!

    // 已打开 → 双栏界面
    Row(Modifier.fillMaxSize()) {
        // 左侧分组树
        LazyColumn(
            Modifier.width(220.dp).fillMaxSize().padding(8.dp)
        ) {
            item {
                Text("分组", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
            }
            items(groups, key = { it.path }) { group ->
                Text(
                    group.path + " (${group.entryCount})",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedGroupPath = group.path
                            refresh(db)
                        }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    style = if (group.path == selectedGroupPath) {
                        MaterialTheme.typography.bodyMedium
                    } else {
                        MaterialTheme.typography.bodySmall
                    },
                    color = if (group.path == selectedGroupPath) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }
            item {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        newGroupName = ""
                        showNewGroupDialog = true
                    }
                ) { Text("＋ 新建分组") }
            }
        }

        HorizontalDivider()

        // 右侧条目表
        Column(Modifier.weight(1f).fillMaxSize().padding(8.dp)) {
            // === OneDrive 同步卡片 ===
            OneDriveSyncCard(
                isLoggedIn = !oneDriveEmail.isNullOrBlank(),
                email = oneDriveEmail,
                busy = oneDriveBusy,
                status = syncStatus,
                onLogin = {
                    oneDriveBusy = true
                    syncStatus = null
                    scope.launch {
                        try {
                            val session = withContext(Dispatchers.IO) {
                                AppContainer.oneDriveAuth.login()
                            }
                            oneDriveManager.saveSession(session)
                            oneDriveEmail = session.userEmail.ifBlank { "OneDrive 账号" }
                            syncStatus = "OneDrive 登录成功"
                        } catch (e: Exception) {
                            syncStatus = "OneDrive 登录失败：${e.message}"
                        } finally {
                            oneDriveBusy = false
                        }
                    }
                },
                onUpload = {
                    oneDriveBusy = true
                    syncStatus = null
                    scope.launch {
                        try {
                            val source = OneDriveKeePassFileSource(
                                authTokenProvider = { oneDriveManager.tokenProvider() },
                                remotePath = "/bastion/${db.file.name}"
                            )
                            when (val result = syncEngine.upload(db, source, expectedVersion = pendingUploadVersion)) {
                                is OneDriveSyncResult.Success -> {
                                    pendingUploadVersion = result.remoteEtag
                                    syncStatus = "上传成功：${result.bytesWritten} 字节"
                                }
                                is OneDriveSyncResult.Conflict -> {
                                    showConflictDialog = true
                                    pendingUploadVersion = null
                                    syncStatus = "同步冲突：远端文件已被修改"
                                }
                                is OneDriveSyncResult.Error -> {
                                    syncStatus = "上传失败：${result.message}"
                                }
                                OneDriveSyncResult.RemoteMissing -> {
                                    syncStatus = "远端文件不存在，请先确认 /bastion 文件夹存在"
                                }
                            }
                        } catch (e: Exception) {
                            syncStatus = "上传失败：${e.message}"
                        } finally {
                            oneDriveBusy = false
                        }
                    }
                },
                onDownload = {
                    oneDriveBusy = true
                    syncStatus = null
                    scope.launch {
                        try {
                            val source = OneDriveKeePassFileSource(
                                authTokenProvider = { oneDriveManager.tokenProvider() },
                                remotePath = "/bastion/${db.file.name}"
                            )
                            when (val result = syncEngine.download(
                                source = source,
                                localFile = db.file,
                                password = db.password,
                                keyFileBytes = db.keyFileBytes
                            )) {
                                is OneDriveDownloadResult.Success -> {
                                    opened = result.opened
                                    pendingUploadVersion = result.remoteEtag
                                    selectedGroupPath = "Root"
                                    refresh(result.opened)
                                    syncStatus = "下载成功：${result.bytesDownloaded} 字节"
                                }
                                is OneDriveDownloadResult.Error -> {
                                    syncStatus = "下载失败：${result.message}"
                                }
                                OneDriveDownloadResult.RemoteMissing -> {
                                    syncStatus = "远端文件不存在"
                                }
                            }
                        } catch (e: Exception) {
                            syncStatus = "下载失败：${e.message}"
                        } finally {
                            oneDriveBusy = false
                        }
                    }
                },
                onLogout = {
                    oneDriveManager.clearSession()
                    oneDriveEmail = null
                    pendingUploadVersion = null
                    syncStatus = "已登出 OneDrive"
                }
            )
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(db.file.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "分组：$selectedGroupPath · ${entries.size} 个条目",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(onClick = {
                    editingEntry = KeePassEntryData()
                }) {
                    Text("新建条目")
                }
            }
            Spacer(Modifier.height(8.dp))

            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("此分组下没有条目", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(entries, key = { it.uuid }) { entry ->
                        Row(
                            Modifier.fillMaxWidth().clickable { editingEntry = entry }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.title.ifBlank { "未命名" }, style = MaterialTheme.typography.titleMedium)
                                if (entry.username.isNotBlank()) {
                                    Text(entry.username, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            TextButton(onClick = { editingEntry = entry }) { Text("编辑") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    // 条目编辑对话框
    editingEntry?.let { entry ->
        EntryEditDialog(
            entry = entry,
            onDismiss = { editingEntry = null },
            onSave = { updated ->
                try {
                    service.saveEntry(db, updated, selectedGroupPath)
                    refresh(db)
                    editingEntry = null
                } catch (e: Exception) {
                    errorMessage = e.message
                }
            },
            onDelete = {
                try {
                    service.deleteEntry(db, entry.uuid)
                    refresh(db)
                    editingEntry = null
                } catch (e: Exception) {
                    errorMessage = e.message
                }
            }
        )
    }

    // 新建分组对话框
    if (showNewGroupDialog) {
        AlertDialog(
            onDismissRequest = { showNewGroupDialog = false },
            title = { Text("新建分组") },
            text = {
                OutlinedTextField(
                    value = newGroupName,
                    onValueChange = { newGroupName = it },
                    label = { Text("分组名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    val name = newGroupName.trim()
                    if (name.isNotBlank()) {
                        try {
                            service.addGroup(db, selectedGroupPath, name)
                            refresh(db)
                            showNewGroupDialog = false
                        } catch (e: Exception) {
                            errorMessage = e.message
                        }
                    }
                }) {
                    Text("创建")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewGroupDialog = false }) { Text("取消") }
            }
        )
    }

    // OneDrive 同步冲突对话框
    if (showConflictDialog) {
        AlertDialog(
            onDismissRequest = { showConflictDialog = false },
            title = { Text("OneDrive 同步冲突") },
            text = {
                Text("远端文件已被其他设备修改。强制覆盖会丢弃远端的更改，确定继续吗？")
            },
            confirmButton = {
                Button(onClick = { forceUpload(db) }) {
                    Text("强制覆盖")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConflictDialog = false }) { Text("取消") }
            }
        )
    }
}

/**
 * OneDrive 同步卡片：登录 / 上传 / 下载 / 登出。
 */
@Composable
private fun OneDriveSyncCard(
    isLoggedIn: Boolean,
    email: String?,
    busy: Boolean,
    status: String?,
    onLogin: () -> Unit,
    onUpload: () -> Unit,
    onDownload: () -> Unit,
    onLogout: () -> Unit
) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("OneDrive 云同步", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (isLoggedIn) "已连接：${email ?: "OneDrive 账号"}"
                        else "未连接 OneDrive",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!isLoggedIn) {
                    Button(onClick = onLogin, enabled = !busy) {
                        Text(if (busy) "等待授权…" else "登录 OneDrive")
                    }
                } else {
                    Button(onClick = onUpload, enabled = !busy) {
                        Text("上传到 OneDrive")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onDownload, enabled = !busy) {
                        Text("从 OneDrive 下载")
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onLogout) { Text("登出") }
                }
            }
            status?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * KDBX 条目编辑对话框。
 */
@Composable
private fun EntryEditDialog(
    entry: KeePassEntryData,
    onDismiss: () -> Unit,
    onSave: (KeePassEntryData) -> Unit,
    onDelete: () -> Unit
) {
    var title by remember { mutableStateOf(entry.title) }
    var username by remember { mutableStateOf(entry.username) }
    var password by remember { mutableStateOf(entry.password) }
    var url by remember { mutableStateOf(entry.url) }
    var notes by remember { mutableStateOf(entry.notes) }
    var showPassword by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entry.uuid.isBlank()) "新建条目" else "编辑条目") },
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
                        androidx.compose.ui.text.input.PasswordVisualTransformation()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = showPassword, onCheckedChange = { showPassword = it })
                    Text("显示密码")
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
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
                if (entry.uuid.isNotBlank()) {
                    TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    onSave(
                        entry.copy(
                            title = title,
                            username = username,
                            password = password,
                            url = url,
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
