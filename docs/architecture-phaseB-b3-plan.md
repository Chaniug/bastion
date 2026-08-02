# Bastion Phase B.3：PasswordViewModel 拆分执行计划

> **文档目的**：在 B.1/B.2.1/B.2.2/B.2.3 完成后，执行 B.3 —— 把 4162 行的 `PasswordViewModel.kt`
> 按职责簇拆分为多个协调器/工具类。**本文档即约定 #5 要求的"重点改动计划"，确认后逐步实施。**
>
> **创建时间**：2026-08-02
> **状态**：🟡 执行中（集群 1 ✅ / 集群 2 ✅ / 集群 4 ✅；`PasswordViewModel` 4162 → 4044 行；剩集群 3/5/6/7/8）
> **前置**：B.1 ✅、B.2.1 ✅、B.2.2 ✅、B.2.3 ✅（治理目标达成）
> **仓库**：https://github.com/Chaniug/bastion（dev 分支开发，验证后合并 main）
> **硬约束**：**不得引入密码条目 / 验证码（OTP/TOTP）回归**（用户明确要求）

---

## 一、当前状态

- **行数**：4162 行
- **函数数**：~140 个
- **职责簇**：7 个（见下表）
- **内部 new 的协作者**：9 个（未通过 DI 注入）

---

## 二、OTP / 密码防回归闸（每集群必达）

> 这是本项目最高优先级的约束。**每个集群拆分后必须全部满足，否则回退：**

1. `git push dev` 后 **CI `failed=0`**（编译 + 全量测试基线不上升）。
2. **关键守卫测试必须仍绿**（它们直接守卫 OTP/密码/TOTP 路径，任一变红即说明回归）：
   - `OtpAutofillClipboardRegressionGuardTest`（OTP 复制默认开 + 无障碍填充后留在剪贴板）
   - `BiometricUnlockRegressionGuardTest`（守卫 `performOtpAutofillSideEffects` 走 `withContext(Dispatchers.IO)` 而非 `runBlocking` 的 OTP 复制路径；密码加密/MDK 边界）
   - `TotpPasswordBindingRegressionGuardTest`、`PasswordTotpCrossDatabaseBindingGuardTest`（TOTP↔密码绑定）
3. **不改动任何被上述守卫 `substringAfter("fun xxx")` 抽取的函数体文本**（拆分只做"纯搬迁"或"构造注入"，不改既有逻辑与文案）。
4. 每完成一轮 → 真机（荣耀 / Android 17）抽查：**密码填充正确 + OTP 验证码进输入法剪贴板**。

> 任何守卫若因本次重构需"有意演进"，必须先在 B.2.3 治理纪律下改为**保留锚点的容错正则**或**行为测试**，禁止为过 CI 而删除/弱化守卫。

---

## 三、职责簇与拆分目标

| 职责簇 | 行范围 | 函数数 | 拆分目标 | 依赖 |
| --- | --- | --- | --- | --- |
| 顶层类型声明 | 82-149 | — | `CategoryFilter`、`PasswordArchiveFilterController`、`BitwardenRecoveryResult`、`BitwardenSyncRawHistoryItem` → 独立文件（同包） | 无（纯搬迁） |
| Bitwarden 离线缓存 | 240-1090 | ~25 | `BitwardenOfflineSecretCacheFacade` | bitwardenRepository, securityManager |
| KeePass 同步/对齐/TOTP 投影 | 1092-1727 | ~30 | `KeePassSyncCoordinator` | keepassBridge, repository |
| 类别过滤序列化/恢复 | 1728-1960 | ~12 | `CategoryFilterState` | settingsManager |
| 跨存储迁移 | 1961-2210 | ~14 | `PasswordMoveExecutor` | repository, keepassBridge, bitwardenRepository |
| Trash/批量删除 | 2599-2894 | ~14 | `PasswordDeleteOrchestrator` | repository, keepassBridge |
| 归档/取消归档 | 2895-3213 | ~22 | `PasswordArchiveOrchestrator` | repository, keepassBridge |
| 历史记录/自定义字段/主密码 | 3214-4162 | ~25 | `PasswordHistoryRecorder` + `MasterPasswordOps` | securityManager, passwordHistoryManager |

---

## 四、执行顺序（低风险 → 高敏感）

1. **集群 1（无状态类型搬迁）**：`CategoryFilter` + `PasswordArchiveFilterController` → `CategoryFilter.kt`（✅ CI 绿 `30725125903`）；续迁 `BitwardenRecoveryResult` + `BitwardenSyncRawHistoryItem` → `BitwardenSyncTypes.kt`（进行中）。**零逻辑变更**，最安全。
2. **集群 2（Bitwarden 离线缓存）**：抽 `BitwardenOfflineSecretCacheFacade`。
3. **集群 3（KeePass 同步协调器）**：抽 `KeePassSyncCoordinator`（含 TOTP 投影，敏感）。
4. **集群 4（类别过滤状态）**：抽 `CategoryFilterState`（序列化/恢复）。
5. **集群 5（跨存储迁移）**：抽 `PasswordMoveExecutor`。
6. **集群 6（删除/归档编排）**：抽 `PasswordDeleteOrchestrator` + `PasswordArchiveOrchestrator`。
7. **集群 7（主密码/历史）**：抽 `PasswordHistoryRecorder` + `MasterPasswordOps`（含 2 处 TODO 补全）。
8. **构造注入**：把 9 个内部 `new` 的协作者改为构造参数注入。
9. **每拆一簇推 CI + 真机抽查**，确保基线不上升、守卫仍绿。

---

## 五、注意事项

