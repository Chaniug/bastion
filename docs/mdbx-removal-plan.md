# 移除 MDBX · 收敛到 KDBX + Bitwarden 双后端（实施计划）

> 状态：**计划待确认，尚未动工**。
> 决策背景：MDBX 为自研格式，仅作者本人测试使用，维护成本高；KDBX（开源标准、生态成熟）与 Bitwarden 保留为正式后端。
> 前置依赖：**Phase 0 必须先完成**，否则无法验证后续任何阶段的正确性。

## 一、决策依据（已核实）

### 1. MDBX 无真实用户

仅作者自测，因此无需「只读过渡 + 观察版本」的渐进退场流程，可直接分阶段移除。

### 2. KDBX 具备字段级冲突检测，能力缺口小于预期

初评曾认为「KDBX 为全量覆盖、无合并能力」，**此判断不准确，已修正**。

`app/src/main/java/com/bastion/app/keepass/KeePassChangeSetApplier.kt`（946 行）实现了变更集应用机制，含：

- `assertFieldPatchHasNoRemoteConflict(...)` —— 应用补丁前检测远端冲突
- `entryMatchesBaseField(entry, base)` —— 基线值比对（乐观并发控制）
- `applyFieldPatch` / `moveEntry` / `addAttachment` 等字段级操作

配合 `KeePassPendingChange.kt`(457) / `KeePassChangeSet.kt`(358) / `KeePassPendingFlush.kt`(353)，KDBX 侧为「拉取远端 → 字段级打补丁 → 基线冲突检测 → 回写」。

**实际能力差**（MDBX 有而 KDBX 无）：提交图历史回溯（`commits`/`commit_parents`）、每设备 HEAD 增量（`device_heads`/`sync_bundles`）、附件分块传输（`attachment_chunks`）、密钥世代轮换（`key_epochs`）。
**日常多设备编辑场景**：KDBX 的字段级冲突检测已可覆盖。

### 3. MDBX 是三后端中耦合最浅的

| 后端 | 代码量 | 引用文件数 |
| --- | --- | --- |
| Bitwarden | 20,553 行 | 228 |
| KeePass/KDBX | 24,120 行 | 211 |
| **MDBX** | **17,666 行** | **116** |

### 4. 数据安全：移除不会导致密码不可解密（关键）

`app/src/main/java/com/bastion/app/viewmodel/PasswordViewModel.kt:225-247` 显示三个 Provider 使用**完全相同**的加解密实参：

```kotlin
DefaultPasswordProvider(decodePassword = ::decodePasswordOrNull, encryptPassword = securityManager::encryptData)
KeePassPasswordProvider(decodePassword = ::decodePasswordOrNull, encryptPassword = securityManager::encryptData)
MdbxPasswordProvider (decodePassword = ::decodePasswordOrNull, encryptPassword = securityManager::encryptData)
```

**推论**：Room 中归属 MDBX 的条目与本地条目加密方式一致。移除时将 `mdbxDatabaseId` 置空即可使其降级为本地条目，**密码仍可正常解密，零数据丢失**。

此外，磁盘/WebDAV/OneDrive 上的 `.mdbx` 物理文件为外部文件，本次改动**不删除、不修改**，仅解除应用侧引用。

## 二、移除范围清单（已核实）

### 主源码：116 个文件引用 mdbx

MDBX 专有文件 20 个 / 17,666 行：

| 文件 | 行数 |
| --- | --- |
| `repository/MdbxVaultStore.kt` | 6636 |
| `ui/screens/MdbxManagerScreen.kt` | 3876 |
| `viewmodel/MdbxViewModel.kt` | 3301 |
| `ui/screens/MdbxOneDriveOpenScreen.kt` | 526 |
| `ui/screens/MdbxOneDriveCreateScreen.kt` | 494 |
| `ui/screens/MdbxWebDavOpenScreen.kt` | 493 |
| `ui/screens/MdbxVaultComponents.kt` | 433 |
| `ui/screens/MdbxWebDavCreateScreen.kt` | 295 |
| `ui/screens/MdbxLocalCreateScreen.kt` | 270 |
| `ui/screens/MdbxLocalOpenScreen.kt` | 240 |
| `data/LocalMdbxDatabase.kt` | 235 |
| `repository/MdbxVaultCrypto.kt` | 219 |
| `utils/WebDavMdbxFileSource.kt` | 155 |
| `mdbx/MdbxDiagLogger.kt` | 140 |
| `repository/MdbxRepository.kt` | 116 |
| `data/MdbxRemoteSource.kt` | 70 |
| `utils/OneDriveMdbxFileSource.kt` | 66 |
| `repository/MdbxAttachmentCekPayload.kt` | 44 |
| `domain/provider/MdbxPasswordProvider.kt` | 42 |
| `utils/MdbxFileSource.kt` | 15 |

