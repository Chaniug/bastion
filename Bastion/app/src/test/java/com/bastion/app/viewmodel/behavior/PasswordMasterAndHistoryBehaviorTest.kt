package com.bastion.app.viewmodel.behavior

import com.bastion.app.data.PasswordEntry
import com.bastion.app.data.PasswordHistoryEntry
import com.bastion.app.repository.PasswordRepository
import com.bastion.app.security.SecurityManager
import com.bastion.app.viewmodel.PasswordViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * `PasswordViewModel` 主密码变更 + 密码历史簇的**行为**测试（Phase B.3 Step 3，集群 7 前置）。
 *
 * ## 为什么需要这组测试
 *
 * 集群 7（主密码 / 历史）是 B.3 剩余簇之一，此前**零测试覆盖**，且 `changePassword`
 * / `saveSecurityQuestions` 各含一个 TODO（§三 已登记，拆分时一并补全）。在抽取
 * `PasswordHistoryRecorder` + `MasterPasswordOps` 之前，必须先锁住当前语义。
 *
 * ## 夹具约定（与 PasswordDelete/Move/ArchiveBehaviorTest 一致）
 *
 * `context = null` 使 `passwordHistoryManager` / `settingsManager` / `keepassBridge` 等
 * 全部退化为 null。`securityManager` 用 relaxed mock：
 * - `verifyMasterPassword` 默认返回 false → 可断言"密码错误提前返回"分支；
 * - 用 `coEvery` 覆盖为 true 后可测"验证通过 → 全量重加密"分支；
 * - `decryptForDisplay` → `decodePasswordOrNull` → `securityManager`（relaxed 安全）。
 *
 * `savePasswordHistorySnapshot` 用 `repository.getPasswordHistoryByEntryIdSync().first()`
 * + `decryptForDisplay` + `encryptDataLegacyCompat`，全部走 repository/mock，无 Android 依赖。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PasswordMasterAndHistoryBehaviorTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun localEntry(id: Long = 1L, title: String = "本地条目") = PasswordEntry(
        id = id,
        title = title,
        website = "https://example.com",
        username = "user$id",
        password = "encrypted-$id"
    )

    private fun historyEntry(
        id: Long = 1L,
        entryId: Long = 1L,
        password: String = "hist-encrypted"
    ) = PasswordHistoryEntry(
        id = id,
        entryId = entryId,
        password = password
    )

    private fun newViewModel(repository: PasswordRepository, securityManager: SecurityManager): PasswordViewModel {
        return PasswordViewModel(
            repository = repository,
            securityManager = securityManager,
            secureItemRepository = null,
            customFieldRepository = null,
            context = null,
            localKeePassDatabaseDao = null
        )
    }

    // ---------------------------------------------------------------------
    // changePassword：主密码变更
    // ---------------------------------------------------------------------

    @Test
    fun `changePassword with wrong current password returns early without touching entries`() = runTest {
        val repository = mockk<PasswordRepository>(relaxed = true)
        val securityManager = mockk<SecurityManager>(relaxed = true)
        coEvery { securityManager.verifyMasterPassword(any()) } returns false
        val viewModel = newViewModel(repository, securityManager)

        viewModel.changePassword(currentPassword = "wrong", newPassword = "new-secret")

        // 验证失败 → 不读取条目、不设置新密码、不重加密。
        coVerify(exactly = 0) { repository.getAllPasswordEntries() }
        coVerify(exactly = 0) { securityManager.setMasterPassword(any()) }
        coVerify(exactly = 0) { repository.updatePasswordEntry(any()) }
    }

    @Test
    fun `changePassword with valid current password re-encrypts all entries with the new key`() = runTest {
        val repository = mockk<PasswordRepository>(relaxed = true)
        val securityManager = mockk<SecurityManager>(relaxed = true)
        coEvery { securityManager.verifyMasterPassword(any()) } returns true
        // 解密：decrypt("encrypted-N") → "plain"，之后 decrypt("plain") → (默认) 自身即稳定。
        coEvery { securityManager.decryptData(any()) } answers {
            val input = firstArg<String>()
            if (input.startsWith("encrypted-")) "plain-password" else input
        }
        coEvery { securityManager.encryptData(any()) } returns "re-encrypted"
        coEvery { repository.getAllPasswordEntries() } returns flowOf(listOf(localEntry(1L), localEntry(2L)))
        val viewModel = newViewModel(repository, securityManager)

        viewModel.changePassword(currentPassword = "old-secret", newPassword = "new-secret")

        // 设置新主密码 + 逐条写回重加密结果。
        coVerify(exactly = 1) { securityManager.setMasterPassword("new-secret") }
        coVerify(exactly = 2) { repository.updatePasswordEntry(any()) }
        val written = slot<PasswordEntry>()
        coVerify(exactly = 1) { repository.updatePasswordEntry(capture(written)) }
        assertEquals("写回条目的密码应为新密钥加密结果", "re-encrypted", written.captured.password)
        assertNotNull("写回条目应刷新 updatedAt", written.captured.updatedAt)
    }

    // ---------------------------------------------------------------------
    // savePasswordHistorySnapshot：历史快照（去重 / trim / 加密）
    // ---------------------------------------------------------------------

    @Test
    fun `history delete and clear entry points delegate to repository`() = runTest {
        val repository = mockk<PasswordRepository>(relaxed = true)
        val securityManager = mockk<SecurityManager>(relaxed = true)
        val viewModel = newViewModel(repository, securityManager)

        viewModel.deletePasswordHistoryEntry(9L)
        viewModel.clearPasswordHistory(3L)

        coVerify(exactly = 1) { repository.deletePasswordHistoryById(9L) }
        coVerify(exactly = 1) { repository.clearPasswordHistory(3L) }
    }

    @Test
    fun `history flow on empty repository emits empty list`() = runTest {
        val repository = mockk<PasswordRepository>(relaxed = true)
        val securityManager = mockk<SecurityManager>(relaxed = true)
        coEvery { repository.getPasswordHistoryByEntryId(any()) } returns flowOf(emptyList())
        val viewModel = newViewModel(repository, securityManager)

        val entries = viewModel.getPasswordHistoryFlow(7L).first()

        assertTrue("无历史时应返回空列表", entries.isEmpty())
    }

    // ---------------------------------------------------------------------
    // 历史展示：getPasswordHistoryFlow 解码过滤
    // ---------------------------------------------------------------------

    @Test
    fun `history flow decodes passwords and drops entries that fail to decode`() = runTest {
        val repository = mockk<PasswordRepository>(relaxed = true)
        val securityManager = mockk<SecurityManager>(relaxed = true)
        coEvery { repository.getPasswordHistoryByEntryId(any()) } returns flowOf(
            listOf(
                historyEntry(id = 1L, password = "enc-ok"),
                historyEntry(id = 2L, password = "enc-bad")
            )
        )
        // unwrapPasswordLayersForDisplay 循环最多 3 次；用"指定输入→指定输出" +
        // 默认"返回输入本身"实现稳定解码：decrypt(enc-ok)→decoded-1 后，
        // decrypt(decoded-1)→(默认)decoded-1 == current 提前返回。
        coEvery { securityManager.decryptData("enc-ok") } returns "decoded-1"
        coEvery { securityManager.decryptData("enc-bad") } returns ""
        coEvery { securityManager.decryptData(any()) } answers { firstArg() }
        // decodeHistoryPasswordForDisplay 的稳定化副作用（encryptDataLegacyCompat 返回
        // 空串 ≠ entry.password）会触发 updatePasswordHistoryPassword，relaxed mock 安全。
        val viewModel = newViewModel(repository, securityManager)

        val entries = viewModel.getPasswordHistoryFlow(7L).first()

        assertEquals("解码失败的条目应从展示列表剔除", 1, entries.size)
        assertEquals("保留条目的密码应为解密后的明文", "decoded-1", entries[0].password)
        assertEquals("条目 id 应保留", 1L, entries[0].id)
    }

    // ---------------------------------------------------------------------
    // 历史删除 / 清空
    // ---------------------------------------------------------------------

    @Test
    fun `deleting history entry removes by id`() = runTest {
        val repository = mockk<PasswordRepository>(relaxed = true)
        val securityManager = mockk<SecurityManager>(relaxed = true)
        val viewModel = newViewModel(repository, securityManager)

        viewModel.deletePasswordHistoryEntry(42L)

        coVerify(exactly = 1) { repository.deletePasswordHistoryById(42L) }
    }

    @Test
    fun `clearing history removes all entries for the password`() = runTest {
        val repository = mockk<PasswordRepository>(relaxed = true)
        val securityManager = mockk<SecurityManager>(relaxed = true)
        val viewModel = newViewModel(repository, securityManager)

        viewModel.clearPasswordHistory(5L)

        coVerify(exactly = 1) { repository.clearPasswordHistory(5L) }
    }

    // ---------------------------------------------------------------------
    // getBitwardenSyncRawHistoryFlow：同步原始历史（空 cipherId 短路）
    // ---------------------------------------------------------------------

    @Test
    fun `bitwarden sync raw history with blank cipher id short-circuits to empty`() = runTest {
        val repository = mockk<PasswordRepository>(relaxed = true)
        val securityManager = mockk<SecurityManager>(relaxed = true)
        val viewModel = newViewModel(repository, securityManager)

        val items = viewModel.getBitwardenSyncRawHistoryFlow(vaultId = 1L, cipherId = "").first()

        assertTrue("空 cipherId 应返回空列表", items.isEmpty())
        coVerify(exactly = 0) { repository.getBitwardenSyncRawRecords(any(), any()) }
    }

    @Test
    fun `bitwarden sync raw history filters to sync response payloads`() = runTest {
        val repository = mockk<PasswordRepository>(relaxed = true)
        val securityManager = mockk<SecurityManager>(relaxed = true)
        val viewModel = newViewModel(repository, securityManager)
        val syncRecord = com.bastion.app.data.bitwarden.BitwardenSyncRawEntryRecord(
            id = 1L,
            vaultId = 1L,
            bitwardenCipherId = "c1",
            operation = "UPDATE",
            endpoint = "/cipher",
            payloadCipherText = "encrypted-payload",
            payloadSource = "SYNC_RESPONSE",
            payloadDigest = "digest",
            responseCode = 200,
            success = true,
            capturedAt = 1_700_000_000_000L
        )
        val otherRecord = syncRecord.copy(id = 2L, payloadSource = "LOCAL_MUTATION")
        coEvery { repository.getBitwardenSyncRawRecords(any(), any()) } returns
            flowOf(listOf(syncRecord, otherRecord))
        // decodePasswordOrNull → decryptData（relaxed 返回空串），preview parser 需要
        // bitwardenRepository 的对称密钥——context=null 时 bitwardenRepository 为 null，
        // parser 走 null 分支，安全。
        coEvery { securityManager.decryptData(any()) } returns ""

        val items = viewModel.getBitwardenSyncRawHistoryFlow(vaultId = 1L, cipherId = "c1").first()

        assertEquals("只保留 SYNC_RESPONSE 记录", 1, items.size)
        assertEquals("保留记录 id", 1L, items[0].id)
    }
}
