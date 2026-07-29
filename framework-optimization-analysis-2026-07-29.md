# Bastion 框架分析与优化机会报告

> **日期**：2026-07-29
> **范围**：`D:\Bastion\bastion\Bastion\app\`（Android 模块，约 860 个 kt/java 文件，源码根 `com.bastion.app`）
> **方法**：纯静态分析（读源码 + grep + 包结构扫描）。**未修改任何文件、未执行构建**（沙箱 Gradle 进程会被杀，无法运行）。
> **目标**：梳理整体框架，并找出可提升「运行效率 / 速度 / 稳定性」的具体优化点，供你评估决策。

---

## 一、整体框架概览

### 1.1 技术栈
- **语言/UI**：Kotlin + Jetpack Compose（Material 3）；`minSdk 26 / targetSdk 34 / compileSdk 35`，AGP 8.7.3 / Kotlin 2.0.21 / Gradle 8.9。
- **本地存储**：Room（元数据库 `PasswordDatabase` + `SteamDatabase`）+ 原生 `SQLiteDatabase` 封装的「真 vault」（字段级 AES-GCM 加密）。
- **加密**：AndroidX `EncryptedSharedPreferences`（Keystore `MasterKey`）+ 自研 `MdbxVaultCrypto`（PBKDF2 + AES-GCM 字段加密）。
- **并发**：Kotlin Coroutines + Flow；**无 Hilt/Dagger/Koin 实质 DI**（见 §2）。
- **自动填充**：`autofill_ng` 为唯一实现（基于 Android Autofill Framework + Credential Provider）。

### 1.2 分层与包结构（按文件数）
| 包 | 文件数 | 职责 |
|---|---|---|
| `ui` | 266 | Compose 屏幕/组件（含 `vaultv2`、`screens`、`password`、`totp`…） |
| `data` | 68 | Room 实体/DAO、`LocalMdbxDatabase`、`SecureItem`、去重 |
| `autofill_ng` | 61 | 唯一自动填充实现 |
| `utils` | 52 | KeePass/WebDAV/Settings/备份 等通用工具 |
| `bitwarden` | 42 | Bitwarden 同步/加密/服务 |
| `steam` | 39 | Steam Guard 导入/网络/UI |
| `attachments` | 29 | 附件存储/加密/门面 |
| `keepass` | 23 | KeePass 执行器/编解码 |
| `viewmodel` | 19 | 各业务 ViewModel + 跨配置保留状态 |
| `passkey` | 17 | Passkey / Credential Provider |
| `security` | 13 | `SecurityManager` / `SessionManager` / `AppUpdateSecurityGuard` |
| `repository` | 13 | `MdbxVaultStore` / `SecureItemRepository` / `MdbxRepository` |
| `util` | 12 | **与 `utils` 并存**（见 §5）：`PasswordGenerator` / `TotpDataResolver` |
| `webdav` / `domain` / `sync` / `service` / `notes` / `workers` / `receiver` / `perf` / `navigation` / `mdbx` | 少量 | 各自领域 |

### 1.3 依赖方向
自上而下单向依赖，**未发现明显环**：
- UI 层（`ui/*`、`viewmodel/*`、`autofill_ng`）→ `repository` / `security` / `data` / `utils` / `util`
- `repository` → `data`（Room DAO）/ `security` / `utils`
- `bitwarden` / `keepass` / `steam` / `webdav` / `passkey` → `data` / `security` / `utils` / `repository`
- `security` / `data` / `util` / `mdbx` 为底层，不反向依赖 UI

**结论**：分层基本清晰、职责划分合理，这是项目的健康基础。主要问题在于**依赖装配方式**（手写 new + 单例）和**少数热点代码路径**。

---

## 二、当前架构评估

**优点**
- 分层单向、无环；`autofill_ng` / `vaultv2` 均为唯一实现（无新旧并存的历史包袱）。
- 绝大多数重活已正确放到 `Dispatchers.IO`/`Default`（autofill 解析、MDBX 读写、Steam 视图模型等）。
- Room 未开 `allowMainThreadQueries`，对主线程同步查询有硬约束。
- `:accessibility` 独立进程只做轻量初始化即返回，降低常驻开销。
- autofill 已有 `buildResponseStabilityKey` / `buildFieldSignatureKey` 稳定响应缓存，避免重复构建。

**短板（优化空间所在）**
- **依赖装配是「手工 new + 少量 object 单例」**，Koin 虽引入却是空容器（死重）。
- **并发作用域管理薄弱**：5 处 `GlobalScope`（Autofill Service 的 scope 已在 `onDestroy` 正确 `cancel()`，D2 实际已完成）。
- **异常被大量静默吞掉**（`runCatching` 数百处 `getOrNull()`），真实故障难暴露。
- **冷启动/旋转屏幕的主线程 Keystore 开销**真实存在。
- **巨型类集中**（`MainActivity` 184KB、`MdbxVaultStore` >6500 行、`WebDavHelper` >5300 行），可维护性与单测成本高。

---

## 三、优化机会清单

每条格式：**现象 → 位置（文件:行）→ 预期收益 → 风险/代价**。

### A. 启动速度
- **A1. 冷启动主线程 Keystore 初始化**：`MainActivity.kt:363` 主线程 `SecurityManager(this)` → 构造内 `MasterKey.build()` + `EncryptedSharedPreferences.create`（`SecurityManager.kt:63-86`）。→ 收益：消除低端机冷启动/旋转卡顿。→ 代价：低；改为懒加载、或提升为 Application 级单例。
- **A2. 配置变更重建依赖图**：`MainActivity.kt:362-387` 每次 `onCreate` 重建 `SecurityManager`/Repository（旋转屏幕即重跑）。→ 收益：旋转/折叠屏不再重建 Keystore 与对象图。→ 代价：低；上移为单例或 ViewModel 持有。
- **A3. 删除空 Koin 容器**：`BastionApplication.kt:84-92` `startKoin` 仅 `androidLogger`+`androidContext`，无 `modules(...)`。→ 收益：减无意义依赖与认知负担。→ 代价：低；直接移除 Koin 依赖与调用（已核实无任何 module 注册）。

### B. 运行时效率（CPU/内存）
- **B1. OTP 副作用全表解密 TOTP**：`OtpAutofillSideEffects.kt:205-208` 每次填充都 `getActiveItemsByTypeSync(TOTP)` 全量取出并逐条解密，只为匹配 1 条。→ 收益：填充时延与 CPU 随条目数线性下降。→ 代价：中；改为按 `boundPasswordId`/identity 索引或增量匹配 + 结果缓存。
- **B2. dataset 逐条解密**：`FilledDataBuilderNg.kt:186-200` 每个候选条目各解密 username+password。→ 收益：大 vault 填充更快。→ 代价：低；解锁后做「解密缓存」或限制候选数。
- **B3. 重复 new SecurityManager**：`OtpAutofillSideEffects.kt:179/206/244/263` 等多处反复构建（每次都重建 Keystore/EncryptedSP）。→ 收益：减少重复 Keystore 成本。→ 代价：低；注入/复用单例。
- **B4. 巨型类**：`MainActivity` / `MdbxVaultStore` / `WebDavHelper` / `SteamViewModel`。→ 收益：方法数/内存/编译期与可维护性。→ 代价：高；需渐进拆分。

### C. UI 流畅度
- **C1. Composable 内直接持有 Room 单例**：`AddEditPasswordScreen.kt:233`、`PasswordListContent.kt:665`、`VaultV2Pane.kt:1422` 等 `remember { PasswordDatabase.getDatabase(context) }`。→ 收益：状态与数据解耦、可测试、避免重建抖动。→ 代价：中；迁移到 ViewModel/Repository。

### D. 稳定性 / 崩溃风险
- **D1. GlobalScope 无结构化并发**：`BastionApplication.kt:110/120`、`AutofillCipherCallbackActivity.kt:309`、`AutofillPickerActivityV2.kt:1254/1323`。→ 收益：进程回收/销毁时可取消、可观测、防重复执行。→ 代价：低；改为 `ProcessLifecycleOwner.get().lifecycleScope`（先 `get()` 取进程级 LifecycleOwner 实例再取作用域）或自定义 `CoroutineScope(SupervisorJob())` 在宿主销毁时 `cancel()`。
- **D2. Autofill Service scope 绑定生命周期（已核实完成）**：`BastionAutofillServiceNg.kt:98` `CoroutineScope(SupervisorJob()+Main.immediate)`，`onDestroy`（L154）已 `scope.cancel()`。→ 结论：已达标，无需改动；记录以避免重复实施。
- **D3. runCatching 静默吞异常**：`SettingsManager.kt`、`MdbxVaultStore.kt`、`BitwardenSyncService.kt`、`SteamLoginImportService.kt` 等数百处 `getOrNull()`/`getOrDefault()` 无 `onFailure`。→ 收益：暴露真实故障、减少「空白/假成功」类隐性 bug。→ 代价：中；关键路径（解锁/写入/同步）至少 `onFailure` 上报或 Toast。
- **D4. PBKDF2 高迭代解锁时延**：`MdbxVaultCrypto.kt:205-209`（最高 360k 迭代）。→ 已确认在 `Dispatchers.IO`（`MdbxVaultStore.kt:468-475`）非主线程，非 ANR 风险，但低端机解锁可达数秒。→ 收益：解锁体验。→ 代价：中/安全权衡；确认进度 UI，评估 Argon2id 或按需下调档位。

### E. 代码健康
- **E1. 删除/合并 `utils/PasswordGenerator.kt`**：`utils/PasswordGenerator.kt:8` 与活跃实现 `util/PasswordGenerator.kt:23` API 不同（一个用 `PasswordOptions`、一个用独立参数 + zxcvbn），全仓 grep **无任何 `import ...utils.PasswordGenerator`**（疑似死代码/孤儿类）。→ 收益：消除混淆与双重维护。→ 代价：低；确认无全限定名引用后删除或统一到 `util` 版。
- **E2. 拆分 god-class**：`MainActivity` / `MdbxVaultStore` / `WebDavHelper` / `SteamViewModel`。→ 收益：可测试、可并行开发、降低回归。→ 代价：高；渐进重构。
- **E3. 统一解密入口**：8+ 处重复 `decryptStoredSensitiveValue`/`decryptPassword`（Password/Note/BankCard/…）。→ 收益：逻辑单一可信、易加缓存。→ 代价：中；抽到 `SecurityManager`/Repository。
- **E4. 安全：`usesCleartextTraffic="true"`**（`AndroidManifest.xml:59`）。→ 收益：降低中间人/泄露风险（非性能，但属健康度红线）。→ 代价：低；按需对私有 WebDAV 域放开而非全局。
- **E5. 多套加解密实现并存**：`EncryptionHelper` / `SteamMaFileCrypto` / `StratumDecryptor` / `AegisDecryptor` / `BitwardenCrypto`。→ 收益：减小攻击面。→ 代价：中；收敛公共原语。

---

## 四、Top 10 最值得做的优化（优先级）

| 优先级 | 优化点 | 分类 | 一句话理由 |
|---|---|---|---|
| **1** | **D1 消除 GlobalScope（D2 Autofill scope 已达标）** | 稳定性 | 5 处无作用域协程 = 进程被杀不取消、泄漏、难观测；改动小收益大 |
| **2** | **A1 冷启动主线程 SecurityManager/Keystore 移出主线程** | 启动速度 | `MainActivity.kt:363` 主线程 Keystore 是冷启动与旋转屏幕的真实卡顿源（已核实） |
| **3** | **D3 关键路径 runCatching 不再静默吞异常** | 稳定性/可观测 | 数百处 `getOrNull()` 掩盖真实故障，是「空白/假成功」类隐性崩溃根因 |
| **4** | **B1 OTP 副作用全表 TOTP 解密 → 按需匹配/缓存** | 运行时效率 | 每次填充解密全部 TOTP（`OtpAutofillSideEffects.kt:205`）随条目数线性变慢 |
| **5** | **A2 配置变更重建依赖图 → 单例化** | 启动速度 | `MainActivity.kt:362-387` 每次旋转重建 Keystore 与对象图，纯浪费 |
| **6** | **B3 重复 new SecurityManager → 注入单例** | 运行时效率 | Keystore/EncryptedSP 被反复构建（OTP/autofill/解析多路径） |
| **7** | **C1 Composable 内直接持有 Room 单例 → 移入 ViewModel** | UI 流畅度/健康 | `remember { getDatabase }` 把数据层耦合进 UI，影响重建与可测性 |
| **8** | **A3 删除空 Koin 容器** | 代码健康/启动 | `startKoin` 无 module，纯死重依赖（已核实） |
| **9** | **E1 删除/合并 `utils/PasswordGenerator`（疑似死代码）** | 代码健康 | 零引用且 API 与 `util` 版冲突，易引发混淆 |
| **10** | **D4 评估 PBKDF2 高迭代解锁时延（IO+进度 UI）** | 稳定性/体验 | 360k 迭代在低端机解锁可达数秒，需确认进度反馈与必要调优 |

> 第 1–3 项属「**低代价、高稳定性收益**」，建议优先落地；第 4–6 项属「**运行时效率热点**」，对大 vault / 频繁填充用户体感明显；第 7–10 项为健康度与体验的中长期项。

---

## 五、落地节奏建议（供你决策，本次未实施）

- **第一轮（低风险速赢）**：D1+D2（协程作用域）、A3（删空 Koin）、E1（删死代码）、A1+A2+B3（SecurityManager 单例化 + 移出主线程）。这几项改动局部、收益明确、回归面小。
- **第二轮（效率热点）**：B1（OTP 解密按需化/缓存）、B2（dataset 解密缓存）、C1（Room 单例移出 Composable）。需配套单测验证填充行为不变。
- **第三轮（健康度/中长期）**：E2/E3/E5 拆分巨型类与统一解密入口、D3 异常可见化、D4 解锁算法评估、E4 明文流量收敛。

> 注：以上均为**分析与建议**，未触碰任何代码。若你认可某一轮，我再按「BugFix / 增量开发」流程落地并走 CI 验证。

---

## 附：关键文件速查
- 启动：`BastionApplication.kt`、`MainActivity.kt`
- DI/单例：`BastionApplication.kt:84`、`MainActivity.kt:362`
- 并发：`BastionApplication.kt:110/120`、`AutofillCipherCallbackActivity.kt:309`、`AutofillPickerActivityV2.kt:1254/1323`、`BastionAutofillServiceNg.kt:98`
- 加密：`MdbxVaultCrypto.kt:40/123/205`、`SecurityManager.kt:26/63/99`
- autofill：`BastionAutofillServiceNg.kt:158/179`、`FilledDataBuilderNg.kt:186`、`OtpAutofillSideEffects.kt:43/205`
- 数据：`PasswordDatabase.kt:52`、`MdbxVaultStore.kt`（>6500 行）、`SecureItemDao.kt:114`
- 冗余：`util/PasswordGenerator.kt:23` vs `utils/PasswordGenerator.kt:8`、`AndroidManifest.xml:59`
