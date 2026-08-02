package com.bastion.app.viewmodel

import com.bastion.app.bitwarden.cache.BitwardenOfflineSecretCache
import com.bastion.app.data.PasswordEntry

/**
 * Phase B.3 集群 2：Bitwarden 离线密钥缓存外观。
 *
 * 把原先散落在 [PasswordViewModel] 中的离线缓存编排（recall / remember /
 * clear / 批量清理 / 启动预热）收敛到一处，让 ViewModel 只保留薄委托。
 *
 * 本类是**纯委托**实现：每个方法的判空、空串短路、遍历顺序与返回值
 * 都与搬迁前逐行等价，不改变任何加解密、离线兜底或 TOTP 行为。
 *
 * 边界约束：
 * - 解密仍由调用方通过 [decodePassword] 注入。`decodePasswordOrNull`
 *   同时服务多个 PasswordProvider 且携带 ViewModel 的重认证副作用，
 *   不属于本外观职责，故保留在 ViewModel 内。
 * - [cache] 为 null 表示无 Context（单元测试构造场景），此时所有写操作
 *   静默跳过、读操作返回 null，与搬迁前的 `cache ?: return` 语义一致。
 */
internal class BitwardenOfflineSecretCacheFacade(
    private val cache: BitwardenOfflineSecretCache?,
    private val decodePassword: (String) -> String?
) {

    /** 缓存是否可用（等价于搬迁前的 `bitwardenOfflineSecretCache != null`）。 */
    fun isAvailable(): Boolean = cache != null

    /** 对应搬迁前的 `loadBitwardenOfflineCachedSecret`。 */
    fun recall(entry: PasswordEntry): String? {
        return cache?.recall(entry)
    }

    /** 对应搬迁前的 `rememberBitwardenOfflineCachedSecret`（含空串短路）。 */
    fun remember(entry: PasswordEntry, plainSecret: String) {
        if (plainSecret.isBlank()) return
        cache?.remember(entry, plainSecret)
    }

    /** 对应搬迁前清理单条目的 `bitwardenOfflineSecretCache?.clear(entryId)`。 */
    fun clear(entryId: Long) {
        cache?.clear(entryId)
    }

    /** 对应搬迁前 `clearBitwardenOfflineSecretCacheForVault` 中的逐条清理循环。 */
    fun clearAll(entryIds: Iterable<Long>) {
        val target = cache ?: return
        entryIds.forEach { entryId -> target.clear(entryId) }
    }

    /**
     * 对应搬迁前的 `rememberDecodedBitwardenSecrets`。
     *
     * 仅把已解密的明文填入内存缓存（`warmMemory`，不重新加密、不写盘），
     * 保持冷启动预热不触发 QueuedWork 主线程反堵的既有特性。
     */
    fun rememberDecodedSecrets(entries: List<PasswordEntry>): Int {
        val target = cache ?: return 0
        var warmedCount = 0
        entries.forEach { entry ->
            if (!entry.hasBitwardenCipherBinding() || entry.password.isBlank()) return@forEach
            val decoded = decodePassword(entry.password)
            if (!decoded.isNullOrBlank()) {
                target.warmMemory(entry, decoded)
                warmedCount += 1
            }
        }
        return warmedCount
    }
}
