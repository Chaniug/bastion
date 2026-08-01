# CI 测试信号失真 · 守卫测试债（交接文档）

> 状态：**问题已定位并留证，尚未修复**。本轮的架构重构尝试已回退，`dev` 代码树与重构前基线逐字节一致。
> 结论优先级：**在修复 CI 信号与清理存量失败之前，不应进行任何文件级/结构级重构。**

## 一、核心结论

### 1. CI 绿色 ≠ 测试通过（信号失真）

`.github/workflows/main.yml:193-195`：

```yaml
- name: Run unit tests (non-blocking)
  continue-on-error: true
  run: ./gradlew :app:testDebugUnitTest --continue --stacktrace
```

`continue-on-error: true` 使单元测试全红时 job 仍判定 `success`。
**后果**：Actions 面板长期显示绿色，实际测试从未全通过；任何新引入的测试失败都不会产生可见信号。

### 2. 存量 22 个失败已长期存在

抽查连续多次 dev 运行，测试结果稳定为 `593 tests completed, 22 failed`：

| 运行 | commit | 结果 |
| --- | --- | --- |
| 30676800080 | `21892e00` | 593 tests, 22 failed |
| 30677940698 | `5a4a6971` | 593 tests, 22 failed |
| 30688394181 | `6294ea5a`（本轮重构） | 593 tests, **24 failed** |
| 30688883488 | `f8745d04`（回退后） | 593 tests, 22 failed |

即：**22 个回归守卫当前处于失效状态**，且由于 CI 恒绿，失效时间点无法从流水线追溯。

### 3. 守卫测试锁的是「源码文本」，不是「运行时行为」

这是本项目最关键的架构约束，直接决定重构可行性。

示例 —— `app/src/test/java/com/bastion/app/repository/MdbxAndroidIntegrationGuardTest.kt:12-22`：

```kotlin
val source = projectFile(
    "app/src/main/java/com/bastion/app/repository/MdbxVaultStore.kt"
).readText()

assertTrue(
    "...",
    source.contains("private const val MDBX_SCHEMA_FORMAT_VERSION = \"MDBX-1\"") &&
        source.contains("private const val MDBX_OFFICIAL_RELEASE_LABEL = \"MDBX-1.0\"") &&
        ...
)
```

同类断言还有 `MultiPasswordSaveRegressionGuardTest.kt:1545`：

```kotlin
mdbxStoreSource.contains("val pendingSyncCount: Int") && ...
```

这类测试把主源码文件当**字符串**读取并断言字面量存在。其含义是：

> **「代码写在哪个文件、用什么可见性修饰符、字面量如何书写」本身就是被测试锁死的契约。**

**推论（重要）**：
- 移动定义到同包新文件 → 断言失败（即使编译通过、行为完全等价）
- `private` 改 `internal` → 断言失败
- 重命名、格式化、拆分巨型类 → 成片断言失败
- 因此 `MdbxVaultStore.kt`（6636 行）、`KeePassKdbxService.kt`（6331 行）等巨型文件在现有守卫下**实质不可拆分**，除非同步改写守卫断言 —— 而那等于主动移除安全网。

## 二、本轮已完成（含回退证据链）

### 尝试的改动（已回退）

- 提交 `6294ea5a`：从 `MdbxVaultStore.kt` 抽出纯定义到新文件 `MdbxVaultModels.kt`
  - 内容：`MDBX_*` 4 个常量 + `MdbxVaultDiagnostics` + `MdbxConflictSummary`
  - 性质：同包移动、无 import 变更、可见性 `private` → `internal`、行为零变化
  - 收益：6636 → 6570 行（约 1%）

### 实际后果

新增 2 个测试失败（均为文本断言破裂，非功能回归）：

1. `MdbxAndroidIntegrationGuardTest > vaultStoreKeepsMdbxOneOfficialMetadataAndLegacyReadableFormats`
   —— 断言 `MdbxVaultStore.kt` 文本含 `private const val MDBX_SCHEMA_FORMAT_VERSION = "MDBX-1"`
2. `MultiPasswordSaveRegressionGuardTest > mdbxDatabaseViewsExposePathNavigationAndSyncAction`
   —— 断言 `MdbxVaultStore.kt` 文本含 `val pendingSyncCount: Int`

### 回退与验证

- 提交 `f8745d04`：`git revert 6294ea5a`
- `git diff 29a3d744 HEAD --stat` → 空输出（与重构前基线逐字节一致）
- CI 运行 30688883488 → `593 tests completed, 22 failed`
- 失败**名单**与基线 `5a4a6971` 逐条 diff → 无差异

判定：回退干净，无残留漂移。

## 三、存量 22 个失败清单（待逐项定性）

每项需判定属于「真实功能回归」还是「守卫断言已过时」。

