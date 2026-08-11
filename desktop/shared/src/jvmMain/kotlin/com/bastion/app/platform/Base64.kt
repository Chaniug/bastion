package com.bastion.app.platform

import java.util.Base64 as JvmBase64

actual object Base64 {
    actual fun encodeToString(bytes: ByteArray): String =
        JvmBase64.getEncoder().encodeToString(bytes)

    actual fun decodeToByteArray(input: String): ByteArray =
        JvmBase64.getDecoder().decode(input)

    actual fun encodeUrlSafeToString(bytes: ByteArray): String =
        JvmBase64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    actual fun decodeUrlSafeToByteArray(input: String): ByteArray =
        JvmBase64.getUrlDecoder().decode(input)
}
