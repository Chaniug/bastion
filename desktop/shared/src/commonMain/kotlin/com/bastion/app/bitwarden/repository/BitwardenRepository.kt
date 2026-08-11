package com.bastion.app.bitwarden.repository

import com.bastion.app.bitwarden.api.BitwardenApiManager
import com.bastion.app.bitwarden.api.BitwardenApiFactory
import com.bastion.app.bitwarden.api.BitwardenTlsConfig
import com.bastion.app.bitwarden.crypto.BitwardenCrypto
import com.bastion.app.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import com.bastion.app.bitwarden.service.BitwardenAuthService
import com.bastion.app.bitwarden.service.BitwardenSyncService
import com.bastion.app.bitwarden.service.LoginResult
import com.bastion.app.data.PasswordEntry
import com.bastion.app.data.bitwarden.BitwardenVault
import com.bastion.app.platform.Logger
import com.bastion.app.security.DesktopCryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 桌面端 Bitwarden 仓储（瘦身版）。
 *
 * 与安卓版 [BitwardenRepository]（2155 行）的职责对齐，但只保留桌面端三类核心能力：
 * 1. 登录 / 两因素 / 解锁 / 锁定 / 登出
 * 2. 拉取与推送同步（Login 类型 Cipher）
 * 3. 冲突备份与待同步队列
 *
 * 存储层通过 [store] 抽象注入（Phase 0 用内存实现，Phase 3 换 SQLDelight）。
 */
