# Bastion Phase B.3 — 引入 mockk 补行为测试计划

> **文档目的**：按准则 #5 要求，在落地 mockk 基础设施与行为测试前写出完整计划，用户确认后执行。
>
> **创建时间**：2026-08-02
> **状态**：📋 待确认
> **仓库**：https://github.com/Chaniug/bastion（dev 分支开发）
> **硬约束**：**不得引入密码条目 / 验证码（OTP/TOTP）回归**

---

## 一、为什么要引入 mockk

| 现状 | 后果 |
| --- | --- |
| `PasswordRepository` 是 Kotlin `class`（79 公开方法，非接口） | 不能手写 Fake 实现 |
| `SecurityManager` 是 Kotlin `class`（非接口） | 同上 |
| 测试依赖仅 JUnit + coroutines-test，无 mock 框架 | 无法隔离 VM 依赖，写不了行为测试 |
| 剩余集群 3/5c/6/7 全是零守卫覆盖 | 没有回归网，抽一个就可能踩一个密码/OTP bug |

引入 `mockk`（1 行 `testImplementation`）后，可以用 `mockk<PasswordRepository>()` + `coEvery` 伪造
数据库/加密行为，只测 VM 编排逻辑，不依赖 SQLite/Android Framework。

---

## 二、改动范围（三步走）

### Step 0：依赖与版本（1 文件，~3 行）

| 文件 | 操作 | 内容 |
| --- | --- | --- |
| `Bastion/gradle/libs.versions.toml` | 新增 version | `mockk = "1.13.12"`（支持 Kotlin 2.0.21 的最新稳定版） |
| `Bastion/gradle/libs.versions.toml` | 新增 library | `mockk = { group = "io.mockk", name = "mockk", version.ref = "mockk" }` |
| `Bastion/app/build.gradle` | 新增依赖 | `testImplementation libs.mockk` |

> **零风险**：`testImplementation` 不进 APK，仅 unit test classpath。

### Step 1：为集群 6（删除/归档）补行为测试（~2-3 个测试文件）

**为什么从集群 6 开始**：
- 删除/归档是用户最在意的「密码条目」热路径。
- 23 个函数中 22 个零测试引用，完全没有网。
- 核心逻辑清晰：`deletePasswordEntry` 决定走 Bitwarden 删除队列 / trash / 直接删除；
  `archivePassword` 决定走 KeePass 组路径 / Bitwarden 标记 / Local 标记。
- 补完测试后集群 6 抽取时就有回归网了。

**测试文件清单**：

1. **`PasswordDeleteOrchestratorTest.kt`**（约 80-120 行）
   - `deletePasswordEntry` 分流三态测试：Bitwarden cipher → 调 `handleBitwardenQueuedDelete`；trash 开启 → 调 `moveEntryToTrash`；trash 关闭 → 调 `permanentlyDeleteEntry`
   - `deletePasswordEntriesBatch` 批量分流 + `onProgress` 回调计数
   - `permanentlyDeleteEntry` 与 `permanentlyDeleteEntryLocalOnly` 的 repository 调用链验证

2. **`PasswordArchiveOrchestratorTest.kt`**（约 80-120 行）
   - `archivePassword` → `archivePasswordsInternal` → `archiveSingleEntry` 调用链
   - `archiveEntryByProvider` 三态：KeePass 归档路径创建成功/失败/跳过
   - `unarchivePasswordsAwait` → `unarchiveEntryByProvider` 恢复路径
   - `buildArchiveSyncMeta` / `buildUnarchiveSyncMeta` 纯函数输出验证（不需要 mock，直接用 fixture 测）

**mock 策略**：
- `PasswordRepository` mock：`coEvery` 伪造所有 DB 操作
- `SecurityManager` mock：不需要（删除/归档不碰加密）
- `keepassBridge`：用 `KeePassCompatibilityBridge` 的 mock（需要时 `mockk` 或 spy）
- 测试用 `runTest`（coroutines-test 已有）+ `StandardTestDispatcher` 替代 `viewModelScope.launch`

### Step 2：为集群 5c（跨存储迁移）补行为测试（~1 个测试文件）

- **`PasswordMoveExecutorTest.kt`**（约 100-150 行）
  - `movePasswordsToCategory` 基本路径
  - `movePasswordsToKeePassDatabase` / `movePasswordsToKeePassGroup` KeePass 路径
  - `movePasswordsToBitwardenFolder` Bitwarden 路径
  - `movePasswordsToKeePassInternal` + `deleteMovedKeePassPasswordSources` + `materializeMovedKeePassAttachments` 附件门禁

### Step 3：集群 6/5c 抽取（在测试网建成后）

完成 Step 1-2 后，就可以按原计划抽取集群 6（`PasswordDeleteOrchestrator` + `PasswordArchiveOrchestrator`）
和 5c（`PasswordMoveExecutor`），每次抽取后跑行为测试 + CI + 真机抽查。

---

## 三、不进 APK 的证明

```gradle
testImplementation libs.mockk    // ← 仅 unit test compile classpath
```

- `testImplementation` 作用域不参与 `assembleDebug` / `assembleRelease` 的 classpath
- APK 大小完全不变
- 真机安装不受任何影响

---

## 四、风险与防退

| 风险 | 对策 |
| --- | --- |
| mockk 版本与 Kotlin 2.0.21 不兼容 | 先 `./gradlew test` 验证编译通过，不通过则降级/升级 mockk |
| 行为测试与文本守卫测试命名冲突或执行顺序问题 | 新增测试类放在独立 package `viewmodel.behavior` 下 |
| 引入 mockk 后 CI 缓存失效导致首次 build 变慢 | 一次性成本，后续增量编译恢复 |
| 用户真机验证的 7 关键守卫仍以文本断言方式运行 | mockk 行为测试与文本守卫并行运行，互不替代 |

---

## 五、执行顺序（含 CI / 真机闸）

```
Step 0（依赖） → CI 验证编译通过
    ↓
Step 1（集群 6 行为测试） → CI green → 推 dev → 合并 main
    ↓
Step 1 后抽取集群 6 → CI green + 守卫 green → 推 dev → 合并 main → 真机抽查
    ↓
Step 2（集群 5c 行为测试） → CI green → 推 dev → 合并 main
    ↓
Step 2 后抽取集群 5c → CI green + 守卫 green → 推 dev → 合并 main → 真机抽查
    ↓
重复：集群 7 行为测试 → 抽取 → … 直到 B.3 全部完成
```

每步都是「先补网、再捕鱼」——有测试兜底后再动代码。

---

## 六、与已完工集群的关系

- 集群 1/2/4/5a/5b **不回溯**（它们已完成、CI 绿、真机绿）。
- 已抽出的 `PasswordEntryMatching` 纯函数不需要 mock，已有 JUnit 纯数据测试覆盖。
- `CategoryFilterCodec` 同样不需要 mock。

---

## 七、确认清单（请用户在开始前回复）

- [ ] 接受 `testImplementation` 引入 mockk（不进 APK、不影响真机安装包大小）
- [ ] 接受按「集群 6 → 5c → 7 → 3」的顺序补行为测试（从最紧急的密码条目路径开始）
- [ ] 接受每步独立 CI + 真机抽查闸门
