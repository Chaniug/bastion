# Bastion 架构重构：移除 MDBX，保留 KDBX + Bitwarden + BastionLocal 三后端

> **文档目的**：为「移除 MDBX 自研引擎，保留 KDBX + Bitwarden + BastionLocal 三后端」提供完整的技术设计与实施路线，供多 agent 接力开发。
>
> **创建时间**：2026-08-01
> **最后更新**：2026-08-01（保留 BastionLocal，取消 Phase B）
> **状态**：架构设计已完成，Phase A 执行中
> **决策人**：项目维护者
> **仓库**：https://github.com/Chaniug/bastion（dev 分支开发，验证后合并 main）

---

## 一、架构目标

### 1.1 现状

Bastion 当前有 **4 种存储后端**：

| 后端 | 形态 | 同步能力 | 代码量 | 状态 |
| --- | --- | --- | --- | --- |
| **BastionLocal** | App 内置 Room 本地库 | 无 | ~215 处引用 | 默认 fallback，承载用户主要本地数据 |
| **KDBX (KeePass)** | .kdbx 文件 | 本地 + WebDAV + OneDrive + Google Drive | ~24,120 行 / 211 文件 | 功能完整，含字段级冲突检测 |
| **MDBX** | 自研 git 式版本库引擎 | WebDAV + OneDrive | ~17,666 行 / 116 文件 | 仅作者自测，维护成本高 |
| **Bitwarden** | 云端 | 云端 | ~20,553 行 / 228 文件 | 功能完整，不动 |

### 1.2 目标架构

**移除 MDBX，保留 3 种后端**：

| 后端 | 角色 | 同步能力 | 说明 |
| --- | --- | --- | --- |
| **BastionLocal** | App 内置本地库（默认 fallback） | 无 | 保留不动。开箱即用，不与 KDBX/Bitwarden 冲突 |
| **KDBX (KeePass)** | 可同步的本地文件库（取代 MDBX） | 本地 + WebDAV + OneDrive + Google Drive | 保留不动。功能已完整 |
| **Bitwarden** | 云端密码库 | 云端 | 保留不动 |

> **为什么不删 BastionLocal？** BastionLocal 是 App 默认内置库，开箱即用，承载未绑定外部库的纯本地条目。它与 KDBX/Bitwarden 是互斥平行关系（`toStorageTarget()` 的 `when` 优先级保证），不缓存 Bitwarden/KDBX 数据，不产生冲突。删除它需要数据迁移且有丢失风险，收益为零。

### 1.3 设计原则

- **UI 风格不变**：现有界面布局、交互模式、视觉风格保持不动，仅移除 MDBX 相关的 UI 元素。
- **KDBX 能力已就绪**：KDBX 已有完整的远程同步管线（`RemoteKeePassSyncService` + `KeePassRemoteUploadWorker` + `KeePassChangeSetApplier` 字段级冲突检测 + `KeePassRemoteRebase` 重放），**无需新建同步能力**，仅删除 MDBX 代码即可。
- **零数据丢失**：MDBX 条目的加密方式与本地条目完全相同（`MdbxPasswordProvider` 使用相同的 `decodePasswordOrNull` + `securityManager::encryptData`），移除时将 `mdbxDatabaseId` 置空即可降级为本地条目，密码仍可解密。
- **BastionLocal 保留**：不删 BastionLocal，无需数据迁移，用户开箱即用。

---

## 二、KDBX 已有能力清单（无需新建）

KDBX 已实现完整的本地 + 远程同步管线，以下能力**均已就绪**：

### 2.1 文件源抽象

| 文件 | 能力 |
| --- | --- |
| `KeePassFileSource.kt` | 接口：stat / read / write / listChildren / createFile / testConnection + versionToken / etag 乐观锁 |
| `WebDavKeePassFileSource.kt` (325 行) | WebDAV 协议，含 `expectedVersion` 乐观锁比对 |
| `OneDriveKeePassFileSource.kt` (596 行) | OneDrive API，含 4MB 以上 uploadSession 分块上传 |
| `GoogleDriveKeePassFileSource.kt` (528 行) | Google Drive API（MDBX 没有的云） |

### 2.2 同步引擎

