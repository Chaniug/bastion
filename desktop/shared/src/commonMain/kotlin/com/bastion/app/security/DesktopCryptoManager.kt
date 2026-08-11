package com.bastion.app.security

import com.bastion.app.platform.Base64
import com.bastion.app.platform.KeyStorage
import com.bastion.app.platform.Logger
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 桌面端加密管理器（纯 JCE）。
 *
 * 与安卓版 [SecurityManager] 职责对齐，但密钥存储改为桌面方案：
 * - DEK（Data Encryption Key，随机 32 字节）由 Windows DPAPI 保护后落盘（[KeyStorage]）
 * - 应用主密钥（MDK）由 DEK 经 AES-256-GCM 包裹后落盘
 * - 字段级加密使用 AES-256-GCM
 * - Bitwarden 场景：vault 的 encKey/macKey 由 [KeyStorage] 的 DEK 包裹存储
 *
 * 密钥格式前缀保留与安卓一致的 `V2|`、`AU|`、`CP|`，便于后续 Android 数据导入（可选）。
 */
class DesktopCryptoManager(
    private val keyStorage: KeyStorageProvider = KeyStorageProvider.Default
) {

    interface KeyStorageProvider {
        fun isInitialized(): Boolean
        fun storeDek(dek: ByteArray): Boolean
        fun loadDek(): ByteArray?

        object Default : KeyStorageProvider {
            override fun isInitialized(): Boolean = KeyStorage.isInitialized()
            override fun storeDek(dek: ByteArray): Boolean = KeyStorage.storeDek(dek)
            override fun loadDek(): ByteArray? = KeyStorage.loadDek()
        }
    }

    companion object {
        private const val TAG = "DesktopCryptoManager"
        private const val GCM_TAG_BITS = 128
        private const val GCM_IV_SIZE = 12
        private const val DEK_SIZE = 32

        // 密钥格式前缀
        private const val PREFIX_V2 = "V2|"
        private const val PREFIX_APP_UNLOCK = "AU|"
        private const val PREFIX_CP = "CP|"
    }

    private val random = SecureRandom()

    /** DEK（内存缓存，用于加解密应用密钥）。 */
    private val dek: ByteArray by lazy {
        ensureDek()
    }

    private fun ensureDek(): ByteArray {
        keyStorage.loadDek()?.let { return it }
        val newDek = ByteArray(DEK_SIZE).also { random.nextBytes(it) }
        val stored = keyStorage.storeDek(newDek)
        if (!stored) {
            // 并发创建：读取已存在的
            keyStorage.loadDek()?.let { return it }
            throw IllegalStateException("无法初始化 DEK")
        }
        return newDek
    }

    // ==================== 应用主密钥（MDK）管理 ====================

    /** 生成并持久化 MDK（由 DEK 包裹）。返回是否首次创建。 */
    fun ensureAppMasterKey(): Boolean {
        val file = appMasterKeyFile()
        if (file.exists()) return false
        val mdk = ByteArray(32).also { random.nextBytes(it) }
        val encrypted = encryptWithDek(mdk)
        file.writeBytes(encrypted.toByteArray(Charsets.UTF_8))
        return true
    }

    /** 读取 MDK；不存在时返回 null。 */
    fun loadAppMasterKey(): ByteArray? {
        val file = appMasterKeyFile()
        if (!file.exists()) return null
        return try {
            decryptWithDek(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            Logger.e(TAG, "读取 MDK 失败", e)
            null
        }
    }

    private fun appMasterKeyFile(): java.io.File =
        java.io.File(com.bastion.app.platform.PathProvider.resolve("app_master_key.bin"))

    // ==================== 字段级加密（AES-256-GCM） ====================

    /** 用 MDK 加密字符串。 */
    fun encryptString(plaintext: String): String {
        val key = loadAppMasterKey() ?: throw IllegalStateException("MDK 未初始化")
        return encryptBytes(plaintext.toByteArray(Charsets.UTF_8), key, PREFIX_V2)
    }

    /** 用 MDK 解密字符串。 */
    fun decryptString(ciphertext: String): String {
        val key = loadAppMasterKey() ?: throw IllegalStateException("MDK 未初始化")
        return decryptBytes(ciphertext, key, PREFIX_V2).toString(Charsets.UTF_8)
    }

    // ==================== 外部密钥包裹（Bitwarden encKey/macKey） ====================

    /** 用 DEK 包裹一个密钥（如 Bitwarden SymmetricKey 的 encKey/macKey）。 */
    fun wrapKeyWithDek(rawKey: ByteArray): String {
        return encryptBytes(rawKey, dek, PREFIX_V2)
    }

    /** 用 DEK 解开被包裹的密钥。 */
    fun unwrapKeyWithDek(wrapped: String): ByteArray {
        return decryptBytes(wrapped, dek, PREFIX_V2)
    }

    // ==================== 内部实现 ====================

    private fun encryptWithDek(plaintext: ByteArray): String =
        encryptBytes(plaintext, dek, PREFIX_V2)

    private fun decryptWithDek(ciphertext: String): ByteArray =
        decryptBytes(ciphertext, dek, PREFIX_V2)

    private fun encryptBytes(plaintext: ByteArray, key: ByteArray, prefix: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(GCM_IV_SIZE).also { random.nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val encrypted = cipher.doFinal(plaintext)
        val ivB64 = Base64.encodeToString(iv)
        val dataB64 = Base64.encodeToString(encrypted)
        return prefix + "$ivB64|$dataB64"
    }

    private fun decryptBytes(ciphertext: String, key: ByteArray, prefix: String): ByteArray {
        val stored = ciphertext
        if (!stored.startsWith(prefix)) {
            throw IllegalArgumentException("密文格式前缀不匹配")
        }
        val payload = stored.removePrefix(prefix)
        val parts = payload.split('|')
        require(parts.size == 2) { "密文格式非法" }
        val iv = Base64.decodeToByteArray(parts[0])
        val data = Base64.decodeToByteArray(parts[1])
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(data)
    }
}
