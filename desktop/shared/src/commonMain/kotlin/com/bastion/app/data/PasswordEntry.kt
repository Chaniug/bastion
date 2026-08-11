package com.bastion.app.data

import kotlinx.serialization.Serializable

/**
 * 密码条目（桌面版精简模型）。
 *
 * 与安卓版 PasswordEntry 对齐核心字段，但只保留桌面端三类功能需要的部分：
 * Login 类型条目的展示/编辑/Bitwarden 绑定/冲突处理。SSH/passkey/卡片等安卓特化字段不移植。
 */
@Serializable
data class PasswordEntry(
    val id: Long = 0,

    // === 基础字段 ===
    val title: String = "",
    val website: String = "",
    val username: String = "",
    val password: String = "",        // 密文（Bitwarden 场景：加密后的密码串）
    val notes: String = "",

    // === 展示辅助 ===
    val isFavorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),

    // === Bitwarden 关联 ===
    val bitwardenVaultId: Long? = null,
    val bitwardenCipherId: String? = null,
    val bitwardenFolderId: String? = null,
    val bitwardenRevisionDate: String? = null,
    val bitwardenLocalModified: Boolean = false,

    // === 本地 KDBX 关联 ===
    val keepassDatabaseId: Long? = null,
    val keepassEntryUuid: String? = null,
    val keepassGroupUuid: String? = null
) {
    val isBitwardenEntry: Boolean get() = bitwardenCipherId != null
    val isKeePassEntry: Boolean get() = keepassEntryUuid != null
}