| 文件 | 能力 |
| --- | --- |
| `RemoteKeePassSyncService.kt` (228 行) | 全相位状态机：IDLE → COMPARING → DOWNLOADING → UPLOADING → CONFLICT / FAILED |
| `KeePassRemoteUploadWorker.kt` (295 行) | WorkManager 唯一队列、指数退避、最多 25 步 drain、最多 3 次重试 |
| `KeePassRemoteRebase.kt` | 拉取远端 → 重放 pending ChangeSet → 回写 |
| `KeePassChangeSetApplier.kt` (947 行) | 字段级冲突检测：`assertFieldPatchHasNoRemoteConflict` + `entryMatchesBaseField` 基线比对 |
| `KeePassPendingFlush.kt` (354 行) | pending change 验证 + ready/blocked 分流 |
| `KeePassPendingChange.kt` (457 行) | 变更集数据模型 |
| `KeePassChangeSet.kt` (358 行) | 变更集应用机制 |

### 2.3 浏览器 UI

| 文件 | 能力 |
| --- | --- |
| `LocalKeePassWebDavBrowser.kt` | WebDAV 文件浏览与选择 |
| `LocalKeePassOneDriveBrowser.kt` | OneDrive 文件浏览与选择 |

### 2.4 Room 实体与 DAO

| 实体 | 表名 | 用途 |
| --- | --- | --- |
| `LocalKeePassDatabase` | `local_keepass_databases` | 已注册的 KDBX 数据库元数据 |
| `KeepassRemoteSource` | `keepass_remote_sources` | 远程同步源凭据（WebDAV/OneDrive/GoogleDrive） |
| `KeepassRemoteSyncState` | `keepass_remote_sync_states` | 每库同步状态 |
| `KeePassGroupSyncConfig` | — | 分组级同步配置 |
| `KeePassPendingChange` | `keepass_pending_changes` | entry 级待同步变更 |

### 2.5 MDBX 独有但可放弃的能力

以下 6 项是 MDBX 独有的「数据模型层」能力，KDBX 走轻量路线（事件重放、用后即弃），**这些能力在双后端架构中可安全放弃**：

1. Commit 图历史（commits + commit_parents DAG + listCommitDiff + revertCommit）
2. 设备 HEAD / 分支（device_heads + branches，多端并行编辑追踪）
3. Key epoch 轮换（key_epochs 表 + wrapped_epoch_key_ct）
4. 快照与回滚（snapshots 表 + createSnapshot/revertToSnapshot + autoPrune）
5. SyncBundle 导入导出（exportSyncBundle/importSyncBundle + 哈希校验）
6. 附件分块存储 + 冲突持久化队列（attachment_chunks + conflicts 表 + resolveConflict）

> **评估**：这些能力对日常多设备编辑场景非必需。KDBX 的字段级冲突检测（`KeePassChangeSetApplier`）已可覆盖日常合并需求。用户已确认接受此降级。

---

## 三、需要移除的代码

### 3.1 MDBX 专有文件（整删，17 个）

#### 主源码（12 个，已在 Phase A 删除）

| 文件 | 行数 | 用途 |
| --- | --- | --- |
| `repository/MdbxVaultStore.kt` | 6636 | MDBX 核心引擎（commit 图、快照、同步） |
| `viewmodel/MdbxViewModel.kt` | 3301 | MDBX UI 状态管理 |
| `data/LocalMdbxDatabase.kt` | ~400 | Room 实体：`local_mdbx_databases` 表（25 字段） |
| `data/MdbxRemoteSource.kt` | ~100 | Room 实体：`mdbx_remote_sources` 表（9 字段） |
| `repository/MdbxVaultCrypto.kt` | ~500 | MDBX 加密/解密 |
| `repository/MdbxRepository.kt` | ~300 | MDBX 数据访问层 |
| `repository/MdbxAttachmentCekPayload.kt` | ~50 | 附件加密载荷 |
| `domain/provider/MdbxPasswordProvider.kt` | ~80 | MDBX 密码 Provider |
| `mdbx/MdbxDiagLogger.kt` | ~60 | 诊断日志 |
| `utils/MdbxFileSource.kt` | 15 | MDBX 文件源接口 |
| `utils/WebDavMdbxFileSource.kt` | ~40 | WebDAV 实现 |
| `utils/OneDriveMdbxFileSource.kt` | ~40 | OneDrive 实现 |

