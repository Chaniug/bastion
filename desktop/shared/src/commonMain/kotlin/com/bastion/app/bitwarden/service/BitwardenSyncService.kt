package com.bastion.app.bitwarden.service

import com.bastion.app.bitwarden.api.BitwardenApiFactory
import com.bastion.app.bitwarden.api.BitwardenApiManager
import com.bastion.app.bitwarden.api.CipherApiResponse
import com.bastion.app.bitwarden.api.CipherCreateRequest
import com.bastion.app.bitwarden.api.CipherLoginApiData
import com.bastion.app.bitwarden.api.CipherUpdateRequest
import com.bastion.app.bitwarden.api.CipherUriApiData
import com.bastion.app.bitwarden.api.SyncResponse
import com.bastion.app.bitwarden.crypto.BitwardenCrypto
import com.bastion.app.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import com.bastion.app.bitwarden.repository.BitwardenRepositoryStore
import com.bastion.app.data.PasswordEntry
import com.bastion.app.data.bitwarden.BitwardenConflictBackup
import com.bastion.app.data.bitwarden.BitwardenFolder
import com.bastion.app.data.bitwarden.BitwardenPendingOperation
import com.bastion.app.data.bitwarden.BitwardenVault
import com.bastion.app.logging.runCatchingObserved
import com.bastion.app.platform.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 桌面版 Bitwarden 同步服务（精简）。
 *
 * 与安卓版 [BitwardenSyncService]（2448 行）职责对齐，但只处理 Login 类型 Cipher：
 * 1. fullSync：拉取 sync → 解密 Login cipher → 写入 store（冲突备份）
 * 2. 上传本地修改（CREATE / UPDATE / DELETE）
 * 3. 待同步队列处理
 *
 * 存储通过 [store] 抽象注入（不直接依赖 Room/SQLDelight）。
 */
