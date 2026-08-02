# Bastion Phase B.3 — 引入 mockk 补行为测试计划

> **文档目的**：按准则 #5 要求，在落地 mockk 基础设施与行为测试前写出完整计划，用户确认后执行。
>
> **创建时间**：2026-08-02
> **状态**：🟢 **已全部完成** —— Step 0 ✅（CI 30728150825）、Step 1 ✅（CI 30728548671，`total=559 failed=0`）、
> Step 2 ✅（CI 30731085994，`total=571 failed=0`）、Step 3 ✅（集群 6/5c/7 抽取完成，最终 CI `total=583 failed=0`，见 §九）
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

### Step 0：依赖与版本（✅ 已完成，CI 30728150825 绿）

| 文件 | 操作 | 内容 |
| --- | --- | --- |
| `Bastion/gradle/libs.versions.toml` | 新增 version | `mockk = "1.13.17"` |
| `Bastion/gradle/libs.versions.toml` | 新增 library | `mockk = { group = "io.mockk", name = "mockk", version.ref = "mockk" }` |
| `Bastion/app/build.gradle` | 新增依赖 | `testImplementation libs.mockk` |
| `.../viewmodel/behavior/MockkInfrastructureSmokeTest.kt` | 新增 | 3 个冒烟测试，证明 mockk 在本项目约束下可用 |

> **零风险**：`testImplementation` 不进 APK，仅 unit test classpath。

#### ⚠️ 版本选型：**必须**用 1.13.17，不得升到 1.14.x

排查过程：查 mockk 仓库各 tag 的构建配置，确认它自身用哪个 Kotlin 编译。

| mockk 版本 | 自身构建用的 Kotlin | 产物 metadata | 与本项目（kotlin 2.0.21）| 
| --- | --- | --- | --- |
| 1.13.17（1.13.x 末版）| `kotlinDefault = "2.0.0"` | 2.0 | ✅ 兼容 |
| 1.14.x（含 latest 1.14.11）| `kotlin = "2.2.21"` | 2.2 | ❌ 报 `incompatible version of Kotlin` |

Kotlin 编译器读不了比自身新的 metadata。1.14.x 会在**编译期**直接失败，
不是运行时警告。该结论已写进 `libs.versions.toml` 的注释，防止后续 agent 盲目升级。

若将来项目把 `kotlin` 升到 2.2+，可同步把 mockk 升到 1.14.x。

### Step 1：为集群 6（删除/归档）补行为测试（✅ 已完成，CI 30728548671，`total=559 failed=0`）

**为什么从集群 6 开始**：
- 删除/归档是用户最在意的「密码条目」热路径。
- 23 个函数中 22 个零测试引用，完全没有网。
- 核心逻辑清晰：`deletePasswordEntry` 决定走 Bitwarden 删除队列 / trash / 直接删除；
  `archivePassword` 决定走 KeePass 组路径 / Bitwarden 标记 / Local 标记。
- 补完测试后集群 6 抽取时就有回归网了。

**实际落地文件**（都在 `app/src/test/.../viewmodel/behavior/`）：

| 文件 | 内容 |
| --- | --- |
| `MainDispatcherRule.kt` | 用 `UnconfinedTestDispatcher` 顶替 `Dispatchers.Main`，使 `viewModelScope.launch` 在测试中**同步**执行，调用后可立即断言 |
| `PasswordDeleteBehaviorTest.kt` | 7 个测试：本地软删除、Bitwarden 墓碑、无 cipherId 回落、批量单次提交、进度回调首末值、空列表短路 |
| `PasswordArchiveBehaviorTest.kt` | 8 个测试：本地/Bitwarden 归档元数据、已归档短路、回收站条目不得归档、批量归档、取消归档清标志、**来源以归档元数据为准** |

**mock 策略（实测可行，接力 agent 照抄即可）**：
- `PasswordRepository` / `SecurityManager` 均为 Kotlin final class → 只能 `mockk`，且用 `relaxed = true`
  （编排链会顺带触碰几十个仓库方法，逐个打桩不现实）。
- 用 `slot<PasswordEntry>()` + `capture()` 抓写回实体，断言其状态字段，
  这比只验证「方法被调用了」强得多。

#### 夹具关键约定：`context = null`

`PasswordViewModel` 的多个协作者由 `context` 派生，传 null 后全部退化：

| 字段 | 表达式 | context=null 时 |
| --- | --- | --- |
| `settingsManager` | `context?.let { SettingsManager(it) }` | null |
| `bitwardenRepository` | `context?.let { BitwardenRepository.getInstance(...) }` | null |
| `keepassBridge` | 需 context + `localKeePassDatabaseDao` 同时非空 | null |

好处：删除路径收敛到纯本地分支，断言目标唯一（只剩 `repository` 交互），
且完全避开 Android Framework。

#### 三个实测踩坑点（接力 agent 必读）

1. **`viewModelScope` 需要 Main dispatcher**：不装 `MainDispatcherRule` 直接构造 VM 会抛
   `IllegalStateException: Module with the Main dispatcher had failed to initialize`。
2. **`init` 块的并发污染是伪风险**：init 起了 3 个 `Dispatchers.IO` 维护任务，
   但它们都以 `repository.getAllPasswordEntries().first()` 开头 ——
   relaxed mock 返回的 Flow 不发射任何元素，`first()` 抛 `NoSuchElementException`，
   被 `runCatchingObserved` 吞掉，**走不到任何写方法**，因此不会干扰 `coVerify` 计数。
