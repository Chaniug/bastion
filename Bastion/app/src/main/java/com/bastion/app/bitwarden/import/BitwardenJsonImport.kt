package com.bastion.app.bitwarden.import

import com.bastion.app.bitwarden.crypto.BitwardenCrypto
import com.bastion.app.bitwarden.export.BitwardenEncryptedExport
import com.bastion.app.bitwarden.export.BitwardenPlainExport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Bitwarden 兼容 JSON 导入解析与解密。
 *
 * 与 [com.bastion.app.bitwarden.export.BitwardenJsonExporter] 完全对称：
 *  - 明文导入：{ encrypted:false, folders:[...], items:[...] }
 *  - 加密导入（密码保护）：{ encrypted:true, passwordProtected:true, salt, kdfType,
 *      kdfIterations, encKeyValidation_DO_NOT_EDIT, data, ... }
 *
 * 解密流程与 bw2keepass 的 encrypt_bitwarden_export 对称：
 *  PBKDF2-SHA256(password, utf8(salt), kdfIterations) -> masterKey
 *  HKDF-Expand(masterKey, "enc"/"mac") -> encKey/macKey
 *  data = AES-256-CBC + HMAC-SHA256(整个明文 JSON)
 */
object BitwardenJsonImport {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 仅解析信封头，判断是否密码保护加密导出 */
    fun isEncryptedExport(content: String): Boolean {
        return runCatching {
            val header = json.decodeFromString<BitwardenEnvelopeHeader>(content)
            header.encrypted && header.passwordProtected
        }.getOrDefault(false)
    }

    /** 解析明文导出文件内容 */
    fun parsePlain(content: String): BitwardenPlainExport {
        return json.decodeFromString(content)
    }

    /**
     * 解密并解析密码保护导出文件。
     * @throws SecurityException 密码错误时（校验串无法解密为 "Bitwarden"）
     */
    fun decryptAndParse(content: String, password: String): BitwardenPlainExport {
        val envelope = json.decodeFromString<BitwardenEncryptedExport>(content)

        // salt 以 base64 字符串形式存储，PBKDF2 使用其 UTF-8 字节，与 bw2keepass salt_mode='utf8' 一致
        val masterKey = BitwardenCrypto.deriveMasterKeyPbkdf2(
            password = password,
            salt = envelope.salt,
            iterations = envelope.kdfIterations
        )
        val key = BitwardenCrypto.stretchMasterKey(masterKey)

        val validation = BitwardenCrypto.decryptToString(envelope.encKeyValidation_DO_NOT_EDIT, key)
        if (validation != "Bitwarden") {
            // 清理内存中的密钥材料
            masterKey.fill(0)
            key.encKey.fill(0)
            key.macKey.fill(0)
            throw SecurityException("密码不正确，无法解密 Bitwarden 导出文件")
        }

        val plainJson = BitwardenCrypto.decryptToString(envelope.data, key)

        // 清理内存中的密钥材料
        masterKey.fill(0)
        key.encKey.fill(0)
        key.macKey.fill(0)

        return json.decodeFromString(plainJson)
    }

    @Serializable
    private data class BitwardenEnvelopeHeader(
        val encrypted: Boolean = false,
        val passwordProtected: Boolean = false
    )
}