#### 测试文件（5 个，已在 Phase A 删除）

| 文件 | 用途 |
| --- | --- |
| `androidTest/.../MdbxVaultStoreInstrumentedCompatibilityTest.kt` | 集成测试 |
| `test/.../repository/MdbxAttachmentCekPayloadTest.kt` | 单元测试 |
| `test/.../ui/PasswordListMdbxFilterTest.kt` | UI 过滤测试 |
| `test/.../ui/password/PasswordAggregateMdbxFilterTest.kt` | 聚合过滤测试 |
| `test/.../viewmodel/MdbxPasswordObjectIdRegressionGuardTest.kt` | 回归守卫测试 |

> **注**：Phase 1a 已删除 8 个 MDBX Screen 文件 + `MdbxAndroidIntegrationGuardTest.kt`。

### 3.2 含 MDBX 分支的密封接口（13 个 + 1 enum）

移除 MDBX 需要从以下密封接口/枚举中删除 MDBX 分支定义，并同步删除全仓所有 `when` 分支引用：

| 密封接口/枚举 | 定义文件 | MDBX 分支 | BastionLocal 分支 | `when` 引用数 |
| --- | --- | --- | --- | --- |
| `StorageTarget` | `data/model/StorageTarget.kt` | `Mdbx` | `BastionLocal` | ~10 |
| `UnifiedCategoryFilterSelection` | `ui/components/UnifiedCategoryFilterBottomSheet.kt:143` | `MdbxDatabaseFilter`, `MdbxFolderFilter` | `Local` | ~44 |
| `CategoryFilter` | `viewmodel/PasswordViewModel.kt:84` | `MdbxDatabase`, `MdbxFolderFilter` | `Local` | ~9 文件 |
| `TotpCategoryFilter` | `viewmodel/TotpViewModel.kt:67` | `MdbxDatabase` | `Local` | ~12 |
| `NoteCategoryFilter` | `ui/screens/NoteCategoryFilterModels.kt:6` | `MdbxDatabase` | `Local` | ~8 |
| `CreateDialogTarget`（enum） | `ui/components/CreateCategoryDialog.kt:738` | `Mdbx` | `Local` | ~13 |
| `UnifiedMoveCategoryTarget` | `ui/components/UnifiedMoveToCategoryBottomSheet.kt:71` | `MdbxDatabaseTarget`, `MdbxFolderTarget` | `BastionCategory` | ~61 |
| `MovePickerSource` | `ui/components/UnifiedMoveToCategoryBottomSheet.kt:82` | `MdbxDatabase` | `BastionLocal` | ~7 |
| `StoragePickerSource` | `ui/components/MultiStorageTargetPickerBottomSheet.kt:80` | `MdbxDatabase` | `BastionLocal` | ~5 |
| `PasswordOwnership` | `data/PasswordOwnership.kt:3` | `Mdbx` | `BastionLocal` | ~3 |
| `SecureItemOwnership` | `data/SecureItemOwnership.kt:3` | `Mdbx` | `BastionLocal` | ~5 |
| `PasskeyOwnership` | `data/PasskeyOwnership.kt:3` | `Mdbx` | `BastionLocal` | ~1 |
| `PasswordSource` | `domain/provider/PasswordSource.kt:3` | `Mdbx` | `Local` | ~48 |
| `DedupMergeTarget` | `data/dedup/DedupModels.kt:23` | `MdbxDatabase` | `BastionLocal` | ~7 |

### 3.3 共享文件中的 MDBX 引用（~96 个文件，~1,994 行）

按类别分布：