引用分布（116 文件）：`ui` 58、`data` 18、`viewmodel` 11、`repository` 7、`utils` 5、`autofill_ng` 4、`domain` 3、`passkey` 2、`attachments` 2、`sync` 1、`security` 1、`navigation` 1、`mdbx` 1、`MainActivity.kt`、`BastionApplication.kt`。

### 数据模型字段

- `data/PasswordEntry.kt:80,82` —— `mdbxDatabaseId` / `mdbxFolderId`
- `data/SecureItem.kt:63,65` —— 同上
- `data/PasskeyEntry.kt:140,142` —— 同上
- `data/Category.kt:22` —— `mdbxDatabaseId`
- 归属解析器：`PasswordOwnership.kt`、`SecureItemOwnership.kt`、`PasskeyOwnership.kt`（均含 `hasMdbxBinding` 分支，属**三后端共享逻辑**，改动需谨慎）

### Room 数据库

- 版本 `73` → `74`（`data/PasswordDatabase.kt:48`）
- 实体：`LocalMdbxDatabase::class`、`MdbxRemoteSource::class`
- DAO：`localMdbxDatabaseDao()`、`mdbxRemoteSourceDao()`
- **先例**：`MIGRATION_11_12`「删除记账功能相关表」，本项目已有移除整块功能的迁移范式可参照

### 导航路由（`navigation/Screens.kt:167-173`）

`MdbxManager` / `MdbxLocalCreate` / `MdbxLocalOpen` / `MdbxWebDavCreate` / `MdbxWebDavOpen` / `MdbxOneDriveCreate` / `MdbxOneDriveOpen`

### 测试侧：18 个文件

其中 **26 处直接以字符串方式读取 `MdbxVaultStore.kt` 源文本**。

> ⚠️ **关键约束**：这些守卫测试用 `projectFile(...).readText()` 读主源码文件。删除 `MdbxVaultStore.kt` 会使其抛异常（文件不存在），而非断言失败。因此**测试清理必须与对应代码删除放在同一提交内**，不能滞后。

MDBX 专有测试（可整体删除）：`MdbxAndroidIntegrationGuardTest`、`MdbxAttachmentCekPayloadTest`、`MdbxPasswordObjectIdRegressionGuardTest`、`PasswordListMdbxFilterTest`、`PasswordAggregateMdbxFilterTest`。

需**局部改写**（含非 MDBX 断言，不可整体删）：`MultiPasswordSaveRegressionGuardTest`、`BiometricUnlockRegressionGuardTest`、`SensitiveLocalStorageGuardTest`、`TimelineSnapshotIntegrationGuardTest`、`PasswordOwnershipTest`、`StorageTargetOwnershipTest`、`DefaultSyncCoordinatorStatusTest`、`CardWalletSyncScopeTest`、`WalletListItemTest`、`BackupContentPolicyTest`、`NoteEditorViewModelTest`、`PasswordArchiveFilterControllerTest`、`PasswordTotpCrossDatabaseBindingGuardTest`。

## 三、实施阶段

> 每个阶段独立提交、独立跑 CI、独立核对测试失败**名单**（非仅数字）后再进入下一阶段。

### Phase 0：恢复 CI 信号【前置，必须先做】

**改动范围**：仅 `.github/workflows/main.yml`，不触碰任何业务代码。

**内容**：保留 `continue-on-error: true`（不阻塞出包），新增阈值校验步骤 —— 解析 `app/build/test-results/testDebugUnitTest/*.xml`，当 `failures + errors` 超过基线时显式 fail。

**当前基线**：`593 tests completed, 22 failed`

**理由**：现状为 `continue-on-error` 导致 CI 恒绿，22 个守卫已失效且无告警。上一轮 66 行重构引入 2 个新失败，靠人工扒日志才发现。**在无信号状态下执行 116 文件手术，等同于闭眼拆承重墙。**

**风险**：零（不改业务代码）

---

### Phase 1：移除 UI 与导航入口

**范围**：10 个 MDBX 专有 Screen 文件（共 ~6,627 行）、`navigation/Screens.kt` 7 条路由、`MainActivity.kt` 相关 import 与装配、其余 UI 文件中的 MDBX 条件分支（`ui/password` 14、`ui/components` 7、`ui/cardwallet` 3、`ui/vaultv2` 2 等）。

