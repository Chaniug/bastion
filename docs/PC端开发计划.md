# Bastion Windows 桌面客户端（Compose Multiplatform）实施计划

> 目标：把安卓密码管理应用 Bastion 的核心能力移植为 Windows 桌面客户端。
> 已确认决策：KMP 共享核心 + Compose for Desktop UI；只做 3 类功能（Bitwarden 条目同步 / 本地 KDBX 编辑 / KDBX→OneDrive 同步）；存储改 SQLDelight；OneDrive 用浏览器 PKCE（localhost 回环）。
> 约束：**不动现有安卓工程 `D:\Bastion\bastion\Bastion\`**；桌面端独立构建；先跑起来、再逐步完善。

## 0. 最新拉取复核结论（2026-08-11，用户刚 pull）

- 复核发现：最近提交（8/7~8/11）= autofill 迭代 + **bitwarden 同步修复** + android17 优化 + 图标文档，**无任何 KMP/桌面端结构变化**，项目仍是纯 Android 单模块。
- **对本计划的正面影响（间接）**：bitwarden 同步修复（上行 5 个静默数据丢失缺陷、登录条目自定义字段丢失、跨 vault 串行化同步）恰好是桌面端要复用的核心逻辑——**我们移植的是更健壮的版本**。
- 复核确认的可复用性基线：`BitwardenApi.kt` 0 个 android import（登录已写死 desktop client_id）、`BitwardenCrypto.kt` 仅 1 个 Base64、`KeePassKdbxService.kt` 6331 行仅 5 个 android import。下表均基于此。

---

## 1. 目标工程结构

### 推荐布局：在 `D:\Bastion\bastion\` 下新建**独立 Gradle 构建** `desktop/`，与安卓构建完全隔离

```
D:\Bastion\bastion\
├── Bastion\                  # 现有安卓工程（本次【零修改】，保持原样）
├── docs\                     # 已有文档（azure-app-registration-guide.md 等）
└── desktop\                  # 【新建】独立 KMP Gradle 构建
    ├── settings.gradle.kts   # rootProject.name="BastionDesktop"; include(":shared", ":desktop")
    ├── build.gradle.kts
    ├── gradle.properties
    ├── gradle\libs.versions.toml
    ├── shared\               # KMP 模块（commonMain + jvmMain），纯 Kotlin 核心
    │   └── src\
    │       ├── commonMain\
    │       │   ├── sqldelight\com\bastion\app\db\    # *.sq 数据库 schema
    │       │   └── kotlin\com\bastion\app\           # 保持 com.bastion.app 包名（复制零改包）
    │       │       ├── bitwarden\crypto\api\service\sync\mapper\   # Bitwarden 核心
    │       │       ├── kdbx\                          # KeePassKdbxCore + 文件源接口
    │       │       ├── data\                          # SQLDelight 数据访问 + 模型
    │       │       ├── sync\                          # KDBX 同步状态机/编排
    │       │       ├── security\                      # 字段级加密核心（JCE 纯 Kotlin）
    │       │       └── platform\                      # expect: Logger/Base64/KeyStorage/PathProvider
    │       └── jvmMain\kotlin\com\bastion\app\platform\   # actual: 各 expect 的 JVM 实现
    └── desktop\              # Compose for Desktop 应用模块（纯 JVM）
        └── src\main\kotlin\com\bastion\desktop\
            ├── Main.kt                # main()/Application/Window
            ├── ui\                    # Tab 界面与屏幕
            ├── platform\              # OneDriveBrowserAuth / LocalFileSource / DpapiKeyStore / SyncScheduler / SettingsFile
            └── di\                    # 手动依赖装配（不引 DI 框架）