- `CategoryFilter` 密封类（13 分支）随首拆迁到 `CategoryFilter.kt`，全仓引用按"同包"解析，无需改 import。
- `BitwardenRecoveryResult`、`BitwardenSyncRawHistoryItem`、`KeePassCustomFieldFingerprint` 等私有类型，按簇移入各自协调器。
- `changePassword`(3572)、`saveSecurityQuestions`(3608) 含 TODO，拆分时一并补全。
- 所有 `private const`/`private data class` 若仅 VM 内部使用，留在 VM 直至对应簇抽取时一并迁移。

---

## 六、进度跟踪

| 集群 | 内容 | CI | 守卫(OTP/密码) | 真机 | 状态 |
| --- | --- | --- | --- | --- | --- |
| 1 | 顶层类型搬迁（CategoryFilter+PasswordArchiveFilterController→CategoryFilter.kt；BitwardenRecoveryResult+BitwardenSyncRawHistoryItem→BitwardenSyncTypes.kt） | ✅ 30725125903+30725511081 | ✅ 0 失败 | — | ✅ 完成 |
| 2 | Bitwarden 离线缓存 → `BitwardenOfflineSecretCacheFacade` | ✅ 30726186131 | ✅ 0 失败 | ✅ 通过 | ✅ 完成 |
| 3 | KeePass 同步协调器 | — | — | — | ⬜ **延后**（见 §七） |
| 4 | 类别过滤解码 → `CategoryFilterCodec` | ✅ 30726656548 | ✅ 0 失败 | ✅ 通过 | ✅ 完成 |
| 5 | 跨存储迁移 | — | — | — | ⬜ |
| 6 | 删除/归档编排 | — | — | — | ⬜ |
| 7 | 主密码/历史 | — | — | — | ⬜ |
| 8 | 构造注入 | — | — | — | ⬜ |

---

## 七、执行中的顺序调整与踩坑记录（接力 agent 必读）

### 7.1 集群 3（KeePass 同步协调器）为何延后

原计划顺序是 1→2→3，实际执行时把 **集群 4 提前到集群 3 之前**，理由：

- `PasswordViewModel.kt` 中 KeePass 相关引用达 **359 处**，远超集群 2（8 处）与集群 4（33 处）。
- 集群 3 范围内含 **TOTP 投影**（`KeePassTotpProjectionMatcher` 相关路径），
  直接触碰用户明令不得回归的验证码链路。
- 当前守卫对 KeePass↔TOTP 投影的覆盖是「源码文本断言」而非行为测试，
  重构过程中**守卫无法真正兜住语义回归**，只能兜住文本不变。

**结论**：集群 3 应在两个前置条件满足后再动 ——
（a）为 TOTP 投影补一组**行为测试**（Tier A），不再只依赖文本断言；
（b）用户完成一次针对 KeePass 库 + TOTP 的真机专项抽查，确立可信基线。
在此之前优先推进低风险集群（5/6/7 中的无状态部分）。

### 7.2 守卫陷阱：`substringAfter` 抽取型断言（集群 4 实测）

`PasswordArchiveReturnFilterGuardTest` 的断言方式是：

```kotlin
val persistence = source.substringAfter("private fun persistCategoryFilter(")
val archivedBranch = persistence.substringAfter("is CategoryFilter.Archived ->")
    .substringBefore("is CategoryFilter.Local ->")
assertFalse(archivedBranch.contains("updateLastPasswordCategoryFilter"))
```

**陷阱**：Kotlin 的 `substringAfter` 在**找不到分隔符时返回原字符串**（而非空串）。
因此若把 `persistCategoryFilter` 整个搬出 `PasswordViewModel.kt`，
`persistence` 会退化为**整个源文件**，`archivedBranch` 随之覆盖大段无关代码，
断言会以一种**看似合理实则失真**的方式变红或变绿——两种都是错误信号。

**处置**（本次采用）：集群 4 只搬 **decode 侧**（纯函数、无锚点依赖），
`persistCategoryFilter` **刻意留在 VM 内**保住锚点；17 个 `SAVED_FILTER_*`
字面量的唯一真源移入 `CategoryFilterCodec`，VM 内保留同名 `const` 别名，
使 persist 分支的**文本形态完全不变**。

**通用规则**：搬迁任何函数前，先 `grep -rn "substringAfter(\"...fun 目标函数" app/src/test`，
命中即说明该函数是**守卫锚点**，只能原地保留或先将守卫升级为 Tier A 行为测试。

### 7.3 推送网络：GitHub 直连 IP 会**逐 IP、逐端点**失效

实测现象：同一时刻 `140.82.113.3` 对 `https://github.com/...`（网页/克隆）返回 **200**，
但对 `.../info/refs?service=git-receive-pack`（**推送**端点）返回 **000**（连接被中断）。
即「能拉不能推」，仅测 curl 首页会误判为网络正常。

**正确探测方式**（判定某 IP 是否可推送）：

```bash
curl -s -o /dev/null -w "%{http_code}" --resolve github.com:443:<IP> \
  -u "x-access-token:$(gh auth token)" \
  "https://github.com/Chaniug/bastion.git/info/refs?service=git-receive-pack"
# 200 = 可推送；000 = 该 IP 推送端点不通，换 IP
```

**候选 IP**：`140.82.112.3` / `140.82.113.3` / `140.82.114.3` / `140.82.116.3` / `20.205.243.166`
（`api.github.com` 用 `140.82.113.5`）。修改 `/etc/hosts` 后**必须同步写入 `~/.user_hosts`**，
否则工作区重启会被还原。本次多轮推送中可用 IP 从 `.113.3` 漂移到 `.114.3`，
建议接力 agent 直接写一个「探测→切 hosts→重试」的循环脚本，不要手工试。
