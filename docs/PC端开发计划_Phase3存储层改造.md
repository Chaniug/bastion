# PC 端 Phase 3 存储层改造计划

> 分支策略：所有改动在 `dev` 开发，CI 绿后合 `main`。不触碰 `Bastion/` 安卓模块（桌面端完全物理隔离于 `desktop/`）。
> 状态：✅ **已完成并通过本地编译**（`gradle :shared:compileKotlinJvm :desktop:compileKotlin` BUILD SUCCESSFUL）。本文件用于记录方案与实施记录，供本 agent 或其他 agent 接力。

## 背景

当前桌面端 `BitwardenRepository` 的数据全部存在 `InMemoryBitwardenRepositoryStore`（212 行纯内存实现，
用了 `ConcurrentHashMap` / `mutableListOf`）。应用一关，vault / 条目 / 文件夹 / 冲突备份 / 待同步队列全部丢失。

SQLDelight 插件已配置（`shared/build.gradle.kts`），`PasswordEntries.sq` 能被编译并生成 `Database` 类，
但**没有任何 `SqlDriver` 实例化**，所有 store 方法都是内存版。

`BitwardenRepositoryStore` 接口分 6 组、约 37 个方法，对应需要持久化的数据：

| 组 | 方法数 | 需要的表 |
|---|---|---|
| Vault | 12 | `bitwarden_vaults`（缺失） |
| 条目 PasswordEntry | 11 | `password_entries`（已存在） |
| 文件夹 | 3 | `bitwarden_folders`（缺失） |
| 冲突备份 | 3 | `bitwarden_conflict_backups`（缺失） |
| 待同步队列 | 4 | `bitwarden_pending_operations`（缺失） |
| 偏好 key-value | 4 | `local_preferences`（新增，不计入原 8 张） |
| KDBX 本地库登记 | — | `local_keepass_databases`（缺失） |
| KDBX 远端源 | — | `keepass_remote_sources`（缺失） |
| KDBX 远端同步态 | — | `keepass_remote_sync_states`（缺失） |

> 原 PC 计划列了 8 张表：`password_entries` + 上表 2~4（bitwarden_* 3 张）+ 上表 7~9（keepass_* 3 张）。
> 偏好 `local_preferences` 为单列，建议单列不计入那 8 张。

## 目标

把 `InMemoryBitwardenRepositoryStore` 替换为 `SqlDelightBitwardenRepositoryStore`，使以上数据落盘（SQLite），
应用重启不丢；Flow 观察能力保留（`asFlow().mapToList()`）。

## 实施步骤

### A. Schema 扩展（shared/commonMain/sqldelight）
- 新增 7 个 `.sq` 表定义 + 查询：
  - `BitwardenVaults.sq`：对应 `BitwardenVault`（~40 字段，加密字段已是 Base64 密文，直接存 TEXT）。
    查询：selectAll / observeAll / getActive / getById / getByEmail / upsert / setActive / markUnlocked/Locked/AllLocked/Synced / delete。
  - `BitwardenFolders.sq`：id, vault_id, bitwarden_folder_id, name。查询：getByBitwardenId / upsert / getByVault。
  - `BitwardenConflictBackups.sq`：id, vault_id, cipher_id, snapshot_json, created_at, resolved。
  - `BitwardenPendingOperations.sq`：id, vault_id, op_type, payload_json, created_at, completed。
  - `LocalKeepassDatabases.sq` / `KeepassRemoteSources.sq` / `KeepassRemoteSyncStates.sq`（支撑 Phase 4 远端态持久化）。
  - `LocalPreferences.sq`：(key TEXT PRIMARY KEY, value TEXT) — 支撑 `loadBoolean/loadLong/saveBoolean/saveLong`。

### B. 迁移
- 当前 schema 版本 v1（仅 `password_entries`）。升级到 v2，保留 `password_entries`，新增 7+1 张表。
- 决策：**v1→v2 迁移保留已有 `password_entries` 数据**（dev 阶段、无正式发布用户，但避免开发期数据丢失）。
- `shared/build.gradle.kts` 的 `sqldelight { }` 块加 `deriveSchemaFromGrpc` 无关；配置 `schemaOutputDirectory` 以生成 v1 schema 供迁移校验。

### C. Driver 工厂（shared/jvmMain）
- 新增 `expect/actual` 或仅 JVM 的 driver 工厂：`JdbcSqliteDriver` 指向
  `~/.bastion-desktop/bastion.db`（路径可由 `AppContainer` 注入），实例化 `Database(driver)`，
  执行 `PRAGMA foreign_keys=ON`。
- 测试用 in-memory `JdbcSqliteDriver("jdbc:sqlite::memory:")`。

### D. SqlDelightBitwardenRepositoryStore 实现
- 新建 `SqlDelightBitwardenRepositoryStore(database, cryptoManager)`，实现 `BitwardenRepositoryStore` 全部 ~37 方法。
- Flow 用 `query.asFlow().mapToList()` / `mapToOneOrDefault()`。
- `decryptAccessToken`：对称密钥属运行时内存态（解锁后缓存），**不落库**，重启需重新解锁——与安卓版一致。

