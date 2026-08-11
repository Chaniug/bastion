package com.bastion.app.data.bitwarden

/**
 * Bitwarden 冲突备份（桌面版精简模型）。
 * 同步时若发现本地修改与服务器版本冲突，把服务器版本快照存为备份，避免数据丢失。
 */
data class BitwardenConflictBackup(
    val id: Long = 0,
    val vaultId: Long,
    val entryId: Long? = null,           // 关联本地 PasswordEntry id
    val cipherId: String,
    val conflictType: String = TYPE_CONCURRENT_EDIT,
    val serverSnapshotJson: String = "", // 服务器版本的 Cipher JSON 快照
    val localSnapshotJson: String = "",  // 本地版本的 JSON 快照
    val resolved: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_CONCURRENT_EDIT = "CONCURRENT_EDIT"
        const val TYPE_DELETE_EDIT = "DELETE_EDIT"
    }
}
