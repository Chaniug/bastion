package com.bastion.app.bitwarden.repository

import com.bastion.app.data.PasswordEntry
import com.bastion.app.data.bitwarden.BitwardenConflictBackup
import com.bastion.app.data.bitwarden.BitwardenFolder
import com.bastion.app.data.bitwarden.BitwardenPendingOperation
import com.bastion.app.data.bitwarden.BitwardenVault
import com.bastion.app.security.DesktopCryptoManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 内存版 Bitwarden 仓储存储（Phase 1 占位）。
 * Phase 3 由 SQLDelight 实现替换。
 */
class InMemoryBitwardenRepositoryStore : BitwardenRepositoryStore {

    private val vaults = LinkedHashMap<Long, BitwardenVault>()
    private val vaultFlow = MutableStateFlow<List<BitwardenVault>>(emptyList())
    private var nextId = 1L
    private var activeVaultId: Long? = null

    private val entries = LinkedHashMap<Long, PasswordEntry>()
    private val folders = mutableListOf<BitwardenFolder>()
    private val conflictBackups = mutableListOf<BitwardenConflictBackup>()
    private val pendingOps = mutableListOf<BitwardenPendingOperation>()

    private val prefs = mutableMapOf<String, Any>()

    // ===== Vault =====
    override suspend fun getAllVaults(): List<BitwardenVault> = vaultFlow.value

    override fun observeAllVaults(): Flow<List<BitwardenVault>> = vaultFlow

    override suspend fun getActiveVault(): BitwardenVault? {
        val id = activeVaultId ?: vaults.keys.firstOrNull() ?: return null
        return vaults[id]
    }

    override suspend fun getVaultById(id: Long): BitwardenVault? = vaults[id]

    override suspend fun getVaultByEmail(email: String): BitwardenVault? =
        vaults.values.firstOrNull { it.email.equals(email, ignoreCase = true) }

    override fun setActiveVault(vaultId: Long) {
        activeVaultId = vaultId
    }

    override suspend fun upsertVault(vault: BitwardenVault): BitwardenVault {
        val stored = if (vault.id == 0L) {
            vault.copy(
                id = nextId++,
                updatedAt = System.currentTimeMillis()
            )
        } else {
            vault.copy(updatedAt = System.currentTimeMillis())
        }
        vaults[stored.id] = stored
        if (activeVaultId == null) activeVaultId = stored.id
        publish()
        return stored
    }

    override suspend fun deleteVault(vaultId: Long): Boolean {
        val removed = vaults.remove(vaultId) != null
        if (activeVaultId == vaultId) activeVaultId = vaults.keys.firstOrNull()
        entries.keys.filter { entries[it]?.bitwardenVaultId == vaultId }.forEach { entries.remove(it) }
        publishEntries(vaultId)
        publish()
        return removed
    }

    override suspend fun markUnlocked(vaultId: Long) {
        vaults[vaultId]?.let { vaults[vaultId] = it.copy(isLocked = false, updatedAt = System.currentTimeMillis()) }
    }

    override suspend fun markLocked(vaultId: Long) {
        vaults[vaultId]?.let { vaults[vaultId] = it.copy(isLocked = true, updatedAt = System.currentTimeMillis()) }
    }

    override suspend fun markAllLocked() {
        vaults.keys.forEach { id ->
            vaults[id] = vaults[id]!!.copy(isLocked = true, updatedAt = System.currentTimeMillis())
        }
    }

    override suspend fun markSynced(vaultId: Long) {
        vaults[vaultId]?.let {
            vaults[vaultId] = it.copy(lastSyncAt = System.currentTimeMillis(), lastFullSyncAt = System.currentTimeMillis())
        }
    }

    // ===== 加密令牌解密 =====
    override fun decryptAccessToken(vault: BitwardenVault, cryptoManager: DesktopCryptoManager): String? {
        val encrypted = vault.encryptedAccessToken ?: return null
        return runCatching { cryptoManager.decryptString(encrypted) }.getOrNull()
    }

    // ===== 条目 =====
    override suspend fun getEntriesByVault(vaultId: Long): List<PasswordEntry> =
        entries.values.filter { it.bitwardenVaultId == vaultId }.sortedByDescending { it.updatedAt }

    override fun observeEntriesByVault(vaultId: Long): Flow<List<PasswordEntry>> {
        return entryFlows.getOrPut(vaultId) {
            MutableStateFlow(getEntriesSync(vaultId))
        }
    }

    private val entryFlows = mutableMapOf<Long, MutableStateFlow<List<PasswordEntry>>>()

    private fun getEntriesSync(vaultId: Long): List<PasswordEntry> =
        entries.values.filter { it.bitwardenVaultId == vaultId }.sortedByDescending { it.updatedAt }

    override suspend fun getEntryById(id: Long): PasswordEntry? = entries[id]

