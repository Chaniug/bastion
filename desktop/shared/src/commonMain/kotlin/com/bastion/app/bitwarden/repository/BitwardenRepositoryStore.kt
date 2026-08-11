package com.bastion.app.bitwarden.repository

import com.bastion.app.data.PasswordEntry
import com.bastion.app.data.bitwarden.BitwardenConflictBackup
import com.bastion.app.data.bitwarden.BitwardenFolder
import com.bastion.app.data.bitwarden.BitwardenPendingOperation
import com.bastion.app.data.bitwarden.BitwardenVault
import com.bastion.app.security.DesktopCryptoManager
import kotlinx.coroutines.flow.Flow

/**
 * Bitwarden 仓储存储层抽象。
 * Phase 0/1 用内存实现；Phase 3 换 SQLDelight。
 */
interface BitwardenRepositoryStore {

    // ===== Vault =====
    suspend fun getAllVaults(): List<BitwardenVault>
    fun observeAllVaults(): Flow<List<BitwardenVault>>
    suspend fun getActiveVault(): BitwardenVault?
    suspend fun getVaultById(id: Long): BitwardenVault?
    suspend fun getVaultByEmail(email: String): BitwardenVault?
    fun setActiveVault(vaultId: Long)
    suspend fun upsertVault(vault: BitwardenVault): BitwardenVault
    suspend fun deleteVault(vaultId: Long): Boolean
    suspend fun markUnlocked(vaultId: Long)
    suspend fun markLocked(vaultId: Long)
    suspend fun markAllLocked()
    suspend fun markSynced(vaultId: Long)

    // ===== 加密令牌解密 =====
    /** 用内存缓存的对称密钥解密 vault 中加密的 access token。 */
    fun decryptAccessToken(vault: BitwardenVault, cryptoManager: DesktopCryptoManager): String?

    // ===== 条目 (PasswordEntry) =====
    suspend fun getEntriesByVault(vaultId: Long): List<PasswordEntry>
    fun observeEntriesByVault(vaultId: Long): Flow<List<PasswordEntry>>
    suspend fun getEntryById(id: Long): PasswordEntry?
    suspend fun getEntryByCipherId(vaultId: Long, cipherId: String): PasswordEntry?
    suspend fun getLocalModifiedEntries(vaultId: Long): List<PasswordEntry>
    suspend fun insertEntry(entry: PasswordEntry): PasswordEntry
    suspend fun updateEntry(entry: PasswordEntry)
    suspend fun deleteEntryByCipherId(vaultId: Long, cipherId: String)
    suspend fun deleteEntriesNotIn(vaultId: Long, keepCipherIds: List<String>)
    suspend fun countEntries(vaultId: Long): Int

    // ===== 文件夹 =====
    suspend fun getFolderByBitwardenId(vaultId: Long, folderId: String): BitwardenFolder?
    suspend fun upsertFolder(folder: BitwardenFolder)
    suspend fun getFoldersByVault(vaultId: Long): List<BitwardenFolder>

    // ===== 冲突备份 =====
    suspend fun insertConflictBackup(backup: BitwardenConflictBackup)
    suspend fun getConflictBackups(vaultId: Long): List<BitwardenConflictBackup>
    suspend fun markConflictResolved(backupId: Long)

    // ===== 待同步队列 =====
    suspend fun insertPendingOperation(op: BitwardenPendingOperation)
    suspend fun getRunnablePendingOperations(vaultId: Long): List<BitwardenPendingOperation>
    suspend fun updatePendingOperation(op: BitwardenPendingOperation)
    suspend fun markPendingCompleted(opId: Long)

    // ===== 偏好 =====
    fun loadBoolean(key: String, default: Boolean): Boolean
    fun saveBoolean(key: String, value: Boolean)
    fun loadLong(key: String, default: Long): Long
    fun saveLong(key: String, value: Long)
}
