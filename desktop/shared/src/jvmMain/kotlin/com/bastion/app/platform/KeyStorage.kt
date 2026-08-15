package com.bastion.app.platform

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference
import java.io.File
import java.security.SecureRandom

/**
 * Windows DPAPI 实现（JNA 调 crypt32.dll CryptProtectData/CryptUnprotectData）。
 * 只用于保护随机 DEK；应用密钥由 DEK 再经 AES-GCM 包裹。
 */
actual object KeyStorage {

    private val dekFile: File by lazy { File(PathProvider.resolve("key.blob")) }
    private val random = SecureRandom()

    actual fun storeDek(dek: ByteArray): Boolean {
        if (dekFile.exists()) return false
        val encrypted = cryptProtect(dek, "BastionDesktop/KeyStorage")
        dekFile.parentFile?.mkdirs()
        dekFile.writeBytes(encrypted)
        return true
    }

    actual fun loadDek(): ByteArray? {
        if (!dekFile.exists()) return null
        return try {
            val bytes = dekFile.readBytes()
            cryptUnprotect(bytes, "BastionDesktop/KeyStorage")
        } catch (t: Throwable) {
            null
        }
    }

    actual fun isInitialized(): Boolean = dekFile.exists()

    // ---- DPAPI ----

    @Structure.FieldOrder("cbData", "pbData")
    class DataBlob : Structure {
        @JvmField var cbData: Int = 0
        @JvmField var pbData: Pointer? = null

        constructor() : super()
        constructor(size: Int) : super() {
            cbData = size
            pbData = Memory(size.toLong())
        }
    }

    private interface Crypt32 : Library {
        companion object {
            val INSTANCE: Crypt32 = Native.load("crypt32", Crypt32::class.java)
        }

        fun CryptProtectData(
            pDataIn: DataBlob,
            szDataDescr: WString?,
            pOptionalEntropy: DataBlob?,
            pvReserved: Pointer?,
            pPromptStruct: Pointer?,
            dwFlags: Int,
            pDataOut: DataBlob
        ): Boolean

        fun CryptUnprotectData(
            pDataIn: DataBlob,
            ppszDataDescr: PointerByReference?,
            pOptionalEntropy: DataBlob?,
            pvReserved: Pointer?,
            pPromptStruct: Pointer?,
            dwFlags: Int,
            pDataOut: DataBlob
        ): Boolean
    }

    /**
     * LocalFree 由 kernel32.dll 导出（crypt32.dll 并不导出它）。
     * 若把 LocalFree 声明在 Crypt32 接口里，JNA GetProcAddress 会失败并抛
     * UnsatisfiedLinkError: Error looking up function 'LocalFree'，
     * 导致应用在首次 DPAPI 调用时崩溃（启动即崩，jpackage 启动器报 "Failed to launch JVM"）。
     */
    private interface Kernel32 : Library {
        fun LocalFree(hMem: Pointer?): Pointer?

        companion object {
            val INSTANCE: Kernel32 = Native.load("kernel32", Kernel32::class.java)
        }
    }

    private fun cryptProtect(data: ByteArray, entropy: String): ByteArray {
        val input = DataBlob(data.size).apply {
            pbData!!.write(0, data, 0, data.size)
        }
        val ent = DataBlob(entropy.toByteArray(Charsets.UTF_8).size).apply {
            pbData!!.write(0, entropy.toByteArray(Charsets.UTF_8), 0, entropy.toByteArray(Charsets.UTF_8).size)
        }
        val output = DataBlob()
        val ok = Crypt32.INSTANCE.CryptProtectData(input, WString("Bastion"), ent, null, null, 0, output)
        if (!ok) throw IllegalStateException("DPAPI CryptProtectData failed, last error=${Native.getLastError()}")
        return readBlob(output)
    }

    private fun cryptUnprotect(encrypted: ByteArray, entropy: String): ByteArray {
        val input = DataBlob(encrypted.size).apply {
            pbData!!.write(0, encrypted, 0, encrypted.size)
        }
        val ent = DataBlob(entropy.toByteArray(Charsets.UTF_8).size).apply {
            pbData!!.write(0, entropy.toByteArray(Charsets.UTF_8), 0, entropy.toByteArray(Charsets.UTF_8).size)
        }
        val output = DataBlob()
        val ok = Crypt32.INSTANCE.CryptUnprotectData(input, null, ent, null, null, 0, output)
        if (!ok) throw IllegalStateException("DPAPI CryptUnprotectData failed, last error=${Native.getLastError()}")
        return readBlob(output)
    }

    private fun readBlob(blob: DataBlob): ByteArray {
        try {
            val size = blob.cbData
            val ptr = blob.pbData ?: return ByteArray(0)
            return ptr.getByteArray(0, size)
        } finally {
            Kernel32.INSTANCE.LocalFree(blob.pbData)
        }
    }
}