    override suspend fun getEntryByCipherId(vaultId: Long, cipherId: String): PasswordEntry? =
        entries.values.firstOrNull { it.bitwardenVaultId == vaultId && it.bitwardenCipherId == cipherId }

    override suspend fun getLocalModifiedEntries(vaultId: Long): List<PasswordEntry> =
        entries.values.filter { it.bitwardenVaultId == vaultId && it.bitwardenLocalModified }

    override suspend fun insertEntry(entry: PasswordEntry): PasswordEntry {
        val stored = entry.copy(id = if (entry.id == 0L) nextId++ else entry.id)
        entries[stored.id] = stored
        publishEntries(stored.bitwardenVaultId ?: 0L)
        return stored
    }

    override suspend fun updateEntry(entry: PasswordEntry) {
        entries[entry.id] = entry.copy(updatedAt = System.currentTimeMillis())
        publishEntries(entry.bitwardenVaultId ?: 0L)
    }

    override suspend fun deleteEntryByCipherId(vaultId: Long, cipherId: String) {
        entries.keys.filter { id ->
            val e = entries[id]
            e?.bitwardenVaultId == vaultId && e.bitwardenCipherId == cipherId
        }.forEach { entries.remove(it) }
        publishEntries(vaultId)
    }

    override suspend fun deleteEntriesNotIn(vaultId: Long, keepCipherIds: List<String>) {
        entries.keys.filter { id ->
            val e = entries[id]
            e?.bitwardenVaultId == vaultId && e.bitwardenCipherId != null &&
                e.bitwardenCipherId !in keepCipherIds && !e.bitwardenLocalModified
        }.forEach { entries.remove(it) }
        publishEntries(vaultId)
    }

    override suspend fun countEntries(vaultId: Long): Int =
        entries.values.count { it.bitwardenVaultId == vaultId }

    // ===== 文件夹 =====
    override suspend fun getFolderByBitwardenId(vaultId: Long, folderId: String): BitwardenFolder? =
        folders.firstOrNull { it.vaultId == vaultId && it.bitwardenFolderId == folderId }

    override suspend fun upsertFolder(folder: BitwardenFolder) {
        val idx = folders.indexOfFirst { it.vaultId == folder.vaultId && it.bitwardenFolderId == folder.bitwardenFolderId }
        if (idx >= 0) folders[idx] = folder else folders.add(folder)
    }

    override suspend fun getFoldersByVault(vaultId: Long): List<BitwardenFolder> =
        folders.filter { it.vaultId == vaultId }

    // ===== 冲突备份 =====
    override suspend fun insertConflictBackup(backup: BitwardenConflictBackup) {
        conflictBackups.add(backup.copy(id = nextId++))
    }

    override suspend fun getConflictBackups(vaultId: Long): List<BitwardenConflictBackup> =
        conflictBackups.filter { it.vaultId == vaultId && !it.resolved }

    override suspend fun markConflictResolved(backupId: Long) {
        val idx = conflictBackups.indexOfFirst { it.id == backupId }
        if (idx >= 0) conflictBackups[idx] = conflictBackups[idx].copy(resolved = true)
    }

    // ===== 待同步队列 =====
    override suspend fun insertPendingOperation(op: BitwardenPendingOperation) {
        pendingOps.add(op.copy(id = nextId++))
    }

    override suspend fun getRunnablePendingOperations(vaultId: Long): List<BitwardenPendingOperation> =
        pendingOps.filter { it.vaultId == vaultId && it.status == BitwardenPendingOperation.Status.PENDING }

    override suspend fun updatePendingOperation(op: BitwardenPendingOperation) {
        val idx = pendingOps.indexOfFirst { it.id == op.id }
        if (idx >= 0) pendingOps[idx] = op
    }

    override suspend fun markPendingCompleted(opId: Long) {
        val idx = pendingOps.indexOfFirst { it.id == opId }
        if (idx >= 0) pendingOps[idx] = pendingOps[idx].copy(status = BitwardenPendingOperation.Status.COMPLETED)
    }

    // ===== 偏好 =====
    override fun loadBoolean(key: String, default: Boolean): Boolean = prefs[key] as? Boolean ?: default
    override fun saveBoolean(key: String, value: Boolean) { prefs[key] = value }
    override fun loadLong(key: String, default: Long): Long = (prefs[key] as? Number)?.toLong() ?: default
    override fun saveLong(key: String, value: Long) { prefs[key] = value }

    private fun publishEntries(vaultId: Long) {
        if (vaultId <= 0L) return
        val flow = entryFlows.getOrPut(vaultId) { MutableStateFlow(getEntriesSync(vaultId)) }
        flow.value = getEntriesSync(vaultId)
    }

    private fun publish() {
        vaultFlow.value = vaults.values.toList()
    }
}