3. **`OperationLogger` 在单测中安全**：其 `log()` 首行判 `database == null` 即早退，
   而单测从不调 `OperationLogger.init(context)`。配合 `unitTests.returnDefaultValues = true`
   （本项目已开启），`android.util.Log.*` 也不会抛 "not mocked"。

#### 本夹具覆盖不到的分支（已登记，勿遗漏）

`settingsManager = null` ⇒ `trashSettings = null` ⇒ `trashEnabled` 恒取兜底值 `true`。
因此**「回收站关闭 → 永久删除」分支无法覆盖**
（`permanentlyDeleteEntry` / `permanentlyDeleteEntryLocalOnly` / `applyLocalDeleteBatch` 的 else 分支）。
该分支需等**集群 8 把 `settingsManager` 改为构造注入**后补齐。

### Step 2：为集群 5c（跨存储迁移）补行为测试（✅ 已完成，CI 30731085994，`total=571 failed=0`）

- **`PasswordMoveBehaviorTest.kt`**（12 个 mockk 用例，§7.9/§7.10 有完整记录）
  - `movePasswordsToCategory` 基本路径
  - `movePasswordsToKeePassDatabase` / `movePasswordsToKeePassGroup` KeePass 路径
  - `movePasswordsToBitwardenFolder` Bitwarden 路径
  - `movePasswordsToKeePassInternal` + `deleteMovedKeePassPasswordSources` + `materializeMovedKeePassAttachments` 附件门禁

### Step 3：集群 6/5c/7 抽取（✅ 已完成）

完成 Step 1-2 后，按「先补网、再捕鱼」模式完成全部抽取：
- 集群 6：`PasswordDeleteOrchestrator` + `PasswordArchiveOrchestrator`（CI 30729317779，`total=559 failed=0`）
- 集群 5c：`PasswordMoveExecutor`（CI 30732001927，`total=571 failed=0`）
- 集群 7：`PasswordHistoryRecorder` + `MasterPasswordOps`（CI 30735924374，`total=583 failed=0`）
- 集群 8：3 个无依赖协作者构造注入（同 CI）
- 每个集群抽取后行为测试 + CI + 真机抽查全部通过；`PasswordViewModel` 4162 → 3472 行

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

## 七、确认清单

- [x] 接受 `testImplementation` 引入 mockk（不进 APK、不影响真机安装包大小）
- [x] 接受按「集群 6 → 5c → 7 → 3」的顺序补行为测试（从最紧急的密码条目路径开始）
- [x] 接受每步独立 CI + 真机抽查闸门

> 用户已于 2026-08-02 确认。

---

## 八、附带产出：CI 测试统计 annotation（排错基建）

### 问题

准则 #3 要求「排错要看 GitHub Actions 运行日志」，但本地环境里：

```
results-receiver.actions.githubusercontent.com  → 198.18.0.58   （保留段）
productionresultssa9.blob.core.windows.net      → 198.18.0.60   （保留段）
```

`198.18.0.0/15` 是 RFC 2544 基准测试保留段，说明这两个域名被 **DNS 劫持**。
后果是 `gh run view --log` 与 `gh run download` **全部返回 EOF**——
run log 和 artifact 的真实下载源都在 blob 上，只有 `api.github.com` 是通的。

这不是偶发网络抖动，而是稳定复现的环境特性，会持续拖慢每一次排错。

### 解决

在 `main.yml` 的基线闸门里加一行 GitHub Actions workflow command：

```python
print(
    f"::notice title=Unit test stats::total={total} failed={failed} "
    f"baseline={baseline} verdict={verdict}"
)
```

`::notice` 会被 Actions 转成 **check-run annotation**，而 annotation 走 checks API
（`api.github.com`），是本环境唯一稳定可读的回传通道。

### 用法（接力 agent 直接抄）

```bash
CR=$(gh api repos/Chaniug/bastion/commits/<sha>/check-runs --jq '.check_runs[0].id')
gh api "repos/Chaniug/bastion/check-runs/$CR/annotations" \
  --jq '.[] | "\(.annotation_level) | \(.title) | \(.message)"'
# → notice | Unit test stats | total=559 failed=0 baseline=0 verdict=PASS
```

失败时基线闸门本就会发 `::error`，同样能通过这个通道读到失败测试名单。
**不必再尝试下载日志或 artifact。**

---

## 九、收官记录（2026-08-02）

行为测试网 + 抽取全部完成，B.3 正式收官：

| 阶段 | 内容 | CI | 测试数 |
| --- | --- | --- | --- |
| Step 0 | mockk 1.13.17 依赖 | 30728150825 | — |
| Step 1 | 集群 6 行为测试（删除/归档 15 用例） | 30728548671 | 559 |
| 集群 6 抽取 | `PasswordDeleteOrchestrator` + `PasswordArchiveOrchestrator` | 30729317779 | 559 |
| Step 2 | 集群 5c 行为测试（move* 12 用例） | 30731085994 | 571 |
| 集群 5c 抽取 | `PasswordMoveExecutor` | 30732001927 | 571 |
| 集群 7 测试网 | 主密码/历史 10 用例（含 3 处踩坑修复，见 §7.11/§7.12） | 30735924374 | 583 |
| 集群 7 抽取 | `PasswordHistoryRecorder` + `MasterPasswordOps` | 30735924374 | 583 |
| 集群 8 注入 | 3 个无依赖协作者构造参数 | 30735924374 | 583 |

**最终**：`total=583 failed=0`，`PasswordViewModel` 4162 → 3472 行（**-690**），
dev/main 已同步，用户真机验证通过。集群 3（KeePass 同步协调器）用户确认**保持现状**
（§7.14 记录，含将来若重新评估的判断依据）。
