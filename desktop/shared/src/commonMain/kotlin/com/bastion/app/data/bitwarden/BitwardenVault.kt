package com.bastion.app.data.bitwarden

/**
 * Bitwarden Vault - 存储用户的 Bitwarden 账户信息（桌面版纯 Kotlin 模型，无 Room 注解）。
 *
 * 设计原则:
 * 1. 每个 Vault 对应一个 Bitwarden 账户
 * 2. 敏感数据 (refreshToken, masterKey, encKey, macKey) 必须加密存储
 * 3. 服务器端点支持官方服务和自托管服务
 *
 * 安全规则:
 * - 任何错误都不能导致此表数据丢失
 * - 删除操作需要软删除机制
 */
data class BitwardenVault(
    val id: Long = 0,

    // === 账户标识 ===
    val email: String,
    val canonicalEmail: String = "",
    val userId: String? = null,  // Bitwarden 用户 UUID
    val accountKey: String = "",
    val displayName: String? = null,

    // === 服务器配置 ===
    val serverUrl: String = "https://vault.bitwarden.com",
    val identityUrl: String = "https://identity.bitwarden.com",
    val apiUrl: String = "https://api.bitwarden.com",
    val eventsUrl: String? = null,

    // === TLS / 证书配置（仅用于自托管）===
    val tlsCertificateAlias: String? = null,
    val tlsCaCertificatePem: String? = null,
    val tlsMtlsEnabled: Boolean = false,
    val tlsClientCertPkcs12Base64: String? = null,
    val tlsEncryptedClientCertPassword: String? = null,

    // === 认证信息 (加密存储) ===
    val encryptedAccessToken: String? = null,  // 访问令牌 (加密)
    val encryptedRefreshToken: String? = null, // 刷新令牌 (加密)
    val accessTokenExpiresAt: Long? = null,    // 访问令牌过期时间 (Unix ms)

    // === 加密密钥 (加密存储) ===
    val encryptedMasterKey: String? = null,    // 主密钥 (Base64, 加密)
    val encryptedEncKey: String? = null,       // 加密密钥 (32字节, 加密)
    val encryptedMacKey: String? = null,       // MAC 密钥 (32字节, 加密)

    // === KDF 配置 ===
    val kdfType: Int = KDF_TYPE_PBKDF2,        // 0=PBKDF2, 1=Argon2id
    val kdfIterations: Int = 600000,           // PBKDF2: 600000, Argon2: 3
    val kdfMemory: Int? = null,                // Argon2 专用: 64 (MB)
    val kdfParallelism: Int? = null,           // Argon2 专用: 4

    // === 同步状态 ===
    val lastSyncAt: Long? = null,              // 最后同步时间 (Unix ms)
    val lastFullSyncAt: Long? = null,          // 最后完整同步时间
    val revisionDate: String? = null,          // 服务器 revision date

    // === 状态标志 ===
    val isDefault: Boolean = false,            // 是否为默认 vault
    val isLocked: Boolean = true,              // 是否已锁定
    val isConnected: Boolean = false,          // 是否已连接
    val syncEnabled: Boolean = true,           // 是否启用同步

    // === 审计字段 ===
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val KDF_TYPE_PBKDF2 = 0
        const val KDF_TYPE_ARGON2ID = 1

        const val DEFAULT_PBKDF2_ITERATIONS = 600000
        const val DEFAULT_ARGON2_ITERATIONS = 3
        const val DEFAULT_ARGON2_MEMORY = 64
        const val DEFAULT_ARGON2_PARALLELISM = 4
    }

    /**
     * 检查访问令牌是否过期
     */
    fun isAccessTokenExpired(): Boolean {
        val expiresAt = accessTokenExpiresAt ?: return true
        // 提前 5 分钟刷新
        return System.currentTimeMillis() > (expiresAt - 5 * 60 * 1000)
    }

    /**
     * 是否使用 Argon2 KDF
     */
    fun usesArgon2(): Boolean = kdfType == KDF_TYPE_ARGON2ID
}