```

### 为什么独立构建而非塞进现有 settings.gradle
- 现有安卓构建是 **AGP 9.1.1（内置 Kotlin 2.3.21）+ Gradle 9.3.1**，与 KMP `androidTarget` 插件存在版本/扩展冲突风险；独立构建把风险隔离在 `desktop/`。
- 与安卓的关系决策：**先独立新建 shared 模块、复制式迁移；安卓之后（另行排期）再接入**。本次只做桌面端，安卓保持现状。

### 版本基线（已联网核实）
| 组件 | 版本 |
|---|---|
| Kotlin | 2.3.21（与安卓一致） |
| Compose Multiplatform 插件 | **1.10.3**（org.jetbrains.compose；已知与 Kotlin 2.3.21 配套；1.11.1 要求 Kotlin≥2.3.10 亦可） |
| Compose compiler | org.jetbrains.kotlin.plugin.compose 2.3.21 |
| SQLDelight | 2.1.0（app.cash.sqldelight + sqlite-driver[jvm] + coroutines-extensions） |
| kotlinx-serialization-json | 1.8.1 |
| kotlinx-coroutines | 1.9.0（或 JVM 版 1.10.x） |
| kotpass | 0.10.0（如与 Kotlin 2.3 有 metadata 兼容问题则升到最新，见风险 R4） |
| bouncycastle / argon2kt / retrofit2 / okhttp3 | 1.78.1 / 1.6.0 / 2.11.0 / 4.12.0（与安卓一致） |
| JNA（DPAPI） | net.java.dev.jna:jna-platform 5.14.0 |
| JDK 目标 | JVM 17 |

---

## 2. 平台适配清单（expect/actual 与 JVM 替换）

### 2.1 Room → SQLDelight

**保留/移植的表**（写为 `shared/src/commonMain/sqldelight/com/bastion/app/db/*.sq`）：

| 表 | 说明 | 裁剪建议 |
|---|---|---|
| `password_entries` | 唯一条目表（密码） | 保留 title/website/username/password(密文)/notes/createdAt/updatedAt/isFavorite/软删除 + bitwarden 绑定字段 + keepass 绑定字段；**砍掉** Parcelable、卡片/SSH/WIFI/图标/归档等 100+ 字段 |
| `bitwarden_vaults` | 服务器配置+令牌+密钥 | 保留（serverUrl/apiUrl/identityUrl/token/refreshToken/userEmail/symmetricKey 密文等） |
| `bitwarden_folders` | 文件夹 | 保留最小集 |
| `bitwarden_conflict_backups` | 冲突快照 | 保留（原 BitwardenConflictBackup，JSON 快照字段） |
| `bitwarden_pending_operations` | 离线待同步队列 | 保留（原 BitwardenPendingOperation） |
| `local_keepass_databases` | KDBX 数据库登记 | 保留并**简化**：filePath 改为绝对路径字符串，砍 keyFileUri/内部存储/工作副本路径等安卓概念 |
| `keepass_remote_sources` | 远端来源 | 保留最小集（providerType 只留 ONEDRIVE） |
| `keepass_remote_sync_states` | KDBX 同步状态 | 保留（base/working/remote hash + etag + phase） |

**直接砍掉**：Category、OperationLog、TimelineVersionSnapshot、KeepassGroupSyncConfig、CustomField、PasswordPageAggregateStackEntry、PasswordArchiveSyncMeta、PasswordHistoryEntry、PasskeyEntry、BitwardenSend、BitwardenSyncRawEntryRecord（诊断）、Attachment、KeePassPendingChange（KDBX 变更集队列首版可省，见 3.2）、dedup/ledger/安全问答/生成器/备份偏好等全部安卓特化表。

**差异处理**：
- 枚举（KeePassSyncStatus、KeePassRemoteProviderType、ItemType 等）用 SQLDelight `ColumnAdapter` 存 TEXT；布尔用 INTEGER；时间用 INTEGER。
- Flow：Room DAO 的 `Flow<List<T>>` 换成 `Query.asFlow().mapToList()`（coroutines-extensions）。
- 外键/级联需手写 schema；`INTEGER PRIMARY KEY AUTOINCREMENT` 语法兼容。
- 类型安全的命名查询（insert/update/select by id/按 vault 查等）在 `.sq` 文件里重写一遍，行为对齐原 DAO。

### 2.2 Android Keystore → 桌面密钥存储（推荐 DPAPI）

- **推荐方案**：Windows DPAPI（经 JNA 调 `CryptProtectData`/`CryptUnprotectData`）。
  - 生成随机 32 字节 DEK（数据加密密钥），用 DPAPI 加密后存 `%APPDATA%\BastionDesktop\key.blob`。
  - 现有 Android 的 MDK（Master Data Key）+ AES-256-GCM 字段级加密逻辑（`SecurityManager` 的 JCE 部分）**原样保留**，仅把「MDK 由 Android Keystore 包裹」换成「MDK 由 DEK 包裹、DEK 由 DPAPI 保护」。
- **备选方案（对比后不首推）**：
  - 主密码派生密钥 + 本地加密文件：无 OS 依赖、可移植，但每次启动都要输主密码（若同时做 Bitwarden/KDBX 解锁体验差），作**可选模式**保留。
  - Java `KeyStore` PKCS12：需要文件口令，无 OS 集成，不推荐。
- 实现形态：`security/KeyStorage` 用 **expect/actual**（commonMain 接口 + jvmMain DPAPI 实现），`DesktopCryptoManager` 在 shared 里纯 JCE 实现。

### 2.3 MSAL → Graph 浏览器授权（desktop 模块重写）

`platform/OneDriveBrowserAuth.kt`（彻底替换 `utils/OneDriveAuthManager.kt`，后者为安卓 MSAL，不可复用）：
1. 生成 PKCE：`code_verifier`（43~128 字符随机）+ `code_challenge = BASE64URL(SHA256(verifier))`。
2. `java.awt.Desktop.browse()` 打开
   `https://login.microsoftonline.com/common/oauth2/v2.0/authorize?client_id=...&response_type=code&redirect_uri=http://localhost:{PORT}&scope=offline_access%20User.Read%20Files.ReadWrite&code_challenge=...&code_challenge_method=S256&prompt=select_account`。
3. 后台线程 `ServerSocket(PORT, bind=localhost)` 接收回环跳转，从请求行解析 `code`，回一个"登录成功可关闭窗口"的 HTML，关闭 socket。
4. POST `https://login.microsoftonline.com/common/oauth2/v2.0/token`，`grant_type=authorization_code` + `code_verifier` 换 token（access+refresh）。
5. refresh_token 经 `DesktopCryptoManager` 加密落盘；`getAccessToken()` 先查过期再 `grant_type=refresh_token` 刷新。
6. Graph 调用侧复用 `OneDriveKeePassFileSource`（见 3.2），只是把它的 `authTokenProvider: suspend () -> String` 接到本服务。

**Azure 注册前置（外部待办，Phase 0 就启动）**：
1. portal.azure.com → App registrations → 新建（或复用 `51306f8c-de1c-41da-8ae0-df00d1e830cb`）。
2. Authentication → **Add a platform → Mobile and desktop applications** → 添加 `http://localhost`（Azure 对 loopback 允许任意端口；保险起见再精确注册 `http://localhost:{固定端口}`，默认固定端口如 52525）。
3. Authentication → 勾选 **Allow public client flows**（必须）。
4. API permissions → Microsoft Graph → Delegated → `User.Read` + `Files.ReadWrite`。
5. 记录新 client_id（若不复用）。该步骤是人工操作，可能拖延 Phase 4，务必前置。

### 2.4 DataStore → 桌面偏好存储
- 非敏感设置（最近打开文件、自动同步开关、UI 偏好）：`java.util.prefs.Preferences.userRoot().node("BastionDesktop")`（Windows 走注册表，零依赖）。
- 敏感设置（Bitwarden/OneDrive 令牌、MDK blob）：一律走 `DesktopCryptoManager` 加密文件，绝不入 prefs。
- 原 `utils/AppDataStore.kt` / `utils/SettingsManager.kt` 仅移植其 key 定义与读取语义，实现换成 `SettingsStore`（shared expect / desktop actual）。

### 2.5 WorkManager → 协程定时任务
- `bitwarden/sync/BitwardenSyncWorker.kt`、`workers/KeePassRemoteUploadWorker.kt` 弃用。
- desktop 建 `platform/SyncScheduler.kt`：`CoroutineScope(SupervisorJob() + Dispatchers.Default)`，`while(isActive){ doSync(); delay(interval) }` 周期同步 + 手动 `requestSync()` 事件驱动；首版仅支持手动/退出前不强制后台任务。

### 2.6 Context / Uri 等上下文替换
- `Context` → 构造参数显式传 `dataDir: File`（由 desktop `PathProvider` 提供 `%APPDATA%\BastionDesktop`）。
- `Uri`/`ParcelFileDescriptor` → `java.io.File`（桌面有真实文件系统路径）。
- `android.util.Base64` → 统一 `platform/Base64` expect/actual（jvmMain 用 `java.util.Base64`；commonMain 调它）。
- `android.util.Log` → `platform/Logger` expect/actual（jvmMain 写 console + 可选日志文件）。

---

## 3. KDBX 与 Bitwarden 核心复用策略

> 原则：**保持 `com.bastion.app` 包名原样复制**，把「直接复制 / 小改 / 重写」分三类。大文件**重组不复刻**：新建瘦身类承接核心逻辑，避免啃 6300 行旧文件。

### 3.1 Bitwarden（源：`D:\Bastion\bastion\Bastion\app\src\main\java\com\bastion\app\bitwarden\`）

**直接复制（几乎零修改）**：
- `crypto/BitwardenCrypto.kt`（663 行，仅 1 个 `android.util.Base64` → 换 `platform/Base64`；自带 BouncyCastle Argon2 低内存回退，JVM 友好）
- `api/BitwardenApi.kt`（1143 行，**0 个 android import**；且登录接口本就写死 `client_id=desktop`、`deviceType=8`，天然面向桌面）
- `api/BitwardenTlsConfig.kt`、`sync/BitwardenSyncOrchestrator.kt`（567 行，0 android import）、`sync/SyncEnums.kt`、`sync/BitwardenSyncSummary.kt`、`sync/BitwardenUiSyncStatus.kt`、`sync/VaultSyncStatusExtensions.kt`、`sync/BitwardenRepositorySync.kt`（0 android import）

**小改（去 android.* import）**：
- `api/BitwardenApiFactory.kt`：`android.util.Base64`（mTLS PKCS12 解码）→ `platform/Base64`；`logging.runCatchingObserved` → 本地 `Logger`
- `service/BitwardenAuthService.kt`：去掉 `Context`（diag logger 初始化）、`Build`、`Log`；其余登录/两因素/新设备 OTP 流程原样
- `service/BitwardenSyncService.kt`（2448 行，1 个 android import）：移植同步主流程，**裁剪**非 Login 类型的处理分支（TOTP/CARD/NOTE/PASSKEY/SEND 等按当前范围砍掉或跳过）
- `service/CipherSyncProcessor.kt`（1 个 android import）、`service/CipherUploadProcessor.kt`（2 个，裁剪附件上传）
- `mapper/BitwardenMapper.kt`：纯映射，只保留 PasswordEntry↔Cipher(Login) 方向

**必须重写/大改**：
- `repository/BitwardenRepository.kt`（2155 行，8 个 android import：Context/ConnectivityManager/Uri/Base64/Log/EncryptedSharedPreferences/MasterKey）：**不整体复制**。新建瘦身 `DesktopBitwardenRepository`：接线 SQLDelight + `DesktopCryptoManager` + 纯同步核心，保留原类的登录态管理、token 生命周期、pending 队列、冲突备份语义。网络检测 `checkNetwork` 改为 `{ NetworkGateResult.ALLOWED }` 或简单 `NetworkChecker` 接口。

### 3.2 KDBX（源：`utils\` 与 `keepass\`）

**直接复制**：
- `utils/KeePassFileSource.kt`（接口+DTO，**0 android import**，纯 Kotlin）
- `keepass/KeePassEntryModels.kt`、`keepass/KeePassFieldRegistry.kt`、`keepass/KeePassEntryFingerprint.kt`（若确认无 android import）

**小改**：
- `utils/OneDriveKeePassFileSource.kt`（663 行，2 个 android import：Context、Uri）：去掉 Context/Uri，构造改为 `(authTokenProvider: suspend () -> String, accountIdentifier, driveId?, itemId?, remotePath?)`；路径编码换 `java.net.URI`/percent-encoding；Graph REST（createUploadSession 分片上传、If-Match etag 乐观锁、listChildren/testConnection）原样保留
- `utils/RemoteKeePassSyncService.kt`（227 行，0 android import，只依赖 DAO 接口）：DAO 换成 SQLDelight 查询接口后原样复制

**重组（大文件不复刻）**：
- `utils/KeePassKdbxService.kt`（6331 行，仅 5 个 android import：Context/Uri/ParcelFileDescriptor/Base64/Log）：
  - **Phase A（先跑起来）**：新建 `kdbx/KeePassKdbxCore.kt`（shared，约 500~800 行），以原文件为参考**重写**纯 kotpass 子集：`decode/encode`（kotpass `decode`/`encode` + `KeePassCodecSupport` 的 cipherProviders）、`verifyDatabase`、`inspectDatabase`、`readPasswordEntries`、`listGroups`、`loadWorkspace`、`addOrUpdatePasswordEntries`、`updatePasswordEntry`、`addPasswordEntry`、`deletePasswordEntries`、分组 CRUD、自定义字段提取。**剪掉**：附件（readEntryAttachments 等）、passkey/secure-item 路径、`workers/KeePassRemoteUploadWorker` 引用、`SecurityManager` 依赖（换 `DesktopCryptoManager`）。
  - **Phase B（同步）**：新建 `kdbx/KeePassRemoteSyncEngine.kt`，从原文件摘 `syncRemoteDatabase`（base/working/remote 三方合并 + 冲突副本）+ `resolveRemoteConflict` + `flushPendingRemoteUpload` 及相关私有辅助（约 800 行，整体搬移）。
  - `keepass/` 包中的 `KeePassChangeSet`/`KeePassChangeSetApplier`/`KeePassRemoteRebase` 等**变更集机制**：Phase B 三方合并会用到，属纯 Kotlin 模型，随同步引擎一起搬；Phase A 走「整文件保存」不需要。
  - 新增 `kdbx/LocalFileKeePassSource.kt`（desktop actual）：`File` 实现 `KeePassFileSource`（stat/read/write/listChildren/createFile/testConnection），供本地 KDBX 与 OneDrive 同接口复用。

**重写**：
- `utils/OneDriveAuthManager.kt`（MSAL）→ `platform/OneDriveBrowserAuth.kt`（见 2.3）
- `utils/GoogleDriveAuthManager.kt`、`utils/GoogleDriveKeePassFileSource.kt`、WebDAV 相关：**本次不移植**（范围只做 OneDrive）

### 3.3 安全
- `security/SecurityManager.kt`（1658 行）：**拆分**。JCE 纯算法部分（MDK 派生、AES-256-GCM 字段加解密、口令 hash/salt）→ `security/DesktopCryptoManager.kt`（shared）；Android Keystore/EncryptedSharedPreferences 部分 → `KeyStorage` expect/actual（jvmMain=DPAPI）。注意保留密钥别名与格式前缀（`V2|`、`AU|`、`CP|`）以兼容后续 Android 数据导入（可选）。

---

## 4. 桌面 UI 设计（Compose for Desktop，聚焦三功能，不堆组件）

单主窗口 `Window(title="Bastion", state=rememberWindowState(size=DpSize(1080.dp,720.dp)))`，Material3 主题，顶部 `TabRow` 或左侧 `NavigationRail` 三 Tab：

**Tab 1 Bitwarden**（`ui/bitwarden/`）
- 未登录态：登录表单（邮箱+主密码+高级服务器 URL+两因素码）+ "连接并同步" 按钮 + 同步状态卡。
- 已登录态：条目列表（搜索框 + 密码条目行 + 修改时间 + 同步状态点）；条目编辑表单/对话框（标题/用户名/密码[显示切换+生成器]/URL/备注/文件夹下拉/收藏）；顶部工具栏：最后同步时间、"立即同步"、退出。
- 冲突页：`bitwarden_conflict_backups` 列表，逐条"保留本地/保留服务器/合并/丢弃"。
- 视图模型：用 `StateFlow` + `rememberCoroutineScope` 手动装配（不引导航库；如要 ViewModel 用 `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose`）。

**Tab 2 本地 KDBX**（`ui/kdbx/`）
- 打开文件（`java.awt.FileDialog` 或 CMP 文件对话框）+ 主密码/密钥文件输入；"新建库"（KDBX4/AES）。
- 左分组树 / 右条目表（类 KeePassXC 双栏）；条目编辑表单（同上字段集）。
- 工具栏：保存（kotpass 整文件重写）、同步状态徽标、若已绑定 OneDrive 显示远端信息。
- 若绑定远端：OneDrive 登录按钮 / 已登录账号展示 / 手动同步按钮 / 冲突指示。

**Tab 3 设置**（`ui/settings/`）
- OneDrive 账号（登录/登出、显示账号）、Bitwarden（服务器、自动同步开关）、安全（修改主密码、立即锁定）、关于。

UI 全部用 material3 默认组件，不做动画/图表，代码规模控制在 15~25 个 composable 文件。

---

## 5. 分阶段实施计划

### Phase 0 — 工程骨架 + shared 模块（约 1~2 天）
- 新建 `desktop/` 构建：`settings.gradle.kts`、根 `build.gradle.kts`、`gradle/libs.versions.toml`、`shared/build.gradle.kts`、`desktop/build.gradle.kts`。
- `:desktop:run` 出空窗口；`shared` 空 KMP 模块可编译；SQLDelight 空 schema 生效；`platform/` expect/actual（Logger/Base64/KeyStorage/PathProvider）骨架。
- **验收**：`./gradlew :desktop:run` 弹窗；`./gradlew :shared:compileKotlinJvm` 通过。
- **启动外部待办**：Azure 桌面注册（见 2.3）。

### Phase 1 — Bitwarden 核心移植（约 3~5 天）
- 复制：`BitwardenCrypto`、`BitwardenApi`、`BitwardenApiFactory`（改 Base64）、`BitwardenTlsConfig`、`BitwardenSyncOrchestrator` 等纯文件。
- 移植：`BitwardenAuthService`（去 Context）；`DesktopCryptoManager`+`KeyStorage`（DPAPI）；瘦身 `DesktopBitwardenRepository`（先临时用内存/JSON 存储占位，Phase 3 换 SQLDelight）；`BitwardenSyncService`/`CipherSyncProcessor`（仅 Login 类型）。
- **验收**：JVM 单测通过（KDF 派生 + CipherString round-trip 与安卓版输出一致）；`run` 后能用官方服务器完成 预登录→登录→sync 拉取并解密条目标题/口令。

### Phase 2 — KDBX 核心移植（约 3~5 天）
- `kdbx/KeePassKdbxCore.kt`（重写纯 kotpass 子集）+ `KeePassCodecSupport` 移植 + `LocalFileKeePassSource`。
- **验收**：打开真实 KDBX4（KeePassXC 生成）→ 列条目 → 修改 → 保存 → 再打开一致；新建库能被 KeePassXC 打开；自定义字段正确保留。

### Phase 3 — SQLDelight 存储层（约 2~3 天）
- 写 `*.sq`（2.1 的 8 张表）+ ColumnAdapter + 查询接口；把 Phase 1/2 的临时存储换成 SQLDelight；`RemoteKeePassSyncService` 接上 DAO。
- **验收**：CRUD + Flow 驱动 UI 刷新；pending 队列、冲突备份、远端同步状态可写读。

### Phase 4 — OneDrive 桌面授权与同步（约 3~5 天，依赖 Azure 前置完成）
- `OneDriveBrowserAuth`（PKCE + localhost 回环 + token 刷新）；`OneDriveKeePassFileSource` 移植（去 Context/Uri）；`KeePassRemoteSyncEngine`（三方合并 + etag）；`SyncScheduler`。
- **验收**：浏览器登录回调成功、token 落盘加密；本地库绑定 OneDrive 后 上传/下载/列表 成功；两台设备改同一文件触发 CONFLICT 状态并可手动解决。

### Phase 5 — 桌面 UI（约 4~6 天）
- 三 Tab 全部屏幕 + 表单 + 同步状态 + OneDrive 登录页；接线 Phase 1~4 的仓库与同步器。
- **验收**：三大功能 UI 闭环（编辑→保存→同步），无安卓残留 API 报错。

### Phase 6 — 联调与冲突验证（约 2~3 天）
- 端到端场景清单：Bitwarden 双向同步+冲突备份+解决；KDBX OneDrive 三方冲突；本地 KDBX 新建/编辑/保存；锁屏/解锁（DPAPI）。
- **验收**：逐条场景通过；`compose.desktop.application` 打包出可分发的 exe（可选）。

---

## 6. 风险与陷阱

- **R1 Compose Multiplatform 版本匹配**：Kotlin 2.3.21 须配 CMP 插件 ≥1.10.x（已核实 1.10.3 配套样例；1.11.1 需 Kotlin≥2.3.10）。务必统一 Kotlin 版本，Compose compiler 用 `org.jetbrains.kotlin.plugin.compose`。
- **R2 独立构建隔离**：不要在安卓工程里加 KMP 模块，避免 AGP 9 内置 Kotlin 与 KMP 插件冲突（选择理由见 §1）。
- **R3 argon2kt 桌面原生依赖**：argon2kt 在 JVM 走 JNA 加载原生库（Windows 受支持），但建议保持 `BitwardenCrypto` 自带的 BouncyCastle 纯 Java 回退；注意内存上限（Bitwarden 默认 64MB，桌面可放宽）。
- **R4 kotpass 版本**：安卓注释称 0.10.0 是兼容旧工具链的版本；在 Kotlin 2.3 下可能报 metadata 版本不兼容。若阻塞，升到最新 kotpass 并核对 API（`decode`/`encode`/`modifyBinaries`/`Credentials`）。
- **R5 SQLDelight 与 Room 模型差异**：无 TypeConverter 自动转换（要 ColumnAdapter）、无 Flow 自动（要 coroutines-extensions）、外键迁移要手写；`PasswordEntry` 130 字段裁剪时注意 BitwardenMapper/SyncService 引用的字段必须保留。
- **R6 OneDrive Azure 前置阻塞**：localhost 回环 redirect 必须人工在 Azure 注册（公共客户端+Allow public client flows）。外部依赖，**Phase 0 就发起**，否则 Phase 4 会卡死；client_id 复用或自注册需提前定。
- **R7 Compose for Desktop 与安卓 Compose API 差异**：无 Activity/Context/资源系统；窗口用 `ApplicationScope`/`Window`；CMP material3 是独立 artifact（版本线不同，别复用安卓 BOM）；无 navigation-compose（手写 Tab 状态或用 CMP navigation 实验版）；文件选择用 AWT `FileDialog`。
- **R8 大文件移植拖期**：6331 行 KeePassKdbxService、2155 行 BitwardenRepository、2448 行 BitwardenSyncService 直接复制会引入大量 Room/android 依赖。务必按 §3「重组不复刻 + 裁剪子集」执行，先保主路径。
- **R9 多 vault / 多 KDBX**：首版单 Bitwarden 活动 vault（多 vault 并发代码可保留但不暴露 UI）；KDBX 按文件管理，同一文件并发同步需加 Mutex（原 `vaultSyncMutexes` 模式可复用）。
- **R10 分发与 JCE**：`compose.desktop.application` 需要 jpackage 运行时（首次打包下载慢）；现代 JDK AES-256 默认无限制强度，无需额外配置。

---

## 附录 A：关键文件路径索引（源 → 目标）

| 源（安卓，只读参考） | 目标（desktop/shared） | 处理 |
|---|---|---|
| `Bastion/app/src/main/java/com/bastion/app/bitwarden/crypto/BitwardenCrypto.kt` | `shared/.../bitwarden/crypto/BitwardenCrypto.kt` | 复制（Base64 换） |
| `.../bitwarden/api/BitwardenApi.kt` | `shared/.../bitwarden/api/BitwardenApi.kt` | 复制 |
| `.../bitwarden/api/BitwardenApiFactory.kt` | `shared/.../bitwarden/api/BitwardenApiFactory.kt` | 小改（Base64/Logger） |
| `.../bitwarden/service/BitwardenAuthService.kt` | `shared/.../bitwarden/service/BitwardenAuthService.kt` | 小改（去 Context） |
| `.../bitwarden/service/BitwardenSyncService.kt` | `shared/.../bitwarden/service/BitwardenSyncService.kt` | 裁剪移植（仅 Login） |
| `.../bitwarden/repository/BitwardenRepository.kt` | `shared/.../bitwarden/repository/DesktopBitwardenRepository.kt` | 重写瘦身 |
| `.../bitwarden/sync/BitwardenSyncOrchestrator.kt` | `shared/.../bitwarden/sync/BitwardenSyncOrchestrator.kt` | 复制 |
| `.../utils/KeePassKdbxService.kt` | `shared/.../kdbx/KeePassKdbxCore.kt` + `KeePassRemoteSyncEngine.kt` | 重组 |
| `.../utils/KeePassFileSource.kt` | `shared/.../kdbx/KeePassFileSource.kt` | 复制 |
| `.../utils/OneDriveKeePassFileSource.kt` | `shared/.../kdbx/OneDriveKeePassFileSource.kt` | 小改（去 Context/Uri） |
| `.../utils/RemoteKeePassSyncService.kt` | `shared/.../sync/RemoteKeePassSyncService.kt` | 小改（DAO 接口化） |
| `.../utils/OneDriveAuthManager.kt` | `desktop/.../platform/OneDriveBrowserAuth.kt` | 重写 |
| `.../security/SecurityManager.kt` | `shared/.../security/DesktopCryptoManager.kt` + `KeyStorage` | 拆分重写 |
| `.../data/PasswordDatabase.kt` 等 Room 实体 | `shared/.../sqldelight/com/bastion/app/db/*.sq` | 重写 schema |
| `.../logging/SwallowedExceptionLogger.kt` | `shared/.../platform/Logger.kt` | expect/actual |
| `Bastion/app/src/main/res/raw/onedrive_msal_config.json` | `desktop/.../platform/OneDriveConfig.kt` | client_id 常量化 |

## 附录 B：Azure 桌面注册速查（待办前置项）
见 §2.3 五步；最终产出物为 `desktop/.../platform/OneDriveConfig.kt` 里的 client_id + 固定回环端口常量（默认 52525）。
