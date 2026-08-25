# Bitwarden 锁/解锁入口重构（2026-08）

## 背景
真机反馈：Bastion app 里 Bitwarden 的「锁定当前数据库」「重新解锁当前数据库」是无效操作。根因：

- `BitwardenRepository.forceLock`（`BitwardenRepository.kt:673-678`）只清**内存** `symmetricKeyCache` + `accessTokenCache` + 置 `is_locked=1`，**本地 cipher/passkey/密码表一行不动**。
- 本地密码密文是用 **app 级 MDK（SecurityManager）** 加密的（`BitwardenSyncService.kt:2304-2337`），与 Bitwarden 锁正交。
- DAO 查询不按锁过滤、UI 也不隐藏。
- 所以只要 Bastion app 本身没锁，Bitwarden 锁就完全没感知——Toast 一闪、卡片还显示"已解锁"。
- "重新解锁"是纯本地 KDF 校验（`BitwardenRepository.kt:542-626`），不解锁服务器、不查 app 锁；解锁后还自动触发一次全量 sync。

## 结论
Bitwarden 这把锁既不保护数据（数据由 app 锁/MDK 保护），也不真断网络。**统一用 Bastion app 锁就够了**。

## 改动（A1 方案）

### 删除的 UI 入口
- `PasswordListTopSection.kt`：移除「重新解锁」「锁定」顶部菜单项 + 对话框 + state + 3 个 import
- `VaultV2Pane.kt`：同上
- `BitwardenSettingsScreen.kt`：移除 `VaultCard` 的 `onLock`/`onUnlock` 参数、签名、内部两个按钮、调用处的 lambda、`showUnlockDialog`/`vaultToUnlock` state、第一个 `UnlockVaultDialog`
- `PasswordTopActionsMenu.kt`：删除无引用的 `BitwardenReunlockTopActionsMenuItem` / `BitwardenLockTopActionsMenuItem` Composable 定义

### 保留的 UI
- `BitwardenSettingsScreen` 的「**永不锁定**」安全校验解锁对话框（`showNeverLockUnlockDialog`）——这是**合法的**密码验证（启用"永不锁定"前必须验证），不是空操作解锁，**保留**。
- `BitwardenRepository.forceLock` / `unlock` / `lock` 与 `BitwardenViewModel.lock` / `unlock` **内部方法保留**，供程序化使用。

### WebDAV 恢复的副作用（已知局限）
- `WebDavHelper.kt:5312` 删除了 `bitwardenRepository.forceLock(restoredId)` 调用。
- 恢复出来的 vault 保留 DB `is_locked=true`（由 `BitwardenVault` 构造 `isLocked=true` 设定），内存缓存若残留旧 key/token 也被 `is_locked` 闸住不会被使用。
- **影响**：A1 后 WebDAV 恢复出的 vault 无 UI 可解锁（除非删除重加或登出重登）。
- **后续如需"恢复即用"**：需让 WebDAV 恢复流程自动 populate 对称密钥（用 backup 解密出的 masterKey 直接喂 `unlock` 流程，跳过 KDF 重派生），单独 PR。

## 验证
- Android CI debug + CodeQL Advanced 需保持 success。
- 真机（荣耀/安卓17）：顶部菜单不应再出现「重新解锁」「锁定」；设置页 VaultCard 不再有锁/解锁按钮，但「永不锁定」切换仍需输入密码；其余 Bitwarden 操作（同步/清缓存/登出）正常。