| # | 测试 |
| --- | --- |
| 1 | `ActiveFillHardeningRegressionGuardTest > notificationFillIsOptInThrottledAndBoundToTheDetectedApp` |
| 2 | `AutofillAuthResultLaunchModeRegressionGuardTest > authResultActivitiesMustNotReuseExistingInstances` |
| 3 | `AutofillDetectionIntegrationGuardTest > parserAndAuthenticationCallbackShareTheConflictPolicy` |
| 4 | `AutofillDropdownClickRegressionGuardTest > dropdownCipherSuggestionsUseDirectValuesAndKeepManualFallback` |
| 5 | `AutofillInlineClickRegressionGuardTest > directInlineSuggestionsUseRealAuthenticationCallbackInsteadOfNoopIntent` |
| 6 | `BiometricUnlockRegressionGuardTest > mdkWrapperRebuildHandlesInvalidatedAndUnrecoverableKeystoreKeys` |
| 7 | `BiometricUnlockRegressionGuardTest > pageSwitchHotPathsDoNotRunAuthOrBitwardenSyncWorkOnMainThread` |
| 8 | `CardBrandIconTest > cardBrandIconFrameFollowsAppThemeInsteadOfSystemTheme` |
| 9 | `ExpressiveTopBarKeyboardRegressionGuardTest > searchFieldRestoresKeyboardWhenPressedAfterSystemDismissal` |
| 10 | `KeePassOperationAvailabilityTest > remoteDatabaseBlocksUnsafeSyncStates` |
| 11 | `MdbxAndroidIntegrationGuardTest > appFacingWordingTreatsMdbxAsOnePointZeroNotTestFeature` |
| 12 | `MdbxAndroidIntegrationGuardTest > passkeyCreateAndMoveUseMdbxAwarePersistencePaths` |
| 13 | `MdbxAndroidIntegrationGuardTest > passwordAndSecureItemMdbxFolderMovesPreserveFolderId` |
| 14 | `MultiPasswordSaveRegressionGuardTest > mdbxMoveAndCopySurfacesExposeAndPersistFolderTargets` |
| 15 | `MultiPasswordSaveRegressionGuardTest > normalPasswordPageRunsBatchDeleteThroughQuickStatusBar` |
| 16 | `MultiPasswordSaveRegressionGuardTest > normalPasswordPageShowsBatchTransferInQuickStatusBar` |
| 17 | `MultiPasswordSaveRegressionGuardTest > saveFailuresAreReportedWithNonSecretDiagnostics` |
| 18 | `PasskeyRemarkAndNavigationGuardTest > authenticatorAndPasskeyShareOneDockDestinationWithBidirectionalControls` |
| 19 | `PasswordSuggestionUiRegressionGuardTest > primaryActionUsesShortLabelInEnglishAndChinese` |
| 20 | `PlusLocalActivationGuardTest > activationCompletesLocallyWithoutBlockingProgressUi` |
| 21 | `SplashThemeResourceTest > startupUsesOnlyTheAndroidSystemSplashLayer` |
| 22 | `SplashThemeResourceTest > systemSplashFallbackUsesBastionM3LightAndDarkColors` |

分布：Autofill 5、MultiPasswordSave 4、Mdbx 3、Biometric 2、Splash 2、其他 6。

## 四、建议路线（按优先级，均未开始）

### 第 1 步：恢复 CI 信号（零业务风险）

仅改 workflow，不碰任何业务代码。方案：保留 `continue-on-error`，但新增基线阈值校验 —— 解析 `app/build/test-results/testDebugUnitTest/*.xml`，当 `failures + errors` **超过基线 22** 时显式 fail。

效果：存量债不阻塞出包，但任何**新增**失败立即可见。这是防止「第 23、24 个失败悄悄混入 main」的最小闭环。

### 第 2 步：清理 22 个存量失败

逐项定性并修复；每修复一项同步下调第 1 步的基线阈值，形成棘轮效应，直至归零。归零后可将 `continue-on-error` 改为阻塞。

### 第 3 步：架构优化（前置依赖 1、2）

架构本身无设计错误（Provider 模式 + 13 个 Repository，分层清晰）。真实问题是**规模失控**叠加**文本锁守卫**：

- 660 个 Kotlin 文件、约 277,281 行
- 巨型文件：`MdbxVaultStore.kt` 6636、`KeePassKdbxService.kt` 6331、`WebDavHelper.kt` 5442
- `util/`（12 文件）与 `utils/`（53 文件）双目录并存 —— 合并受阻于 `PasswordGenerator.kt` 命名冲突（两份**不同实现**：`util/` 839 行含 zxcvbn/passphrase/PIN，`utils/` 140 行简易版），需先决定保留哪一份

**在第 1、2 步完成前推进第 3 步，等同于在没有信号的情况下改动承重结构。**

## 五、给接力 agent 的提醒

1. 本仓库 `Run unit tests` 非阻塞，**不要以 Actions 绿色作为测试通过依据**。必须拉日志核对 `NNN tests completed, MM failed`，并与基线 22 比对。
2. 核对方式：`gh run view <run-id> --log | grep -E "tests completed|FAILED$"`，将失败**名单**与基线 diff，仅比对数字不足以发现「一个修好、一个新坏」的抵消情况。
3. 沙箱内 `api.github.com` 需在 `/etc/hosts` 固定为 `20.205.243.168`，`github.com` 固定为 `140.82.113.3`，否则出现 TLS handshake timeout / GnuTLS recv error；`git push` 偶发失败需重试。
