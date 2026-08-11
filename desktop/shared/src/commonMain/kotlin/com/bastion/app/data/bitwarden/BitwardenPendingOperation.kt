package com.bastion.app.data.bitwarden

/**
 * Bitwarden 待同步操作（桌面版精简模型）。
 * 离线编辑时先写入队列，联网后逐个执行。
 */
data class BitwardenPendingOperation(
    val id: Long = 0,
    val vaultId: Long,
    val cipherId: String,
    val operationType: OperationType = OperationType.UPDATE,
    val payloadJson: String = "",      // 操作所需的数据（如创建请求 JSON）
    val status: Status = Status.PENDING,
    val errorCount: Int = 0,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    enum class OperationType { CREATE, UPDATE, DELETE, RESTORE }

    enum class Status { PENDING, IN_PROGRESS, COMPLETED, FAILED }
}
