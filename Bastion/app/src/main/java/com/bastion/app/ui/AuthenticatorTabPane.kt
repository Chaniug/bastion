package com.bastion.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bastion.app.ui.common.layout.DetailPane
import com.bastion.app.ui.common.layout.ListPane
import com.bastion.app.ui.screens.AddEditTotpScreen
import com.bastion.app.viewmodel.PasswordViewModel

@Composable
internal fun AuthenticatorTabPane(
    isCompactWidth: Boolean,
    wideListPaneWidth: Dp,
    totpViewModel: com.bastion.app.viewmodel.TotpViewModel,
    passwordViewModel: PasswordViewModel,
    localKeePassViewModel: com.bastion.app.viewmodel.LocalKeePassViewModel,
    onTotpOpen: (Long) -> Unit,
    onNavigateToQuickTotpScan: () -> Unit,
    onSelectionModeChange: (
        Boolean,
        Int,
        () -> Unit,
        () -> Unit,
        () -> Unit,
        () -> Unit
    ) -> Unit,
    isAddingTotpInline: Boolean,
    selectedTotpId: Long?,
    totpNewItemDefaults: NewItemStorageDefaults,
    onInlineTotpEditorBack: () -> Unit,
    showStandaloneSettingsEntry: Boolean,
    onOpenStandaloneSettings: () -> Unit,
    onNavigateToPasskeys: () -> Unit = {}
) {
    val listPaneContent: @Composable ColumnScope.() -> Unit = {
        TotpListContent(
            viewModel = totpViewModel,
            passwordViewModel = passwordViewModel,
            onTotpClick = onTotpOpen,
            onDeleteTotp = { totp ->
                totpViewModel.deleteTotpItem(totp)
            },
            onQuickScanTotp = onNavigateToQuickTotpScan,
            onSelectionModeChange = onSelectionModeChange,
            showStandaloneSettingsEntry = showStandaloneSettingsEntry,
            onOpenStandaloneSettings = onOpenStandaloneSettings,
            onNavigateToPasskeys = onNavigateToPasskeys
        )
    }

    if (isCompactWidth) {
        ListPane(
            modifier = Modifier.fillMaxSize(),
            content = listPaneContent
        )
    } else {
        val totpItems by totpViewModel.totpItems.collectAsState()
        val selectedTotpItem = remember(selectedTotpId, totpItems) {
            selectedTotpId?.let { selectedId ->
                totpItems.firstOrNull { it.id == selectedId }
            }
        }
        val parsedTotpItems by totpViewModel.parsedTotpItems.collectAsState()
        val selectedTotpData = remember(selectedTotpId, parsedTotpItems) {
            parsedTotpItems.firstOrNull { it.item.id == selectedTotpId }?.totpData
        }
        val totpCategories by totpViewModel.categories.collectAsState()

        Row(modifier = Modifier.fillMaxSize()) {
            ListPane(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(wideListPaneWidth),
                content = listPaneContent
            )
            DetailPane(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                if (isAddingTotpInline) {
                    AddEditTotpScreen(
                        totpId = null,
                        initialData = null,
                        initialTitle = "",
                        initialNotes = "",
                        initialCategoryId = totpNewItemDefaults.categoryId,
                        initialKeePassDatabaseId = totpNewItemDefaults.keepassDatabaseId,
                        initialKeePassGroupPath = totpNewItemDefaults.keepassGroupPath,
                        initialBitwardenVaultId = totpNewItemDefaults.bitwardenVaultId,
                        initialBitwardenFolderId = totpNewItemDefaults.bitwardenFolderId,
                        initialIsFavorite = false,
                        categories = totpCategories,
                        passwordViewModel = passwordViewModel,
                        totpViewModel = totpViewModel,
                        localKeePassViewModel = localKeePassViewModel,
                        onSave = { title, notes, totpData, isFavorite, targets, onComplete ->
                            // 与编辑分支同理：若新建时就指定了绑定密码条目，走绑定型保存，
                            // 避免被当成独立条目写入 Bitwarden 而新建一条 otpauth:// cipher。
                            val boundPasswordId = totpData.boundPasswordId
                            if (boundPasswordId != null && boundPasswordId > 0L) {
                                totpViewModel.savePasswordBoundTotp(
                                    passwordId = boundPasswordId,
                                    title = title,
                                    notes = notes,
                                    totpData = totpData,
                                    isFavorite = isFavorite,
                                    preferredTotpId = null,
                                    onComplete = { saved ->
                                        if (saved) {
                                            onInlineTotpEditorBack()
                                        }
                                        onComplete(saved)
                                    }
                                )
                            } else {
                                totpViewModel.saveTotpAcrossTargets(
                                    id = null,
                                    title = title,
                                    notes = notes,
                                    totpData = totpData,
                                    isFavorite = isFavorite,
                                    targets = targets,
                                    onComplete = { saved ->
                                        if (saved) {
                                            totpViewModel.revealSavedTotpTargets(targets)
                                            onInlineTotpEditorBack()
                                        }
                                        onComplete(saved)
                                    }
                                )
                            }
                        },
                        onNavigateBack = onInlineTotpEditorBack,
                        onScanQrCode = onNavigateToQuickTotpScan,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (selectedTotpId == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Select an item to view details",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (selectedTotpItem == null || selectedTotpItem.id <= 0L || selectedTotpData == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "This item is not available for inline editing",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    AddEditTotpScreen(
                        totpId = selectedTotpItem.id,
                        initialData = selectedTotpData,
                        initialTitle = selectedTotpItem.title,
                        initialNotes = selectedTotpItem.notes,
                        initialCategoryId = selectedTotpData.categoryId,
                        initialKeePassDatabaseId = selectedTotpItem.keepassDatabaseId,
                        initialKeePassGroupPath = selectedTotpItem.keepassGroupPath,
                        initialBitwardenVaultId = selectedTotpItem.bitwardenVaultId,
                        initialBitwardenFolderId = selectedTotpItem.bitwardenFolderId,
                        initialReplicaGroupId = selectedTotpItem.replicaGroupId,
                        initialIsFavorite = selectedTotpItem.isFavorite,
                        categories = totpCategories,
                        passwordViewModel = passwordViewModel,
                        totpViewModel = totpViewModel,
                        localKeePassViewModel = localKeePassViewModel,
                        onSave = { title, notes, totpData, isFavorite, targets, onComplete ->
                            // 绑定型验证器（依附于某条密码条目）必须走 savePasswordBoundTotp，
                            // 让它跟随密码条目存储、不占用自己的 Bitwarden 存储位。
                            // 若误走 saveTotpAcrossTargets，它会以「独立条目」身份写入 Bitwarden 目标
                            // （followBoundPasswordStorage=false + 分配 bitwardenVaultId），
                            // 进而在服务器新建一条 website=otpauth://totp/{issuer} 的独立 cipher，
                            // 导致本地与远端各多出一条重复数据。
                            val boundPasswordId = totpData.boundPasswordId
                            if (boundPasswordId != null && boundPasswordId > 0L) {
                                totpViewModel.savePasswordBoundTotp(
                                    passwordId = boundPasswordId,
                                    title = title,
                                    notes = notes,
                                    totpData = totpData,
                                    isFavorite = isFavorite,
                                    preferredTotpId = selectedTotpItem.id,
                                    onComplete = onComplete
                                )
                            } else {
                                totpViewModel.saveTotpAcrossTargets(
                                    id = selectedTotpItem.id,
                                    title = title,
                                    notes = notes,
                                    totpData = totpData,
                                    isFavorite = isFavorite,
                                    targets = targets,
                                    onComplete = { saved ->
                                        if (saved) {
                                            totpViewModel.revealSavedTotpTargets(targets)
                                        }
                                        onComplete(saved)
                                    }
                                )
                            }
                        },
                        onNavigateBack = onInlineTotpEditorBack,
                        onScanQrCode = onNavigateToQuickTotpScan,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