| 类别 | 文件数 | 引用行数 | 代表文件 |
| --- | --- | --- | --- |
| 筛选/过滤 | 5 | 91 | `UnifiedCategoryFilterBottomSheet.kt`, `PasswordDatabaseFiltersSection.kt` |
| 选择器/移动器 | 7 | 202 | `MultiStorageTargetPickerBottomSheet.kt`, `PasswordBatchMoveSupport.kt` |
| 创建/编辑页 | 8 | 341 | `AddEditPasswordScreen.kt`, `CreateCategoryDialog.kt`, `PasskeyCreateActivity.kt` |
| 页面/列表 | 25 | 538 | `VaultV2Pane.kt`(116), `SimpleMainScreen.kt`(51), `PasskeyListScreen.kt`(51) |
| ViewModel | 9 | 254 | `PasswordViewModel.kt`(69), `TotpViewModel.kt`(58) |
| 辅助类型 | 12 | 84 | `DedupMergeService.kt`, `CategoryManagementState.kt` |
| 其他 | 32 | 484 | `MainActivity.kt`(68), `PasswordRepository.kt`(46) |

### 3.4 Room 数据库变更

#### 当前状态

- Room 版本：**73**
- `@Database` 注解仍引用 `LocalMdbxDatabase::class` 和 `MdbxRemoteSource::class`（需移除）
- 4 个共享实体含 `mdbxDatabaseId` / `mdbxFolderId` 字段

#### 需要移除的字段

| 实体 | 字段 | 类型 | 索引 |
| --- | --- | --- | --- |
| `PasswordEntry` | `mdbxDatabaseId` | `Long? = null` | 单列索引 + 复合索引 |
| `PasswordEntry` | `mdbxFolderId` | `String? = null` | — |
| `SecureItem` | `mdbxDatabaseId` | `Long? = null` | 单列索引 + 复合索引 |
| `SecureItem` | `mdbxFolderId` | `String? = null` | — |
| `PasskeyEntry` | `mdbxDatabaseId` | `Long? = null` | 单列索引 |
| `PasskeyEntry` | `mdbxFolderId` | `String? = null` | — |
| `Category` | `mdbxDatabaseId` | `Long? = null` | — |

#### 需要删除的表

| 表名 | 实体 | 字段数 |
| --- | --- | --- |
| `local_mdbx_databases` | `LocalMdbxDatabase` | 25（vault 元数据、Tiga 模式、解锁方式、同步状态） |
| `mdbx_remote_sources` | `MdbxRemoteSource` | 9（WebDAV/OneDrive 凭据） |

---

## 四、实施路线

### Phase A：整删 MDBX（安全、自包含、无数据迁移）

> **风险**：低。MDBX 无真实用户数据，仅作者自测。
> **验证**：CI 编译闸门 + 测试基线。

#### A.1 删除 17 个 MDBX 专有文件 ✅（已完成）

12 个主源码 + 5 个测试文件，已通过 `git rm` 删除。

#### A.2 移除 `@Database` 注解中的 MDBX 实体引用

在 `PasswordDatabase.kt` 的 `@Database(entities = [...])` 中删除：
```kotlin
LocalMdbxDatabase::class,
MdbxRemoteSource::class,
```

#### A.3 移除密封分支定义 + 全部 `when` 分支

对上表 13 个密封接口 + 1 个 enum，逐一：
1. 删除 `data class MdbxXxx(...) : Interface { ... }` 分支定义
2. 删除全仓所有 `is Xxx.MdbxXxx -> ...` 穷尽 `when` 分支
3. 删除 `mdbxDatabaseId != null -> StorageTarget.Mdbx(...)` 类的条件分支

**关键文件**（分支定义所在）：
- `data/model/StorageTarget.kt`（5 个 `when` + 1 个 `data class`）
- `ui/components/UnifiedCategoryFilterBottomSheet.kt`（2 个 `data class` + 多个 `when`）
- `viewmodel/PasswordViewModel.kt`（2 个 `data class` + 多个 `when`）
- 其余 11 个文件各 1-2 个分支定义

**辅助脚本**：`/workspace/remove_mdbx_arms.py` 可自动删除 `is X.Mdbx ->` 分支和 `data class Mdbx` 定义。

#### A.4 清理 ~96 个共享文件中的 MDBX 引用

