package com.bastion.app.platform

/**
 * 操作系统级密钥存储抽象。
 * 桌面端：Windows DPAPI（CryptProtectData/CryptUnprotectData）保护一个随机 DEK，
 *         应用密钥（MDK）再由 DEK 经 AES-GCM 包裹后落盘。
 * 安卓端：Android Keystore。
 */
expect object KeyStorage {
    /**
     * 把一个随机生成的 DEK（32 字节）加密后持久化到磁盘。
     * 若已存在则返回 false。
     */
    fun storeDek(dek: ByteArray): Boolean

    /** 读取并解密持久化的 DEK；不存在时返回 null。 */
    fun loadDek(): ByteArray?

    /** 判断 DEK 是否已初始化。 */
    fun isInitialized(): Boolean
}