**同步删除**：`PasswordListMdbxFilterTest`、`PasswordAggregateMdbxFilterTest`；改写 `WalletListItemTest`、`CardWalletSyncScopeTest`。

**验收**：编译通过；功能入口不可达；测试失败名单相对基线只减不增。

**风险**：中低（UI 叶子层，不涉及数据）

---

### Phase 2：移除引擎层

**范围**：`MdbxVaultStore.kt`(6636)、`MdbxViewModel.kt`(3301)、`MdbxVaultCrypto.kt`、`MdbxRepository.kt`、`MdbxAttachmentCekPayload.kt`、`MdbxDiagLogger.kt`、3 个 FileSource、`MdbxPasswordProvider.kt`（同时从 `PasswordViewModel.kt:236` 的 `PasswordProviderRegistry` 注销）。

**同步删除**：`MdbxAndroidIntegrationGuardTest`、`MdbxAttachmentCekPayloadTest`、`MdbxPasswordObjectIdRegressionGuardTest`；改写 `MultiPasswordSaveRegressionGuardTest` 中全部 `mdbxStoreSource.contains(...)` 断言（含 `:1545` 等 26 处源文本读取）。

**验收**：编译通过；无残留 `MdbxVaultStore` 引用；测试失败名单只减不增。

**风险**：中（引擎层，但已与 UI 解耦）

---

### Phase 3：数据模型与 Room 迁移

**范围**：
1. 移除 4 个实体的 `mdbxDatabaseId` / `mdbxFolderId` 字段
2. 改写 3 个 Ownership 解析器的 `hasMdbxBinding` 分支（**三后端共享逻辑，最需谨慎**）
3. 共享子系统扫尾：`autofill_ng`(4)、`passkey`(2)、`attachments`(2)、`sync`(1)、`security`(1)、`dedup/DedupMergeService.kt`、`BackupContentPolicy`
4. Room `73 → 74` 迁移：
   - `UPDATE` 各表 `mdbx_database_id = NULL`（使原 MDBX 条目降级为本地条目，保留数据）
   - `DROP TABLE` MDBX 相关表
   - 从 `entities` 与 DAO 列表移除

**同步改写**：`PasswordOwnershipTest`、`StorageTargetOwnershipTest`、`TimelineSnapshotIntegrationGuardTest`、`BiometricUnlockRegressionGuardTest`、`SensitiveLocalStorageGuardTest`、`DefaultSyncCoordinatorStatusTest`、`BackupContentPolicyTest`、`NoteEditorViewModelTest`、`PasswordArchiveFilterControllerTest`、`PasswordTotpCrossDatabaseBindingGuardTest`。

**验收**：编译通过；Room 迁移测试通过；升级安装后原 MDBX 条目以本地条目形式存在且密码可解密。

**风险**：中高（涉及 DB 迁移与共享归属逻辑）—— 需在真机/模拟器验证升级路径，不能只靠单测。

## 四、预期收益

| 指标 | 当前 | 移除后 |
| --- | --- | --- |
| MDBX 专有代码 | 17,666 行 | 0 |
| 引用 MDBX 的文件 | 116 | 0 |
| 存储后端数量 | 3（KDBX / Bitwarden / MDBX） | 2 |
| 6000+ 行巨型文件 | 3（含 `MdbxVaultStore.kt` 6636） | 2 |
| 需自行维护的私有格式 | 1 | 0 |

同时消除「同一功能实现三遍」的重复成本 —— 这是巨型文件与 UI 分支复杂度的主要成因之一。

## 五、待确认事项

1. **测试库数据**：作者本机是否有需保留的 MDBX 测试库？若有，建议 Phase 3 前手动导出（现有通路：`movePasswordsToKeePassDatabase()` 可将条目搬运至 KDBX 库）。
2. **是否需要一次性导出器**：若测试数据量大，可在 Phase 1 前先做「MDBX → KDBX 一键导出」（纯新增代码，零破坏）。若数据可弃则跳过。
3. **多设备增量同步**：确认接受降级为 KDBX 的字段级冲突检测方案（失去提交图历史回溯与附件分块增量传输）。

## 六、执行纪律

- 每阶段单独提交，提交后必须监控 Actions；
- **不以 Actions 绿色作为通过依据** —— 必须拉日志核对 `NNN tests completed, MM failed`，并将失败**名单**与上一阶段 diff（仅比数字会漏掉「修好一个、坏一个」的抵消）；
- 任一阶段出现失败名单**新增**项，立即定位；无法快速修复则 revert 该阶段；
- 相关背景见 `docs/ci-test-signal-and-guard-test-debt.md`。
