package com.bastion.app.bitwarden.repository

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.bastion.app.data.PasswordEntry
import kotlinx.coroutines.Dispatchers
import com.bastion.app.data.bitwarden.BitwardenConflictBackup
import com.bastion.app.data.bitwarden.BitwardenFolder
import com.bastion.app.data.bitwarden.BitwardenPendingOperation
import com.bastion.app.data.bitwarden.BitwardenVault
import com.bastion.app.db.BastionDatabase
import com.bastion.app.db.Bitwarden_conflict_backups
import com.bastion.app.db.Bitwarden_folders
import com.bastion.app.db.Bitwarden_pending_operations
import com.bastion.app.db.Bitwarden_vaults
import com.bastion.app.db.Password_entries
import com.bastion.app.security.DesktopCryptoManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Phase 3 SQLDelight 实现的 Bitwarden 仓储存储层，替换 [InMemoryBitwardenRepositoryStore]。
 *
 * - 所有持久化数据落在 [BastionDatabase]（9 张表）。
 * - Vault 的 active 标记通过 preferences 表（key=active_vault_id）持久化。
 * - observe* 方法基于 SQLDelight coroutines 扩展返回实时 [Flow]。
 */
class SqlDelightBitwardenRepositoryStore(
    database: BastionDatabase,
    private val driver: SqlDriver,
    private val cryptoManager: DesktopCryptoManager
) : BitwardenRepositoryStore {

    private val db = database
    private val activeVaultKey = "active_vault_id"

    // ===== Vault =====
    override suspend fun getAllVaults(): List<BitwardenVault> =
        db.bitwardenVaultsQueries.selectAll().executeAsList().map(::toVault)

    override fun observeAllVaults(): Flow<List<BitwardenVault>> =
        db.bitwardenVaultsQueries.selectAll().asFlow().mapToList(Dispatchers.IO).map { it.map(::toVault) }

    override suspend fun getActiveVault(): BitwardenVault? {
        val activeId = getActiveVaultId()
        if (activeId != null) return getVaultById(activeId)
        return db.bitwardenVaultsQueries.selectAll().executeAsList().firstOrNull()?.let(::toVault)
    }

    override suspend fun getVaultById(id: Long): BitwardenVault? =
        db.bitwardenVaultsQueries.selectById(id).executeAsOneOrNull()?.let(::toVault)

    override suspend fun getVaultByEmail(email: String): BitwardenVault? =
        db.bitwardenVaultsQueries.selectByEmail(email).executeAsOneOrNull()?.let(::toVault)

    override fun setActiveVault(vaultId: Long) {
        db.preferencesQueries.upsertLong(key = activeVaultKey, long_value = vaultId)
    }

    override suspend fun upsertVault(vault: BitwardenVault): BitwardenVault {
        val now = System.currentTimeMillis()
        val finalVault = if (vault.id == 0L) {
            vault.copy(id = nextId("bitwarden_vaults"), updatedAt = now)
        } else {
            vault.copy(updatedAt = now)
        }
        val existing = db.bitwardenVaultsQueries.selectById(finalVault.id).executeAsOneOrNull()
        if (existing == null) {
            db.bitwardenVaultsQueries.insert(
                id = finalVault.id,
                email = finalVault.email,
                canonical_email = finalVault.canonicalEmail,
                user_id = finalVault.userId,
                account_key = finalVault.accountKey,
                display_name = finalVault.displayName,
                server_url = finalVault.serverUrl,
                identity_url = finalVault.identityUrl,
                api_url = finalVault.apiUrl,
                events_url = finalVault.eventsUrl,
                tls_certificate_alias = finalVault.tlsCertificateAlias,
                tls_ca_certificate_pem = finalVault.tlsCaCertificatePem,
                tls_mtls_enabled = if (finalVault.tlsMtlsEnabled) 1 else 0,
                tls_client_cert_pkcs12_base64 = finalVault.tlsClientCertPkcs12Base64,
                tls_encrypted_client_cert_password = finalVault.tlsEncryptedClientCertPassword,
                encrypted_access_token = finalVault.encryptedAccessToken,
                encrypted_refresh_token = finalVault.encryptedRefreshToken,
                access_token_expires_at = finalVault.accessTokenExpiresAt,
                encrypted_master_key = finalVault.encryptedMasterKey,
                encrypted_enc_key = finalVault.encryptedEncKey,
                encrypted_mac_key = finalVault.encryptedMacKey,
                kdf_type = finalVault.kdfType.toLong(),
                kdf_iterations = finalVault.kdfIterations.toLong(),
                kdf_memory = finalVault.kdfMemory?.toLong(),
                kdf_parallelism = finalVault.kdfParallelism?.toLong(),
                last_sync_at = finalVault.lastSyncAt,
                last_full_sync_at = finalVault.lastFullSyncAt,
                revision_date = finalVault.revisionDate,
                is_default = if (finalVault.isDefault) 1 else 0,
                is_locked = if (finalVault.isLocked) 1 else 0,
                is_connected = if (finalVault.isConnected) 1 else 0,
                sync_enabled = if (finalVault.syncEnabled) 1 else 0,
                created_at = finalVault.createdAt,
                updated_at = finalVault.updatedAt
            )
        } else {
            db.bitwardenVaultsQueries.update(
                email = finalVault.email,
                canonical_email = finalVault.canonicalEmail,
                user_id = finalVault.userId,
                account_key = finalVault.accountKey,
                display_name = finalVault.displayName,
                server_url = finalVault.serverUrl,
                identity_url = finalVault.identityUrl,
                api_url = finalVault.apiUrl,
                events_url = finalVault.eventsUrl,
                tls_certificate_alias = finalVault.tlsCertificateAlias,
                tls_ca_certificate_pem = finalVault.tlsCaCertificatePem,
                tls_mtls_enabled = if (finalVault.tlsMtlsEnabled) 1 else 0,
                tls_client_cert_pkcs12_base64 = finalVault.tlsClientCertPkcs12Base64,
                tls_encrypted_client_cert_password = finalVault.tlsEncryptedClientCertPassword,
                encrypted_access_token = finalVault.encryptedAccessToken,
                encrypted_refresh_token = finalVault.encryptedRefreshToken,
                access_token_expires_at = finalVault.accessTokenExpiresAt,
                encrypted_master_key = finalVault.encryptedMasterKey,
                encrypted_enc_key = finalVault.encryptedEncKey,
                encrypted_mac_key = finalVault.encryptedMacKey,
                kdf_type = finalVault.kdfType.toLong(),
                kdf_iterations = finalVault.kdfIterations.toLong(),
                kdf_memory = finalVault.kdfMemory?.toLong(),
                kdf_parallelism = finalVault.kdfParallelism?.toLong(),
                last_sync_at = finalVault.lastSyncAt,
                last_full_sync_at = finalVault.lastFullSyncAt,
                revision_date = finalVault.revisionDate,
                is_default = if (finalVault.isDefault) 1 else 0,
                is_locked = if (finalVault.isLocked) 1 else 0,
                is_connected = if (finalVault.isConnected) 1 else 0,
                sync_enabled = if (finalVault.syncEnabled) 1 else 0,
                created_at = finalVault.createdAt,
                updated_at = finalVault.updatedAt,
                id = finalVault.id
            )
        }
        if (getActiveVaultId() == null) setActiveVault(finalVault.id)
        return finalVault
    }

    override suspend fun deleteVault(vaultId: Long): Boolean {
        db.passwordEntriesQueries.deleteAllByVault(vaultId)
        db.bitwardenVaultsQueries.deleteById(vaultId)
        if (getActiveVaultId() == vaultId) db.preferencesQueries.deleteByKey(activeVaultKey)
        return true
    }

    override suspend fun markUnlocked(vaultId: Long) {
        db.bitwardenVaultsQueries.setLocked(is_locked = 0, updated_at = System.currentTimeMillis(), id = vaultId)
    }

    override suspend fun markLocked(vaultId: Long) {
        db.bitwardenVaultsQueries.setLocked(is_locked = 1, updated_at = System.currentTimeMillis(), id = vaultId)
    }

    override suspend fun markAllLocked() {
        db.bitwardenVaultsQueries.setAllLocked(updated_at = System.currentTimeMillis())
    }

    override suspend fun markSynced(vaultId: Long) {
        val now = System.currentTimeMillis()
        db.bitwardenVaultsQueries.markSynced(
            last_sync_at = now,
            last_full_sync_at = now,
            updated_at = now,
            id = vaultId
        )
    }

    // ===== 加密令牌解密 =====
    override fun decryptAccessToken(vault: BitwardenVault, cryptoManager: DesktopCryptoManager): String? {
        val encrypted = vault.encryptedAccessToken ?: return null
        return runCatching { cryptoManager.decryptString(encrypted) }.getOrNull()
    }

    // ===== 条目 =====
    override suspend fun getEntriesByVault(vaultId: Long): List<PasswordEntry> =
        db.passwordEntriesQueries.selectAllByVault(vaultId).executeAsList().map(::toPasswordEntry)

    override fun observeEntriesByVault(vaultId: Long): Flow<List<PasswordEntry>> =
        db.passwordEntriesQueries.selectAllByVault(vaultId).asFlow().mapToList(Dispatchers.IO)
            .map { list -> list.map(::toPasswordEntry) }

    override suspend fun getEntryById(id: Long): PasswordEntry? =
        db.passwordEntriesQueries.selectById(id).executeAsOneOrNull()?.let(::toPasswordEntry)

    override suspend fun getEntryByCipherId(vaultId: Long, cipherId: String): PasswordEntry? =
        db.passwordEntriesQueries.selectByBitwardenCipher(bitwarden_cipher_id = cipherId, bitwarden_vault_id = vaultId)
            .executeAsOneOrNull()?.let(::toPasswordEntry)

    override suspend fun getLocalModifiedEntries(vaultId: Long): List<PasswordEntry> =
        db.passwordEntriesQueries.selectLocalModifiedByVault(vaultId).executeAsList().map(::toPasswordEntry)

    override suspend fun insertEntry(entry: PasswordEntry): PasswordEntry {
        val now = System.currentTimeMillis()
        val finalEntry = if (entry.id == 0L) {
            entry.copy(id = nextId("password_entries"), createdAt = now, updatedAt = now)
        } else {
            entry.copy(updatedAt = now)
        }
        val existing = db.passwordEntriesQueries.selectById(finalEntry.id).executeAsOneOrNull()
        if (existing == null) {
            db.passwordEntriesQueries.insert(
                id = finalEntry.id,
                title = finalEntry.title,
                website = finalEntry.website,
                username = finalEntry.username,
                encrypted_password = finalEntry.password,
                notes = finalEntry.notes,
                is_favorite = if (finalEntry.isFavorite) 1 else 0,
                soft_deleted = 0,
                created_at = finalEntry.createdAt,
                updated_at = finalEntry.updatedAt,
                bitwarden_vault_id = finalEntry.bitwardenVaultId,
                bitwarden_cipher_id = finalEntry.bitwardenCipherId,
                bitwarden_folder_id = finalEntry.bitwardenFolderId,
                bitwarden_revision_date = finalEntry.bitwardenRevisionDate,
                bitwarden_local_modified = if (finalEntry.bitwardenLocalModified) 1 else 0,
                keepass_database_id = finalEntry.keepassDatabaseId,
                keepass_entry_uuid = finalEntry.keepassEntryUuid,
                keepass_group_uuid = finalEntry.keepassGroupUuid
            )
        } else {
            db.passwordEntriesQueries.update(
                title = finalEntry.title,
                website = finalEntry.website,
                username = finalEntry.username,
                encrypted_password = finalEntry.password,
                notes = finalEntry.notes,
                is_favorite = if (finalEntry.isFavorite) 1 else 0,
                soft_deleted = 0,
                updated_at = finalEntry.updatedAt,
                bitwarden_vault_id = finalEntry.bitwardenVaultId,
                bitwarden_cipher_id = finalEntry.bitwardenCipherId,
                bitwarden_folder_id = finalEntry.bitwardenFolderId,
                bitwarden_revision_date = finalEntry.bitwardenRevisionDate,
                bitwarden_local_modified = if (finalEntry.bitwardenLocalModified) 1 else 0,
                keepass_database_id = finalEntry.keepassDatabaseId,
                keepass_entry_uuid = finalEntry.keepassEntryUuid,
                keepass_group_uuid = finalEntry.keepassGroupUuid,
                id = finalEntry.id
            )
        }
        return finalEntry
    }

    override suspend fun updateEntry(entry: PasswordEntry) {
        db.passwordEntriesQueries.update(
            title = entry.title,
            website = entry.website,
            username = entry.username,
            encrypted_password = entry.password,
            notes = entry.notes,
            is_favorite = if (entry.isFavorite) 1 else 0,
            soft_deleted = 0,
            updated_at = System.currentTimeMillis(),
            bitwarden_vault_id = entry.bitwardenVaultId,
            bitwarden_cipher_id = entry.bitwardenCipherId,
            bitwarden_folder_id = entry.bitwardenFolderId,
            bitwarden_revision_date = entry.bitwardenRevisionDate,
            bitwarden_local_modified = if (entry.bitwardenLocalModified) 1 else 0,
            keepass_database_id = entry.keepassDatabaseId,
            keepass_entry_uuid = entry.keepassEntryUuid,
            keepass_group_uuid = entry.keepassGroupUuid,
            id = entry.id
        )
    }

    override suspend fun deleteEntryByCipherId(vaultId: Long, cipherId: String) {
        db.passwordEntriesQueries.deleteByCipherId(bitwarden_vault_id = vaultId, bitwarden_cipher_id = cipherId)
    }

    override suspend fun deleteEntriesNotIn(vaultId: Long, keepCipherIds: List<String>) {
        if (keepCipherIds.isEmpty()) {
            db.passwordEntriesQueries.deleteAllBitwardenByVault(vaultId)
        } else {
            db.passwordEntriesQueries.deleteNotIn(bitwarden_vault_id = vaultId, bitwarden_cipher_id = keepCipherIds)
        }
    }

    override suspend fun countEntries(vaultId: Long): Int =
        db.passwordEntriesQueries.countByVault(vaultId).executeAsOne().toInt()

    // ===== 文件夹 =====
    override suspend fun getFolderByBitwardenId(vaultId: Long, folderId: String): BitwardenFolder? =
        db.bitwardenFoldersQueries.selectByBitwardenId(vault_id = vaultId, bitwarden_folder_id = folderId)
            .executeAsOneOrNull()?.let(::toFolder)

    override suspend fun upsertFolder(folder: BitwardenFolder) {
        val now = System.currentTimeMillis()
        val finalFolder = if (folder.id == 0L) folder.copy(id = nextId("bitwarden_folders")) else folder
        val existing = db.bitwardenFoldersQueries.selectById(finalFolder.id).executeAsOneOrNull()
        if (existing == null) {
            db.bitwardenFoldersQueries.insert(
                id = finalFolder.id,
                vault_id = finalFolder.vaultId,
                bitwarden_folder_id = finalFolder.bitwardenFolderId,
                name = finalFolder.name,
                encrypted_name = finalFolder.encryptedName,
                revision_date = finalFolder.revisionDate,
                created_at = now,
                updated_at = now
            )
        } else {
            db.bitwardenFoldersQueries.update(
                vault_id = finalFolder.vaultId,
                bitwarden_folder_id = finalFolder.bitwardenFolderId,
                name = finalFolder.name,
                encrypted_name = finalFolder.encryptedName,
                revision_date = finalFolder.revisionDate,
                updated_at = now,
                id = finalFolder.id
            )
        }
    }

    override suspend fun getFoldersByVault(vaultId: Long): List<BitwardenFolder> =
        db.bitwardenFoldersQueries.selectByVault(vaultId).executeAsList().map(::toFolder)

    // ===== 冲突备份 =====
    override suspend fun insertConflictBackup(backup: BitwardenConflictBackup) {
        val id = if (backup.id == 0L) nextId("bitwarden_conflict_backups") else backup.id
        db.bitwardenConflictBackupsQueries.insert(
            id = id,
            vault_id = backup.vaultId,
            entry_id = backup.entryId,
            cipher_id = backup.cipherId,
            conflict_type = backup.conflictType,
            server_snapshot_json = backup.serverSnapshotJson,
            local_snapshot_json = backup.localSnapshotJson,
            resolved = if (backup.resolved) 1 else 0,
            created_at = backup.createdAt
        )
    }

    override suspend fun getConflictBackups(vaultId: Long): List<BitwardenConflictBackup> =
        db.bitwardenConflictBackupsQueries.selectByVaultUnresolved(vaultId).executeAsList().map(::toBackup)

    override suspend fun markConflictResolved(backupId: Long) {
        db.bitwardenConflictBackupsQueries.markResolved(id = backupId)
    }

    // ===== 待同步队列 =====
    override suspend fun insertPendingOperation(op: BitwardenPendingOperation) {
        val now = System.currentTimeMillis()
        val id = if (op.id == 0L) nextId("bitwarden_pending_operations") else op.id
        db.bitwardenPendingOperationsQueries.insert(
            id = id,
            vault_id = op.vaultId,
            cipher_id = op.cipherId,
            operation_type = op.operationType.name,
            payload_json = op.payloadJson,
            status = op.status.name,
            error_count = op.errorCount.toLong(),
            last_error = op.lastError,
            created_at = op.createdAt,
            updated_at = now
        )
    }

    override suspend fun getRunnablePendingOperations(vaultId: Long): List<BitwardenPendingOperation> =
        db.bitwardenPendingOperationsQueries.selectRunnableByVault(vaultId).executeAsList().map(::toPending)

    override suspend fun updatePendingOperation(op: BitwardenPendingOperation) {
        val now = System.currentTimeMillis()
        db.bitwardenPendingOperationsQueries.update(
            vault_id = op.vaultId,
            cipher_id = op.cipherId,
            operation_type = op.operationType.name,
            payload_json = op.payloadJson,
            status = op.status.name,
            error_count = op.errorCount.toLong(),
            last_error = op.lastError,
            updated_at = now,
            id = op.id
        )
    }

    override suspend fun markPendingCompleted(opId: Long) {
        val now = System.currentTimeMillis()
        db.bitwardenPendingOperationsQueries.updateStatus(
            status = BitwardenPendingOperation.Status.COMPLETED.name,
            updated_at = now,
            id = opId
        )
    }

    // ===== 偏好 =====
    override fun loadBoolean(key: String, default: Boolean): Boolean {
        val row = db.preferencesQueries.selectByKey(key).executeAsOneOrNull()
        return row?.bool_value?.let { it != 0L } ?: default
    }

    override fun saveBoolean(key: String, value: Boolean) {
        db.preferencesQueries.upsertBoolean(key = key, bool_value = if (value) 1 else 0)
    }

    override fun loadLong(key: String, default: Long): Long {
        val row = db.preferencesQueries.selectByKey(key).executeAsOneOrNull()
        return row?.long_value ?: default
    }

    override fun saveLong(key: String, value: Long) {
        db.preferencesQueries.upsertLong(key = key, long_value = value)
    }

    // ===== 辅助 =====
    private fun getActiveVaultId(): Long? {
        val row = db.preferencesQueries.selectByKey(activeVaultKey).executeAsOneOrNull()
        return row?.long_value
    }

    private fun nextId(table: String): Long {
        val cursor = driver.executeQuery(
            null,
            "SELECT COALESCE(MAX(id), 0) + 1 FROM $table",
            { c: SqlCursor -> QueryResult.Value(c) },
            0
        ) { }.value
        val hasRow = cursor.next().value
        return if (hasRow) cursor.getLong(0) ?: 1L else 1L
    }

    private fun toVault(row: Bitwarden_vaults): BitwardenVault = BitwardenVault(
        id = row.id,
        email = row.email,
        canonicalEmail = row.canonical_email,
        userId = row.user_id,
        accountKey = row.account_key,
        displayName = row.display_name,
        serverUrl = row.server_url,
        identityUrl = row.identity_url,
        apiUrl = row.api_url,
        eventsUrl = row.events_url,
        tlsCertificateAlias = row.tls_certificate_alias,
        tlsCaCertificatePem = row.tls_ca_certificate_pem,
        tlsMtlsEnabled = row.tls_mtls_enabled != 0L,
        tlsClientCertPkcs12Base64 = row.tls_client_cert_pkcs12_base64,
        tlsEncryptedClientCertPassword = row.tls_encrypted_client_cert_password,
        encryptedAccessToken = row.encrypted_access_token,
        encryptedRefreshToken = row.encrypted_refresh_token,
        accessTokenExpiresAt = row.access_token_expires_at,
        encryptedMasterKey = row.encrypted_master_key,
        encryptedEncKey = row.encrypted_enc_key,
        encryptedMacKey = row.encrypted_mac_key,
        kdfType = row.kdf_type.toInt(),
        kdfIterations = row.kdf_iterations.toInt(),
        kdfMemory = row.kdf_memory?.toInt(),
        kdfParallelism = row.kdf_parallelism?.toInt(),
        lastSyncAt = row.last_sync_at,
        lastFullSyncAt = row.last_full_sync_at,
        revisionDate = row.revision_date,
        isDefault = row.is_default != 0L,
        isLocked = row.is_locked != 0L,
        isConnected = row.is_connected != 0L,
        syncEnabled = row.sync_enabled != 0L,
        createdAt = row.created_at,
        updatedAt = row.updated_at
    )

    private fun toPasswordEntry(row: Password_entries): PasswordEntry = PasswordEntry(
        id = row.id,
        title = row.title,
        website = row.website ?: "",
        username = row.username ?: "",
        password = row.encrypted_password ?: "",
        notes = row.notes ?: "",
        isFavorite = row.is_favorite != 0L,
        createdAt = row.created_at,
        updatedAt = row.updated_at,
        bitwardenVaultId = row.bitwarden_vault_id,
        bitwardenCipherId = row.bitwarden_cipher_id,
        bitwardenFolderId = row.bitwarden_folder_id,
        bitwardenRevisionDate = row.bitwarden_revision_date,
        bitwardenLocalModified = row.bitwarden_local_modified != 0L,
        keepassDatabaseId = row.keepass_database_id,
        keepassEntryUuid = row.keepass_entry_uuid,
        keepassGroupUuid = row.keepass_group_uuid
    )

    private fun toFolder(row: Bitwarden_folders): BitwardenFolder = BitwardenFolder(
        id = row.id,
        vaultId = row.vault_id,
        bitwardenFolderId = row.bitwarden_folder_id,
        name = row.name,
        encryptedName = row.encrypted_name,
        revisionDate = row.revision_date
    )

    private fun toBackup(row: Bitwarden_conflict_backups): BitwardenConflictBackup = BitwardenConflictBackup(
        id = row.id,
        vaultId = row.vault_id,
        entryId = row.entry_id,
        cipherId = row.cipher_id,
        conflictType = row.conflict_type,
        serverSnapshotJson = row.server_snapshot_json,
        localSnapshotJson = row.local_snapshot_json,
        resolved = row.resolved != 0L,
        createdAt = row.created_at
    )

    private fun toPending(row: Bitwarden_pending_operations): BitwardenPendingOperation = BitwardenPendingOperation(
        id = row.id,
        vaultId = row.vault_id,
        cipherId = row.cipher_id,
        operationType = parseOpType(row.operation_type),
        payloadJson = row.payload_json,
        status = parseStatus(row.status),
        errorCount = row.error_count.toInt(),
        lastError = row.last_error,
        createdAt = row.created_at,
        updatedAt = row.updated_at
    )

    private fun parseOpType(s: String): BitwardenPendingOperation.OperationType = try {
        BitwardenPendingOperation.OperationType.valueOf(s)
    } catch (_: Exception) {
        BitwardenPendingOperation.OperationType.UPDATE
    }

    private fun parseStatus(s: String): BitwardenPendingOperation.Status = try {
        BitwardenPendingOperation.Status.valueOf(s)
    } catch (_: Exception) {
        BitwardenPendingOperation.Status.PENDING
    }
}