按以下模式批量移除：
- `import com.bastion.app.data.LocalMdbxDatabase` → 删
- `import com.bastion.app.viewmodel.MdbxViewModel` → 删
- `import com.bastion.app.repository.MdbxStoredFolderEntry` → 删
- `mdbxDatabases: List<...LocalMdbxDatabase>,` 参数定义 → 删
- `mdbxDatabases = mdbxDatabases,` 调用传参 → 删
- `mdbxViewModel: MdbxViewModel? = null,` 参数定义 → 删
- `mdbxViewModel = mdbxViewModel,` 调用传参 → 删
- `selectedMdbxDatabaseId: Long?,` 参数定义 → 删
- `selectedMdbxFolders: List<MdbxStoredFolderEntry>,` 参数定义 → 删
- `params.mdbxDatabases.forEach { ... }` UI 块 → 删
- `MdbxSyncTopActionsMenuItem(...)` 同步按钮 → 删
- `is StorageTarget.Mdbx -> { ... }` 移动/创建分支 → 删
- `CategoryFilter.MdbxDatabase(...)` / `CategoryFilter.MdbxFolderFilter(...)` 构造 → 删
- `UnifiedCategoryFilterSelection.MdbxDatabaseFilter(...)` / `.MdbxFolderFilter(...)` 构造 → 删

**保留**：KDBX / Bitwarden / BastionLocal 的所有逻辑不动。

#### A.5 清理 MainActivity 的 MDBX 装配

在 `MainActivity.kt` 中：
- 删除 `val mdbxViewModel: MdbxViewModel = viewModel { ... }` 实例化
- 删除 `mdbxViewModel = mdbxViewModel` 传参
- 删除 `mdbxDatabases = ...` 获取与传参
- 删除 `localMdbxViewModel = mdbxViewModel` 传参

#### A.6 Room 迁移 73 → 74

新增 `MIGRATION_73_74`，参照 `MIGRATION_11_12` 删表先例：
```kotlin
private val MIGRATION_73_74 = object : Migration(73, 74) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // 1. DROP MDBX 专有表
        database.execSQL("DROP TABLE IF EXISTS local_mdbx_databases")
        database.execSQL("DROP TABLE IF EXISTS mdbx_remote_sources")

        // 2. 重建 password_entries 删除 mdbx_database_id / mdbx_folder_id 列
        //    （SQLite < 3.35 不支持 DROP COLUMN，需建新表迁移）
        database.execSQL("""
            CREATE TABLE password_entries_new (
                ... (所有字段，去掉 mdbx_database_id 和 mdbx_folder_id)
            )
        """)
        database.execSQL("""
            INSERT INTO password_entries_new
            SELECT (所有字段，去掉 mdbx_database_id 和 mdbx_folder_id)
            FROM password_entries
        """)
        database.execSQL("DROP TABLE password_entries")
        database.execSQL("ALTER TABLE password_entries_new RENAME TO password_entries")
        // 重建索引...

        // 3. 同理处理 secure_items、passkeys、categories
    }
}
```

将 `version = 73` 改为 `version = 74`，在 `addMigrations(...)` 末尾添加 `MIGRATION_73_74`。

#### A.7 CI 验证

- **编译闸门**（Build Debug APK）必须通过
- **测试基线**：当前 19，删 MDBX 测试后继续下调（只降不升）
- 本地无 Android SDK，全部以 GitHub Actions 日志为准

---

## 五、`StorageTarget` 目标设计（Phase A 完成后）

移除 MDBX 后，`StorageTarget` 保留 3 个分支：

```kotlin
sealed interface StorageTarget {
    val stableKey: String

    data class BastionLocal(val categoryId: Long?) : StorageTarget {
        override val stableKey: String = "local:${categoryId ?: "root"}"
    }

    data class KeePass(
        val databaseId: Long,
        val groupPath: String?
    ) : StorageTarget {
        override val stableKey: String = "keepass:$databaseId:${groupPath.orEmpty()}"
    }

    data class Bitwarden(
        val vaultId: Long,
        val folderId: String?
    ) : StorageTarget {
        override val stableKey: String = "bitwarden:$vaultId:${folderId.orEmpty()}"
    }
}
```

> **BastionLocal 保留**：它是 App 默认内置库，与 KDBX/Bitwarden 互斥平行（`toStorageTarget()` 的 `when` 优先级保证），不缓存其他后端数据，不产生冲突。

---

## 六、CI 验证策略

### 6.1 编译闸门

