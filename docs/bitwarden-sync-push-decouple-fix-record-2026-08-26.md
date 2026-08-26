# Bitwarden 同步提速改动后的 CI 修复记录（2026-08-26）

> 背景：commit `10228d6`（feat(bitwarden): 上传与全量下载解耦 P0+P1）推上 `dev` 后，
> GitHub Actions 的 `Android CI debug` 与 `CodeQL Advanced` 双双报错。
> 本文记录根因、修复步骤与验证结果，供后续接力开发参考。
> 相关设计文档见 `docs/bitwarden-sync-push-decouple-plan-2026-08-26.md`。

---

## 一、根因

commit `10228d6` 为了实现「LOCAL_MUTATION 走只上传路径（sync 跳过整库下载）」做了两处签名级改动，
但**只改了生产代码，未同步更新两处调用点与三处静态代码守卫测试**：

### 1. `executeSync` lambda 漏传 `reason`（编译失败，Android CI #488 / CodeQL #26）

- `BitwardenSyncOrchestrator.kt:96` 将构造参数签名改为
  `suspend (vaultId: Long, silent: Boolean, reason: SyncTriggerReason) -> SyncExecutionOutcome`（三参）。
- 但 `BitwardenViewModel.kt:173` 构造 orchestrator 时传入的 lambda 仍是两参
  `{ vaultId, silent -> runSync(vaultId = vaultId, silent = silent) }`，
  未把新增的 `reason` 透传给 `runSync`。

→ Kotlin 编译报错：
```
e: BitwardenViewModel.kt:173:23 Argument type mismatch:
   actual type is 'suspend (Long, Boolean) -> SyncExecutionOutcome',
   but 'suspend (Long, Boolean, SyncTriggerReason) -> SyncExecutionOutcome' was expected.
```

**修复**（`8b739cf0`）：lambda 改为三参，`reason` 透传给 `runSync`：
```kotlin
executeSync = { vaultId, silent, reason -> runSync(vaultId = vaultId, silent = silent, reason = reason) }
```
> 注：这一步是 commit `10228d6` 设计意图（「executeSync 透传 reason」）的补全。
> 之前 reason 取默认值 `MANUAL`，会导致 `LOCAL_MUTATION` 触发也走全量 reconcile，违背该 commit 的解耦设计。

### 2. 守卫测试断言未跟上源码签名演进（测试回归，Android CI #489）

编译修复通过后，`Android CI #489` 出现 **2 个新测试失败**（`New test regressions`）：

| 失败测试 | 断言 | 根因 |
|---|---|---|
| `legacySendRefreshApiStaysDeprecatedBecauseItBypassesCoordinator` | `syncSection.contains("suspend fun sync(vaultId: Long)")` 与 `... { sync(vaultId) }` | `sync` 签名改为 `sync(vaultId: Long, pullAfterPush: Boolean = true)`，`BitwardenRepositorySync.kt` 内调用改为 `sync(vaultId, pullAfterPush)` |
| `pageSwitchHotPathsDoNotRunAuthOrBitwardenSyncWorkOnMainThread` | `orchestratorSource.contains("executeSync(vaultId, silent)")` | orchestrator 内调用改为 `executeSync(vaultId, silent, reason)` |

**修复**（`fd8edaae`）：
- `BitwardenRepositorySyncTest.kt`：sync 签名断言改为 `"suspend fun sync("` + 校验 `pullAfterPush`；
  requestAndAwait 内调用断言改为 `sync(vaultId, pullAfterPush)`。
- `BiometricUnlockRegressionGuardTest.kt`：executeSync 调用断言改为 `executeSync(vaultId, silent, reason)`。
- `PasskeySyncMergeGuardTest.kt`（防脆弱顺带修正）：`substringAfter("suspend fun sync(")`，
  避免旧写法 `substringAfter("suspend fun sync(vaultId: Long)")` 在找不到 delimiter 时退化为全文件截取（假通过）。

> 守卫测试的核心意图保持不变：`sync`/`refreshSends` 保持 `@Deprecated`、UI/worker 走 `syncViaCoordinator`、
> Bitwarden 同步在 `withContext(Dispatchers.IO)` 中执行（离开主线程）。

---

## 二、修复 commit 清单（均在 `dev` 分支）

| commit | 说明 |
|---|---|
| `8b739cf0` | fix: executeSync lambda 补 reason 参数透传（修复编译失败） |
| `fd8edaae` | fix(test): 更新回归守卫断言以匹配 sync 多参签名与 executeSync 三参 |

---

## 三、CI 验证结果（针对最新 commit `fd8edaae`）

| 工作流 | 运行 | 结论 |
|---|---|---|
| Android CI debug | #490 | ✅ **Success**（`total=653 failed=0 baseline=0 verdict=PASS`，`build_failed=false`，产出 `debug-apk` 46.9MB） |
| CodeQL Advanced | #28 | ✅ **Success** |

> 额外确认：commit `8b739cf0` 的 CodeQL Advanced #27 已 **success**（编译错误确实已修复）。

---

## 四、给后续开发者的提醒

1. **签名演进必须同步守卫测试**：项目里有大量「读源码字符串断言」的静态代码守卫测试
   （`BitwardenRepositorySyncTest`、`BiometricUnlockRegressionGuardTest`、`PasskeySyncMergeGuardTest` 等），
   改方法签名/调用形态时，需同步更新这些测试的断言字符串，否则编译通过后 CI 会因守卫失败。
2. **`substringAfter("...")` 找不到 delimiter 时返回整个字符串**（不抛异常），
   会导致守卫「假通过」，务必让 delimiter 与新签名匹配。
3. 推送使用 CDN 镜像（本环境 github.com 直连 TLS 不稳）：
   ```bash
   git push https://ghp_<TOKEN>@gh-proxy.com/https://github.com/Chaniug/bastion.git dev
   ```
