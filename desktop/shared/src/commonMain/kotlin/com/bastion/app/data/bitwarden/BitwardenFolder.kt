package com.bastion.app.data.bitwarden

/**
 * Bitwarden 文件夹（桌面版精简模型）。
 */
data class BitwardenFolder(
    val id: Long = 0,
    val vaultId: Long,
    val bitwardenFolderId: String,
    val name: String = "",
    val encryptedName: String? = null,
    val revisionDate: String = ""
)
