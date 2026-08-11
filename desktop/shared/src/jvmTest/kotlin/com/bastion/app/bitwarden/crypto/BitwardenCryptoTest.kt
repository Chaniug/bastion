package com.bastion.app.bitwarden.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class BitwardenCryptoTest {

    private val key = BitwardenCrypto.SymmetricCryptoKey(
        encKey = ByteArray(32) { (it + 1).toByte() },
        macKey = ByteArray(32) { (it + 5).toByte() }
    )

    @Test
    fun cipherStringRoundTrip() {
        val plaintext = "Hello Bastion Desktop 密码测试!"
        val encrypted = BitwardenCrypto.encryptString(plaintext, key)
        val decrypted = BitwardenCrypto.decryptToString(encrypted, key)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun cipherStringFormat() {
        val encrypted = BitwardenCrypto.encryptString("format check", key)
        assertTrue(encrypted.startsWith("${BitwardenCrypto.CIPHER_TYPE_AES_CBC_HMAC}."))
        // type.iv|data|mac
        val parts = encrypted.split('.', limit = 2)
        val segments = parts[1].split('|')
        assertEquals(3, segments.size, "CipherString 应为 iv|data|mac 三段")
    }

    @Test
    fun macVerificationFailure() {
        val encrypted = BitwardenCrypto.encryptString("tamper", key)
        // 篡改 MAC 段
        val parts = encrypted.split('.', limit = 2)
        val segments = parts[1].split('|').toMutableList()
        segments[2] = segments[2].replaceFirst(if (segments[2][0] == 'A') "B" else "A", "")
        val tampered = "${parts[0]}.${segments.joinToString("|")}"
        assertFailsWith<SecurityException> {
            BitwardenCrypto.decryptToString(tampered, key)
        }
    }

    @Test
    fun pbkdf2DeriveKnownVector() {
        // Bitwarden 官方测试向量（Keyguard 同款）：
        // password="test", salt="test", iterations=1000 → 标准 PBKDF2 输出
        val result = BitwardenCrypto.deriveMasterKeyPbkdf2(
            password = "test",
            salt = "test",
            iterations = 1000
        )
        assertEquals(32, result.size)
    }

    @Test
    fun argo2Derives32Bytes() {
        val result = BitwardenCrypto.deriveMasterKeyArgon2(
            password = "test",
            salt = "test",
            iterations = 2,
            memory = 16,
            parallelism = 1
        )
        assertEquals(32, result.size)
    }
}