class BitwardenSyncService(
    private val store: BitwardenRepositoryStore,
    private val apiManager: BitwardenApiManager = BitwardenApiManager()
) {

    private val TAG = "BitwardenSyncService"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    companion object {
        private const val CIPHER_TYPE_LOGIN = 1
        private const val CIPHER_TYPE_CARD = 2
        private const val CIPHER_TYPE_IDENTITY = 3
        private const val CIPHER_TYPE_SECURE_NOTE = 4
        private const val CIPHER_TYPE_SSH_KEY = 5
    }

    // ==================== 拉取同步 ====================

    suspend fun fullSync(
        vault: BitwardenVault,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): SyncResult = withContext(Dispatchers.IO) {
        Logger.i(TAG, "Starting full sync for vault ${vault.id}")
        try {
            val vaultApi = apiManager.getVaultApi(vault.apiUrl, refererUrl = vault.serverUrl)
            val response = vaultApi.sync(authorization = "Bearer $accessToken")

            if (!response.isSuccessful) {
                return@withContext SyncResult.Error(
                    "Sync failed: ${response.code()} ${response.message()}"
                )
            }
            val syncResponse = response.body()
                ?: return@withContext SyncResult.Error("Empty sync response")

            processSyncResponse(vault, syncResponse, symmetricKey)
        } catch (e: Exception) {
            Logger.e(TAG, "Full sync failed", e)
            SyncResult.Error("Sync failed: ${e.message}")
        }
    }

    private suspend fun processSyncResponse(
        vault: BitwardenVault,
        response: SyncResponse,
        symmetricKey: SymmetricCryptoKey
    ): SyncResult {
        var foldersAdded = 0
        var ciphersAdded = 0
        var ciphersUpdated = 0
        var conflictsDetected = 0
        var skipped = 0

        val activeServerCipherIds = response.ciphers
            .asSequence()
            .filter { it.deletedDate == null }
            .map { it.id }
            .toList()

        try {
            // 1. 同步文件夹
            response.folders.forEach { folderApi ->
                runCatchingObserved(tag = TAG) {
                    val existing = store.getFolderByBitwardenId(vault.id, folderApi.id)
                    if (existing != null &&
                        existing.revisionDate == folderApi.revisionDate &&
                        existing.encryptedName == folderApi.name
                    ) {
                        return@runCatchingObserved
                    }
                    val decryptedName = decryptString(folderApi.name, symmetricKey) ?: ""
                    store.upsertFolder(
                        BitwardenFolder(
                            vaultId = vault.id,
                            bitwardenFolderId = folderApi.id,
                            name = decryptedName,
                            encryptedName = folderApi.name,
                            revisionDate = folderApi.revisionDate
                        )
                    )
                    if (existing == null) foldersAdded++
                }
            }

            // 2. 同步 Ciphers（仅 Login 类型）
            response.ciphers.forEach { cipherApi ->
                runCatchingObserved(tag = TAG) {
                    when (val result = syncCipher(vault, cipherApi, symmetricKey)) {
                        is CipherSyncResult.Added -> ciphersAdded++
                        is CipherSyncResult.Updated -> ciphersUpdated++
                        is CipherSyncResult.Conflict -> conflictsDetected++
                        is CipherSyncResult.Error -> {
                            Logger.w(TAG, "Cipher sync error: ${result.message}")
                            skipped++
                        }
                        is CipherSyncResult.Skipped -> skipped++
                    }
                }
            }

            // 3. 清理服务器上已删除的条目
            store.deleteEntriesNotIn(vault.id, activeServerCipherIds)

            Logger.i(
                TAG,
                "Full sync completed: added=$ciphersAdded updated=$ciphersUpdated " +
                    "conflicts=$conflictsDetected skipped=$skipped folders=$foldersAdded"
            )
            return SyncResult.Success(
                appliedChangeCount = ciphersAdded + ciphersUpdated,
                remoteAddedCount = ciphersAdded,
                remoteUpdatedCount = ciphersUpdated,
                uploadedCount = 0,
                deletedCount = 0,
                availableOfflineCount = store.countEntries(vault.id),
                conflictCount = conflictsDetected,
                uploadFailedCount = 0,
                skippedDueToLocalDirtyCount = 0
            )
        } catch (e: Exception) {
            Logger.e(TAG, "processSyncResponse failed", e)
            return SyncResult.Error("Sync processing failed: ${e.message}")
        }
    }

    private suspend fun syncCipher(
        vault: BitwardenVault,
        cipherApi: CipherApiResponse,
        symmetricKey: SymmetricCryptoKey
    ): CipherSyncResult {
        // 只处理 Login 类型
        if (cipherApi.type != CIPHER_TYPE_LOGIN) {
            return CipherSyncResult.Skipped("Only login ciphers are supported")
        }
        if (cipherApi.deletedDate != null) {
            return CipherSyncResult.Skipped("Cipher is deleted")
        }

        val existingEntry = store.getEntryByCipherId(vault.id, cipherApi.id)

        return if (existingEntry == null) {
            val newEntry = cipherToPasswordEntry(vault, cipherApi, symmetricKey)
            if (newEntry != null) {
                store.insertEntry(newEntry)
                CipherSyncResult.Added
            } else {
                CipherSyncResult.Error("Failed to convert cipher")
            }
        } else {
            // 本地有修改且版本不一致 → 冲突备份
            if (existingEntry.bitwardenLocalModified &&
                existingEntry.bitwardenRevisionDate != cipherApi.revisionDate
            ) {
                createConflictBackup(vault, existingEntry, cipherApi)
                return CipherSyncResult.Conflict
            }
            val updatedEntry = updatePasswordEntryFromCipher(existingEntry, vault.id, cipherApi, symmetricKey)
            if (updatedEntry != null) {
                store.updateEntry(updatedEntry)
                CipherSyncResult.Updated
            } else {
                CipherSyncResult.Error("Failed to update entry")
            }
        }
    }

    // ==================== Cipher → PasswordEntry ====================

    private fun cipherToPasswordEntry(
        vault: BitwardenVault,
        cipher: CipherApiResponse,
        symmetricKey: SymmetricCryptoKey
    ): PasswordEntry? {
        try {
            val login = cipher.login ?: return null
            val name = decryptString(cipher.name, symmetricKey) ?: "Untitled"
            val username = decryptString(login.username, symmetricKey) ?: ""
            val decryptedPassword = decryptString(login.password, symmetricKey)
            if (!login.password.isNullOrBlank() && decryptedPassword == null) {
                Logger.w(TAG, "Skip cipher ${cipher.id}: password decrypt failed")
                return null
            }
            val password = decryptedPassword ?: ""
            val notes = decryptString(cipher.notes, symmetricKey) ?: ""
            val parsedUris = parseLoginUris(login.uris, symmetricKey)

            return PasswordEntry(
                title = name,
                website = parsedUris.website,
                username = username,
                password = password,
                notes = notes,
                isFavorite = cipher.favorite,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                bitwardenVaultId = vault.id,
                bitwardenCipherId = cipher.id,
                bitwardenFolderId = cipher.folderId,
                bitwardenRevisionDate = cipher.revisionDate,
                bitwardenLocalModified = false
            )
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to convert cipher ${cipher.id}", e)
            return null
        }
    }

    private fun updatePasswordEntryFromCipher(
        entry: PasswordEntry,
        vaultId: Long,
        cipher: CipherApiResponse,
        symmetricKey: SymmetricCryptoKey
    ): PasswordEntry? {
        try {
            val login = cipher.login ?: return null
            val name = decryptString(cipher.name, symmetricKey) ?: entry.title
            val username = decryptString(login.username, symmetricKey) ?: entry.username
            val decryptedPassword = decryptString(login.password, symmetricKey)
            val password = decryptedPassword ?: entry.password
            val notes = decryptString(cipher.notes, symmetricKey) ?: entry.notes
            val parsedUris = parseLoginUris(login.uris, symmetricKey)

            return entry.copy(
                title = name,
                website = if (parsedUris.website.isNotBlank()) parsedUris.website else entry.website,
                username = username,
                password = password,
                notes = notes,
                isFavorite = cipher.favorite,
                updatedAt = System.currentTimeMillis(),
                bitwardenFolderId = cipher.folderId,
                bitwardenRevisionDate = cipher.revisionDate,
                bitwardenLocalModified = false
            )
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to update entry from cipher ${cipher.id}", e)
            return null
        }
    }

    private fun parseLoginUris(uris: List<CipherUriApiData>?, symmetricKey: SymmetricCryptoKey): ParsedLoginUris {
        val first = uris?.firstOrNull() ?: return ParsedLoginUris()
        val uri = decryptString(first.uri, symmetricKey) ?: return ParsedLoginUris()
        return ParsedLoginUris(website = normalizeWebsite(uri))
    }

    private data class ParsedLoginUris(val website: String = "")

    private fun normalizeWebsite(raw: String): String {
        val trimmed = raw.trim()
        val lower = trimmed.lowercase()
        // 去掉协议与路径，保留 host
        val noScheme = when {
            lower.startsWith("https://") -> trimmed.substring(8)
            lower.startsWith("http://") -> trimmed.substring(7)
            lower.startsWith("ssh://") -> trimmed.substring(6)
            else -> trimmed
        }
        val host = noScheme.split('/', '?', '#').firstOrNull()?.trim() ?: return trimmed
        return host.removePrefix("www.").ifBlank { trimmed }
    }

    // ==================== 冲突备份 ====================

    private suspend fun createConflictBackup(
        vault: BitwardenVault,
        entry: PasswordEntry,
        serverCipher: CipherApiResponse
    ) {
        runCatchingObserved(tag = TAG) {
            store.insertConflictBackup(
                BitwardenConflictBackup(
                    vaultId = vault.id,
                    entryId = entry.id,
                    cipherId = serverCipher.id,
                    conflictType = BitwardenConflictBackup.TYPE_CONCURRENT_EDIT,
                    serverSnapshotJson = json.encodeToString(serverCipher),
                    localSnapshotJson = json.encodeToString(entry),
                    createdAt = System.currentTimeMillis()
                )
            )
            Logger.w(TAG, "Created conflict backup for entry ${entry.id}")
        }
    }

    // ==================== 上传本地修改 ====================

    suspend fun uploadLocalEntries(
        vault: BitwardenVault,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): UploadResult = withContext(Dispatchers.IO) {
        Logger.i(TAG, "Uploading local entries for vault ${vault.id}")
        try {
            val localEntries = store.getLocalModifiedEntries(vault.id)
            if (localEntries.isEmpty()) {
                return@withContext UploadResult.Success(uploadedCount = 0)
            }
            val vaultApi = apiManager.getVaultApi(vault.apiUrl, refererUrl = vault.serverUrl)
            var uploaded = 0
            var failed = 0

            for (entry in localEntries) {
                val cipherId = entry.bitwardenCipherId
                val result = when {
                    cipherId == null -> uploadCreate(entry, vault, accessToken, symmetricKey, vaultApi)
                    else -> uploadUpdate(entry, cipherId, vault, accessToken, symmetricKey, vaultApi)
                }
                if (result) uploaded++ else failed++
            }
            UploadResult.Success(uploadedCount = uploaded, failedCount = failed)
        } catch (e: Exception) {
            Logger.e(TAG, "Upload local entries failed", e)
            UploadResult.Error(e.message ?: "上传失败")
        }
    }

    private suspend fun uploadCreate(
        entry: PasswordEntry,
        vault: BitwardenVault,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey,
        vaultApi: com.bastion.app.bitwarden.api.BitwardenVaultApi
    ): Boolean {
        return try {
            val request = buildCreateRequest(entry, symmetricKey)
            val response = vaultApi.createCipher(authorization = "Bearer $accessToken", cipher = request)
            if (response.isSuccessful) {
                val created = response.body()
                if (created != null) {
                    store.updateEntry(
                        entry.copy(
                            bitwardenCipherId = created.id,
                            bitwardenRevisionDate = created.revisionDate,
                            bitwardenLocalModified = false,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
                true
            } else {
                Logger.w(TAG, "Create cipher failed: ${response.code()}")
                false
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Create cipher exception", e)
            false
        }
    }

    private suspend fun uploadUpdate(
        entry: PasswordEntry,
        cipherId: String,
        vault: BitwardenVault,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey,
        vaultApi: com.bastion.app.bitwarden.api.BitwardenVaultApi
    ): Boolean {
        return try {
            val request = buildUpdateRequest(entry, symmetricKey)
            val response = vaultApi.updateCipher(
                authorization = "Bearer $accessToken",
                cipherId = cipherId,
                cipher = request
            )
            if (response.isSuccessful) {
                val updated = response.body()
                store.updateEntry(
                    entry.copy(
                        bitwardenRevisionDate = updated?.revisionDate ?: entry.bitwardenRevisionDate,
                        bitwardenLocalModified = false,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                true
            } else {
                Logger.w(TAG, "Update cipher $cipherId failed: ${response.code()}")
                false
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Update cipher exception", e)
            false
        }
    }

    private fun buildCreateRequest(entry: PasswordEntry, key: SymmetricCryptoKey): CipherCreateRequest {
        return CipherCreateRequest(
            type = CIPHER_TYPE_LOGIN,
            folderId = entry.bitwardenFolderId,
            name = BitwardenCrypto.encryptString(entry.title, key),
            notes = if (entry.notes.isBlank()) null else BitwardenCrypto.encryptString(entry.notes, key),
            login = CipherLoginApiData(
                username = BitwardenCrypto.encryptString(entry.username, key),
                password = if (entry.password.isBlank()) null else BitwardenCrypto.encryptString(entry.password, key),
                uris = if (entry.website.isNotBlank()) {
                    listOf(
                        CipherUriApiData(
                            uri = BitwardenCrypto.encryptString(entry.website, key),
                            match = null
                        )
                    )
                } else null
            ),
            favorite = entry.isFavorite
        )
    }

    private fun buildUpdateRequest(entry: PasswordEntry, key: SymmetricCryptoKey): CipherUpdateRequest {
        return CipherUpdateRequest(
            type = CIPHER_TYPE_LOGIN,
            folderId = entry.bitwardenFolderId,
            name = BitwardenCrypto.encryptString(entry.title, key),
            notes = if (entry.notes.isBlank()) null else BitwardenCrypto.encryptString(entry.notes, key),
            login = CipherLoginApiData(
                username = BitwardenCrypto.encryptString(entry.username, key),
                password = if (entry.password.isBlank()) null else BitwardenCrypto.encryptString(entry.password, key),
                uris = if (entry.website.isNotBlank()) {
                    listOf(
                        CipherUriApiData(
                            uri = BitwardenCrypto.encryptString(entry.website, key),
                            match = null
                        )
                    )
                } else null
            ),
            favorite = entry.isFavorite
        )
    }

    // ==================== 待同步队列处理 ====================

    suspend fun processPendingOperations(
        vault: BitwardenVault,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): Int = withContext(Dispatchers.IO) {
        val ops = store.getRunnablePendingOperations(vault.id)
        if (ops.isEmpty()) return@withContext 0

        val vaultApi = apiManager.getVaultApi(vault.apiUrl, refererUrl = vault.serverUrl)
        var processed = 0
        for (op in ops) {
            val ok = when (op.operationType) {
                BitwardenPendingOperation.OperationType.CREATE -> {
                    runCatchingObserved(tag = TAG) {
                        val request = json.decodeFromString<CipherCreateRequest>(op.payloadJson)
                        vaultApi.createCipher("Bearer $accessToken", request).isSuccessful
                    }.getOrElse { false }
                }
                BitwardenPendingOperation.OperationType.UPDATE -> {
                    runCatchingObserved(tag = TAG) {
                        val request = json.decodeFromString<CipherUpdateRequest>(op.payloadJson)
                        vaultApi.updateCipher("Bearer $accessToken", op.cipherId, request).isSuccessful
                    }.getOrElse { false }
                }
                BitwardenPendingOperation.OperationType.DELETE -> {
                    runCatchingObserved(tag = TAG) {
                        vaultApi.deleteCipher("Bearer $accessToken", op.cipherId).isSuccessful
                    }.getOrElse { false }
                }
                BitwardenPendingOperation.OperationType.RESTORE -> {
                    runCatchingObserved(tag = TAG) {
                        vaultApi.restoreCipher("Bearer $accessToken", op.cipherId).isSuccessful
                    }.getOrElse { false }
                }
            }
            if (ok) {
                store.markPendingCompleted(op.id)
                processed++
            }
        }
        processed
    }

    // ==================== 辅助 ====================

    private fun decryptString(encrypted: String?, key: SymmetricCryptoKey): String? {
        if (encrypted.isNullOrBlank()) return null
        return runCatchingObserved(tag = TAG) {
            BitwardenCrypto.decryptToString(encrypted, key)
        }.getOrNull()
    }

    private fun decryptFolderName(encrypted: String?, key: SymmetricCryptoKey): String? =
        decryptString(encrypted, key)

    // ==================== 结果类型 ====================

    sealed class SyncResult {
        data class Success(
            val appliedChangeCount: Int,
            val remoteAddedCount: Int,
            val remoteUpdatedCount: Int,
            val uploadedCount: Int,
            val deletedCount: Int,
            val availableOfflineCount: Int,
            val conflictCount: Int,
            val uploadFailedCount: Int,
            val skippedDueToLocalDirtyCount: Int
        ) : SyncResult()

        data class Error(val message: String) : SyncResult()

        data class EmptyVaultBlocked(
            val vaultId: Long,
            val localCount: Int,
            val serverCount: Int,
            val reason: String
        ) : SyncResult()
    }

    sealed class CipherSyncResult {
        data object Added : CipherSyncResult()
        data object Updated : CipherSyncResult()
        data object Conflict : CipherSyncResult()
        data class Skipped(val reason: String) : CipherSyncResult()
        data class Error(val message: String) : CipherSyncResult()
    }

    sealed class UploadResult {
        data class Success(val uploadedCount: Int, val failedCount: Int = 0) : UploadResult()
        data class Error(val message: String) : UploadResult()
    }
}