GitHub Actions 的 `Build Debug APK (build gate)` 步骤是**硬性编译闸门**——任何未解析符号或穷尽 `when` 缺失分支都会导致编译失败。

### 6.2 测试基线闸门

`.github/workflows/main.yml` 中的 `Enforce unit test failure baseline` 步骤：
- 当前基线：**19**（Phase 1a 后锁定）
- 删 MDBX 测试后继续下调（只降不升）
- 若失败数 > 基线 → CI fail（阻止新回归）
- 若失败数 < 基线 → 提示下调

### 6.3 无本地编译环境

本地环境无 Android SDK（`ANDROID_HOME` 未设置），**全部依赖 CI 验证**。推送后观察 GitHub Actions 日志，编译失败时根据错误信息修正。

### 6.4 GitHub API 注意事项

本地环境中 `api.github.com` 与 `github.com` IP 不同，需在 `/etc/hosts` 中分别 pin：
```
140.82.113.3 github.com
20.205.243.168 api.github.com
```
否则 `gh run list` / `gh run watch` 会 TLS 超时。`git push` 偶发 GnuTLS recv error，需重试。

---

## 七、当前进度

| 阶段 | 状态 | 提交 |
| --- | --- | --- |
| Phase 0：CI 回归基线闸门 | ✅ 完成 | `e759d188` |
| Phase 1a：删除 MDBX 屏 + 导航入口 | ✅ 完成 | `e475d057` |
| 基线锁定 22 → 19 | ✅ 完成 | `f9ebf6aa` |
| 架构决策：KDBX + Bitwarden 双后端 | ✅ 确认 | — |
| **Phase A：整删 MDBX** | 🔄 执行中 | — |
| A.1 删除 17 个 MDBX 专有文件 | ✅ 完成 | 待提交 |
| A.2 移除 @Database MDBX 实体引用 | ⬜ 待做 | — |
| A.3 移除密封分支 + when 分支 | ⬜ 待做 | — |
| A.4 清理 96 个共享文件 | ⬜ 待做 | — |
| A.5 清理 MainActivity MDBX 装配 | ⬜ 待做 | — |
| A.6 Room 迁移 73 → 74 | ⬜ 待做 | — |
| A.7 CI 验证 | ⬜ 待做 | — |
| ~~Phase B：移除 BastionLocal~~ | ❌ 已取消 | 保留 BastionLocal，不删 |

---

## 八、接力开发指南

### 8.1 环境

- 仓库：`https://github.com/Chaniug/bastion`
- 分支：`dev`（开发分支，验证后合并 `main`）
- 本地无 Android SDK，依赖 CI

### 8.2 辅助脚本

- `/workspace/remove_mdbx_arms.py`：自动删除 `is X.Mdbx ->` when 分支和 `data class Mdbx` 定义
  - 用法：`python3 remove_mdbx_arms.py <file.kt> <suffix1> [suffix2 ...]`
  - suffix 如：`Mdbx`, `MdbxDatabase`, `MdbxFolderFilter`, `MdbxDatabaseTarget`
  - 脚本使用字符串感知的括号计数器，避免 `.contains("...{...}...")` 干扰

### 8.3 关键注意事项

1. **密封接口穷尽性**：Kotlin 的 `when` 对密封接口是穷尽的，删掉一个分支必须同步删掉全仓所有 `when` 分支引用，否则编译失败。
2. **CI 测试基线只降不升**：`BASELINE_FAILURES` 在 `.github/workflows/main.yml` 中，修好测试后必须同步下调，禁止上调。
3. **守卫测试读取源码文本**：部分测试通过 `projectFile(...).readText()` 读取源码文件并断言字面子串——删除源码文件会让这些测试 throw（不是 fail），需同步删除对应测试。
4. **git push 偶发失败**：GnuTLS recv error，重试即可（最多 5 次）。
5. **Room 迁移**：SQLite < 3.35 不支持 `DROP COLUMN`，需建新表迁移数据。参照 `MIGRATION_11_12` 删表先例。

### 8.4 建议的执行顺序

1. 完成 A.2-A.5（移除所有 MDBX 引用）
2. 推送 CI 验证编译通过
3. 完成 A.6（Room 迁移）
4. 推送 CI 验证测试基线
5. Phase B 单独确认后再执行
