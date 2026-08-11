package com.bastion.app.platform

/**
 * Base64 编解码抽象。JVM 用 java.util.Base64，安卓用 android.util.Base64。
 */
expect object Base64 {
    fun encodeToString(bytes: ByteArray): String
    fun decodeToByteArray(input: String): ByteArray
    fun encodeUrlSafeToString(bytes: ByteArray): String
    fun decodeUrlSafeToByteArray(input: String): ByteArray
}