class BitwardenRepository(
    private val store: BitwardenRepositoryStore,
    private val cryptoManager: DesktopCryptoManager,
    private val apiManager: BitwardenApiManager = BitwardenApiManager()
) {
    private val TAG = "BitwardenRepository"

    // 会话密钥缓存（锁仓即清空）
    private val symmetricKeyCache = mutableMapOf<Long, SymmetricCryptoKey>()
    private val accessTokenCache = mutableMapOf<Long, String>()

    private val vaultSyncMutexes = mutableMapOf<Long, Mutex>()

    // 同步状态（供 UI 观察）
    private val _syncStatus = MutableStateFlow<Map<Long, String>>(emptyMap())
    val syncStatus: StateFlow<Map<Long, String>> = _syncStatus.asStateFlow()

    // ==================== 设置 ====================

    var isAutoSyncEnabled: Boolean
        get() = store.loadBoolean("auto_sync_enabled", true)
        set(value) = store.saveBoolean("auto_sync_enabled", value)

    var isSyncOnWifiOnly: Boolean
        get() = store.loadBoolean("sync_on_wifi_only", false)
        set(value) = store.saveBoolean("sync_on_wifi_only", value)

    var isNeverLockEnabled: Boolean
        get() = store.loadBoolean("never_lock_bitwarden", false)
        set(value) = store.saveBoolean("never_lock_bitwarden", value)

    val lastSyncTime: Long
        get() = store.loadLong("last_sync_time", 0L)

    // ==================== Vault 管理 ====================

    suspend fun getAllVaults(): List<BitwardenVault> = withContext(Dispatchers.IO) {
        store.getAllVaults()
    }

    fun getAllVaultsFlow(): Flow<List<BitwardenVault>> = store.observeAllVaults()

    suspend fun getActiveVault(): BitwardenVault? = withContext(Dispatchers.IO) {
        store.getActiveVault()
    }

    fun setActiveVault(vaultId: Long) = store.setActiveVault(vaultId)

    fun isVaultUnlocked(vaultId: Long): Boolean = symmetricKeyCache.containsKey(vaultId)

    fun getCachedSymmetricKey(vaultId: Long): SymmetricCryptoKey? = symmetricKeyCache[vaultId]

    // ==================== 认证 ====================

    suspend fun login(
        email: String,
        masterPassword: String,
        serverUrl: String,
        tlsConfig: BitwardenTlsConfig = BitwardenTlsConfig()
    ): RepositoryLoginResult = withContext(Dispatchers.IO) {
        try {
            val authService = BitwardenAuthService(apiManager)
            when (val result = authService.login(
                email = email,
                password = masterPassword,
                serverUrl = serverUrl,
                tlsConfig = tlsConfig
            ).getOrElse {
                return@withContext RepositoryLoginResult.Error(it.message ?: "登录失败")
            }) {
                is LoginResult.Success -> {
                    val vault = BitwardenVault(
                        email = email,
                        canonicalEmail = email.lowercase(),
                        serverUrl = result.serverUrls.vault,
                        identityUrl = result.serverUrls.identity,
                        apiUrl = result.serverUrls.api,
                        kdfType = result.kdfType,
                        kdfIterations = result.kdfIterations,
                        kdfMemory = result.kdfMemory,
                        kdfParallelism = result.kdfParallelism,
                        encryptedAccessToken = cryptoManager.encryptString(result.accessToken),
                        encryptedRefreshToken = result.refreshToken?.let { cryptoManager.encryptString(it) },
                        encryptedEncKey = cryptoManager.wrapKeyWithDek(result.symmetricKey.encKey),
                        encryptedMacKey = cryptoManager.wrapKeyWithDek(result.symmetricKey.macKey),
                        isConnected = true,
                        isLocked = false
                    )
                    val stored = store.upsertVault(vault)
                    symmetricKeyCache[stored.id] = result.symmetricKey
                    accessTokenCache[stored.id] = result.accessToken
                    RepositoryLoginResult.Success(stored)
                }
                is LoginResult.TwoFactorRequired -> {
                    RepositoryLoginResult.TwoFactorRequired(
                        providers = result.providers,
                        state = result
                    )
                }
                is LoginResult.CaptchaRequired -> {
                    RepositoryLoginResult.CaptchaRequired(result.message, result.siteKey)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Login failed", e)
            RepositoryLoginResult.Error(e.message ?: "登录失败")
        }
    }

    suspend fun loginWithTwoFactor(
        twoFactorState: LoginResult.TwoFactorRequired,
        provider: Int,
        code: String,
        remember: Boolean = false,
        serverUrl: String = BitwardenApiFactory.OFFICIAL_VAULT_URL
    ): RepositoryLoginResult = withContext(Dispatchers.IO) {
        try {
            val authService = BitwardenAuthService(apiManager)
            val result = authService.loginTwoFactor(
                twoFactorState = twoFactorState,
                twoFactorCode = code,
                twoFactorProvider = provider,
                remember = remember,
                serverUrl = serverUrl
            ).getOrElse {
                return@withContext RepositoryLoginResult.Error(it.message ?: "两步验证登录失败")
            }
            when (result) {
                is LoginResult.Success -> {
                    val email = twoFactorState.email
                    val existing = store.getVaultByEmail(email)
                    val vault = (existing ?: BitwardenVault(email = email)).copy(
                        canonicalEmail = email.lowercase(),
                        serverUrl = result.serverUrls.vault,
                        identityUrl = result.serverUrls.identity,
                        apiUrl = result.serverUrls.api,
                        kdfType = result.kdfType,
                        kdfIterations = result.kdfIterations,
                        kdfMemory = result.kdfMemory,
                        kdfParallelism = result.kdfParallelism,
                        encryptedAccessToken = cryptoManager.encryptString(result.accessToken),
                        encryptedRefreshToken = result.refreshToken?.let { cryptoManager.encryptString(it) },
                        encryptedEncKey = cryptoManager.wrapKeyWithDek(result.symmetricKey.encKey),
                        encryptedMacKey = cryptoManager.wrapKeyWithDek(result.symmetricKey.macKey),
                        isConnected = true,
                        isLocked = false
                    )
                    val stored = store.upsertVault(vault)
                    symmetricKeyCache[stored.id] = result.symmetricKey
                    accessTokenCache[stored.id] = result.accessToken
                    RepositoryLoginResult.Success(stored)
                }
                is LoginResult.TwoFactorRequired -> {
                    RepositoryLoginResult.TwoFactorRequired(result.providers, result)
                }
                is LoginResult.CaptchaRequired -> {
                    RepositoryLoginResult.CaptchaRequired(result.message, result.siteKey)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Two-factor login failed", e)
            RepositoryLoginResult.Error(e.message ?: "两步验证登录失败")
        }
    }

    suspend fun unlock(vaultId: Long, masterPassword: String): UnlockResult = withContext(Dispatchers.IO) {
        try {
            val vault = store.getVaultById(vaultId) ?: return@withContext UnlockResult.Error("Vault 不存在")
            val storedEncKey = vault.encryptedEncKey ?: return@withContext UnlockResult.Error("Vault 未完成登录初始化")
            val storedMacKey = vault.encryptedMacKey ?: return@withContext UnlockResult.Error("Vault 未完成登录初始化")

            // 桌面端密钥由 DEK（Windows DPAPI 保护）包裹，解锁即解开 DEK
            val encKey = cryptoManager.unwrapKeyWithDek(storedEncKey)
            val macKey = cryptoManager.unwrapKeyWithDek(storedMacKey)
            val symmetricKey = SymmetricCryptoKey(encKey, macKey)

            symmetricKeyCache[vaultId] = symmetricKey
            store.markUnlocked(vaultId)
            UnlockResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Unlock failed", e)
            UnlockResult.Error(e.message ?: "解锁失败")
        }
    }

    suspend fun lock(vaultId: Long) = withContext(Dispatchers.IO) {
        symmetricKeyCache.remove(vaultId)?.clear()
        accessTokenCache.remove(vaultId)
        store.markLocked(vaultId)
    }

    suspend fun lockAll() = withContext(Dispatchers.IO) {
        symmetricKeyCache.values.forEach { it.clear() }
        symmetricKeyCache.clear()
        accessTokenCache.clear()
        store.markAllLocked()
    }

    suspend fun logout(vaultId: Long): Boolean = withContext(Dispatchers.IO) {
        symmetricKeyCache.remove(vaultId)?.clear()
        accessTokenCache.remove(vaultId)
        store.deleteVault(vaultId)
    }

    // ==================== 条目 ====================

    suspend fun getEntries(vaultId: Long): List<PasswordEntry> = withContext(Dispatchers.IO) {
        store.getEntriesByVault(vaultId)
    }

    fun observeEntries(vaultId: Long): Flow<List<PasswordEntry>> = store.observeEntriesByVault(vaultId)

    suspend fun getEntryById(id: Long): PasswordEntry? = withContext(Dispatchers.IO) {
        store.getEntryById(id)
    }

    suspend fun getEntryByCipherId(vaultId: Long, cipherId: String): PasswordEntry? =
        withContext(Dispatchers.IO) {
            store.getEntryByCipherId(vaultId, cipherId)
        }

    suspend fun getFolders(vaultId: Long): List<com.bastion.app.data.bitwarden.BitwardenFolder> =
        withContext(Dispatchers.IO) {
            store.getFoldersByVault(vaultId)
        }

    suspend fun getConflictBackups(vaultId: Long): List<com.bastion.app.data.bitwarden.BitwardenConflictBackup> =
        withContext(Dispatchers.IO) {
            store.getConflictBackups(vaultId)
        }

    /**
     * 创建或更新一个本地条目，并标记为待同步。
     * 若已有 id 则为更新，否则为新建。
     */
    suspend fun saveEntry(entry: PasswordEntry): PasswordEntry = withContext(Dispatchers.IO) {
        val existing = entry.id.let { id -> if (id > 0L) store.getEntryById(id) else null }
        val marked = entry.copy(bitwardenLocalModified = true, updatedAt = System.currentTimeMillis())
        if (existing != null) {
            store.updateEntry(marked)
            marked
        } else {
            store.insertEntry(marked)
        }
    }

    /** 删除条目（Bitwarden 场景：本地直接删除，待同步删除）。 */
    suspend fun deleteEntry(entry: PasswordEntry): Boolean = withContext(Dispatchers.IO) {
        val cipherId = entry.bitwardenCipherId
        if (cipherId != null) {
            store.deleteEntryByCipherId(entry.bitwardenVaultId ?: 0L, cipherId)
        } else if (entry.id > 0L) {
            store.getEntryById(entry.id)?.let {
                store.deleteEntryByCipherId(it.bitwardenVaultId ?: 0L, it.bitwardenCipherId ?: "")
            }
        }
        true
    }

    /** 上传本地修改到服务器。 */
    suspend fun uploadLocalChanges(vaultId: Long): UploadChangesResult = withContext(Dispatchers.IO) {
        try {
            val vault = store.getVaultById(vaultId) ?: return@withContext UploadChangesResult.Error("Vault 不存在")
            val symmetricKey = symmetricKeyCache[vaultId]
                ?: return@withContext UploadChangesResult.Error("密钥不可用")
            val accessToken = accessTokenCache[vaultId]
                ?: store.decryptAccessToken(vault, cryptoManager)?.also {
                    accessTokenCache[vaultId] = it
                } ?: return@withContext UploadChangesResult.Error("令牌不可用")

            val syncService = BitwardenSyncService(store, apiManager)
            when (val result = syncService.uploadLocalEntries(vault, accessToken, symmetricKey)) {
                is BitwardenSyncService.UploadResult.Success ->
                    UploadChangesResult.Success(result.uploadedCount, result.failedCount)
                is BitwardenSyncService.UploadResult.Error ->
                    UploadChangesResult.Error(result.message)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Upload local changes failed", e)
            UploadChangesResult.Error(e.message ?: "上传失败")
        }
    }

    // ==================== 同步 ====================

    suspend fun sync(vaultId: Long): SyncResult = withContext(Dispatchers.IO) {
        val mutex = syncMutexForVault(vaultId)
        mutex.withLock {
            try {
                val vault = store.getVaultById(vaultId) ?: return@withLock SyncResult.Error("Vault 不存在")
                if (isVaultLockedForSync(vault)) return@withLock SyncResult.Error("Vault 未解锁")

                val symmetricKey = symmetricKeyCache[vaultId]
                    ?: return@withLock SyncResult.Error("密钥不可用")
                val accessToken = accessTokenCache[vaultId]
                    ?: store.decryptAccessToken(vault, cryptoManager)?.also {
                        accessTokenCache[vaultId] = it
                    } ?: return@withLock SyncResult.Error("令牌不可用")

                _syncStatus.value = _syncStatus.value + (vaultId to "SYNCING")

                val syncService = BitwardenSyncService(store, apiManager)
                when (val result = syncService.fullSync(
                    vault = vault,
                    accessToken = accessToken,
                    symmetricKey = symmetricKey
                )) {
                    is BitwardenSyncService.SyncResult.Success -> {
                        store.markSynced(vaultId)
                        store.saveLong("last_sync_time", System.currentTimeMillis())
                        _syncStatus.value = _syncStatus.value + (vaultId to "SYNCED")
                        SyncResult.Success(
                            appliedChangeCount = result.appliedChangeCount,
                            remoteAddedCount = result.remoteAddedCount,
                            remoteUpdatedCount = result.remoteUpdatedCount,
                            uploadedCount = result.uploadedCount,
                            deletedCount = result.deletedCount,
                            availableOfflineCount = result.availableOfflineCount,
                            conflictCount = result.conflictCount,
                            uploadFailedCount = result.uploadFailedCount,
                            skippedDueToLocalDirtyCount = result.skippedDueToLocalDirtyCount
                        )
                    }
                    is BitwardenSyncService.SyncResult.EmptyVaultBlocked -> {
                        _syncStatus.value = _syncStatus.value + (vaultId to "EMPTY_BLOCKED")
                        SyncResult.EmptyVaultBlocked(
                            vaultId = vaultId,
                            localCount = result.localCount,
                            serverCount = result.serverCount,
                            reason = result.reason
                        )
                    }
                    is BitwardenSyncService.SyncResult.Error -> {
                        _syncStatus.value = _syncStatus.value + (vaultId to "FAILED")
                        SyncResult.Error(result.message)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Sync failed for vault $vaultId", e)
                _syncStatus.value = _syncStatus.value + (vaultId to "FAILED")
                SyncResult.Error(e.message ?: "同步失败")
            } finally {
                clearVaultSyncMutex(vaultId)
            }
        }
    }

    private fun isVaultLockedForSync(vault: BitwardenVault): Boolean = !symmetricKeyCache.containsKey(vault.id)

    private fun syncMutexForVault(vaultId: Long): Mutex =
        vaultSyncMutexes.getOrPut(vaultId) { Mutex() }

    private fun clearVaultSyncMutex(vaultId: Long) {
        vaultSyncMutexes.remove(vaultId)
    }

    // ==================== 结果类型 ====================

    sealed class RepositoryLoginResult {
        data class Success(val vault: BitwardenVault) : RepositoryLoginResult()
        data class TwoFactorRequired(
            val providers: List<Int>,
            val state: LoginResult.TwoFactorRequired
        ) : RepositoryLoginResult()
        data class CaptchaRequired(
            val message: String,
            val siteKey: String? = null
        ) : RepositoryLoginResult()
        data class Error(val message: String) : RepositoryLoginResult()
    }

    sealed class UnlockResult {
        object Success : UnlockResult()
        data class Error(val message: String) : UnlockResult()
    }

    sealed class UploadChangesResult {
        data class Success(val uploadedCount: Int, val failedCount: Int = 0) : UploadChangesResult()
        data class Error(val message: String) : UploadChangesResult()
    }

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

    // 避免与其他 SyncResult（service 层）冲突的别名
    typealias RepositorySyncResult = SyncResult
}

/** 抛出时的协程取消异常（避免误吞）。 */
private typealias CancellationException = kotlinx.coroutines.CancellationException