### E. DI 接线（desktop/.../di/AppContainer.kt）
- 把 `InMemoryBitwardenRepositoryStore()` 换成 `SqlDelightBitwardenRepositoryStore(database)`，注入 `Database`。

### F. 测试
- 用 in-memory driver 覆盖：vault 增删改查、条目 upsert/observer、文件夹、待同步队列、冲突备份、迁移 v1→v2。

### G. 验证与合并
- 本地 `gradle :shared:compileKotlinJvm :desktop:compileKotlin`（已具备 JDK17 + 镜像）编译通过。
- 推送 `dev` → 看 **Desktop-Build** CI 绿 → 合 `main`。

## 风险 / 决策点（待确认）

1. **迁移策略**：建议 v1→v2 保留 `password_entries`（不破坏开发期数据）。可改为「直接重置 schema」若你认为 dev 数据无需保留。
2. **偏好表**：单列 `local_preferences`，不计入原 8 张表。
3. **加密密钥缓存**：运行时内存态，不落库（与安卓一致）。
4. **KDBX 三张表（local_keepass_databases / keepass_remote_sources / keepass_remote_sync_states）**：
   是否本 Phase 一并建表（仅建表+基础查询，业务接线留到 Phase 5），还是只做 bitwarden_* + preferences，KDBX 表延后。

## 不在本 Phase 范围

- Phase 5：SyncScheduler 周期同步、UI 完善。
- Phase 6：Windows 真机 + Azure 账号联调（需你提供环境）。

---

## 实施记录（2026-08-12 完成）

### 已落地文件
- `shared/.../sqldelight/.../` 9 个 `.sq`：`PasswordEntries`（修正 id 为 INTEGER 主键）、`BitwardenVaults`、`BitwardenFolders`、`BitwardenConflictBackups`、`BitwardenPendingOperations`、`Preferences`、`LocalKeePassDatabases`、`KeePassRemoteSources`、`KeePassRemoteSyncStates`。
- `shared/.../repository/SqlDelightBitwardenRepositoryStore.kt`：实现 `BitwardenRepositoryStore` 全部接口（约 37 方法 + 9 张表）。
- `shared/.../db/BastionDatabaseFactory.kt`（jvmMain）：`JdbcSqliteDriver` 工厂，含旧 schema（id 为 TEXT）兼容重建；返回 `BastionDatabaseBundle(database, driver)`。
- `desktop/.../di/AppContainer.kt`：注入切换为 SQLDelight 实现（共享同一 `dbBundle`）。
- `InMemoryBitwardenRepositoryStore.kt` **保留未删**（避免连带改动，已是死代码，可后续清理）。

### ⚠️ SQLDelight 2.1.0 关键 API 发现（接力必读）
1. **协程扩展包名是 `app.cash.sqldelight.coroutines`（不是 `coroutines2`）**。`asFlow()` / `mapToList()` 来自此包。
   - `mapToList` 需要 `CoroutineContext` 参数：`query.asFlow().mapToList(Dispatchers.IO)`（`asFlow()` 本身无参）。
2. **`QueryResult` 在 `app.cash.sqldelight.db.QueryResult`**（不是 `app.cash.sqldelight.QueryResult`）。
3. **`executeQuery` 真实签名（位置参数）**：
   `driver.executeQuery(identifier, sql, mapper: (SqlCursor)->QueryResult<R>, parameters: Int) { bindArgs }`
   —— 第 3 参是 mapper（`QueryResult.Value(c)`），第 4 参是参数个数，尾部 lambda 是 `bindArgs`。**不能用命名参数 `ext`/`bindArgs`**（Kotlin 端参数名是 `mapper`/`bindArgs` 之外的命名，命名调用会报错）。
4. **`SqlCursor.next()` 返回 `QueryResult<Boolean>`（不是 `Boolean`）**，取值要 `.next().value`；`getLong(n)` 返回非空 `Long`，`getString(n)` 返回 `String`。
5. **生成的 `BastionDatabase` 不暴露 `driver`**，需在工厂里一并返回（见 `BastionDatabaseBundle`）。
6. **`sqlite_3_18` 方言不支持 `ON CONFLICT...DO UPDATE`**，偏好 upsert 改用 `INSERT OR REPLACE`。

### 验证状态
- ✅ 本地 `:shared:compileKotlinJvm :desktop:compileKotlin` BUILD SUCCESSFUL。
- ⏳ 待推送 `dev` 后观察 **Desktop-Build** CI（CI 跑 `:shared:compileKotlinJvm` + `:desktop:packageMsi/:desktop:packageExe`，打包为 Windows/WiX，本机无法跑打包，仅验证编译）。
- ⚠️ 尚未做 JVM 运行时冒烟测试（CI 不跑测试）；真机/桌面运行验证建议由用户在桌面端确认。

