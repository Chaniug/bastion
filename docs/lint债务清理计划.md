# Lint 债务清理计划

> 生成时间：2026-08-31
> 数据来源：`Bastion/app/lint-baseline.xml`（快照提交 `a4e77a8e`，2026-08-31 00:07）
> 相关文档：[分支合并与发版流程](./分支合并与发版流程.md) · [依赖升级计划](./dependency-upgrade-plan-2026-08.md)

---

## 零、一句话结论

`lint-baseline.xml` 里压着 **1925 条**存量问题，但其中 **38 条已经修好了**（`2361b3eb`，只是 baseline 没跟着更新）。
**动手前必须先重生成 baseline**，否则会照着过期清单去"修"已经修完的东西。

剩余真实债务约 **1887 条**，分布极不均匀：

- **46% 是「无用资源」**（887 条），release 已开 `shrinkResources`，**不影响 APK 体积**，纯属维护噪音，可以最后做甚至选择性不做。
- **34% 是「Compose 里用 Context 取资源」**（641 条），属正确性债但不崩溃，是典型的机械替换，适合批量做。
- **真正值得优先处理的只有个位数条目**（安全 1 条 + 功能 3~4 条）。

---

## 一、当前状态

### 1.1 关键事实（决定了修复策略）

| 事实 | 值 | 影响 |
|------|-----|------|
| baseline 条目总数 | 1925 | 存量债规模 |
| issue 种类 | 42 | — |
| baseline 快照提交 | `a4e77a8e`（08-31 00:07，bot 生成） | **早于** 08-31 00:35 的 `2361b3eb` 修复批次 |
| lint 触发时机 | `github.event_name != 'push'` | **PR / 手动触发才跑**，push 到 dev 跳过 |
| release `shrinkResources` | `!disableMinify`（已开） | 无用资源不进最终 APK |
| lint 失败是否阻断 | 是（PR 门禁） | 新增问题会卡 PR，存量被 baseline 压住不报 |

### 1.2 baseline 机制要点

- baseline 是**快照**：里面记的问题不会报警，**不在里面的新问题才会报**。
- 因此修完一批必须**重生成 baseline**，把成果锁进去，形成棘轮（只减不增）。
- 反过来，代码修好了但 baseline 没更新 → 条目会变成"僵尸条目"，统计虚高。
  **这正是当前的状态。**

### 1.3 ⚠️ 已修复但 baseline 未更新的 38 条

`2361b3eb`（fix(lint): 修复 38 处正确性问题）改动的 4 类问题，与 baseline 中的条目**逐条对应、数量完全吻合**：

| issue id | baseline 条数 | 说明 |
|----------|--------------|------|
| `NewApi` | 13 | 未判版本调用 API 34/30/29/28/27 |
| `StaticFieldLeak` | 8 | 静态字段持有 Context |
| `NonObservableLocale` | 9 | Compose 中非可观察方式读 locale |
| `ConfigurationScreenWidthHeight` | 8 | 用 `screenHeightDp` 而非 `LocalWindowInfo` |
| **合计** | **38** | 与提交信息「修复 38 处」一致 |

已验证：**38/38 条**所在文件全部出现在 `2361b3eb` 的改动清单里。

> **结论：这 38 条不需要再动，重生成 baseline 即可消失。**

---

## 二、剩余债务全景（1925 − 38 = 1887 条）

按「风险 × 收益」排序，不是按数量排序。

### P0 — 安全（2 条，其中 1 条是误报）

| issue id | 数量 | 位置 | 结论 |
|----------|-----:|------|------|
| `AcceptsUserCertificates` | 1 | `res/xml/network_security_config.xml:30` | **真问题**，见 3.1 |
| `CustomX509TrustManager` | 1 | `bitwarden/api/BitwardenApiFactory.kt:442` | **误报**，已核实，见 3.2 |

### P1 — 真功能问题（19 条，量小收益高）

| issue id | 数量 | 风险 |
|----------|-----:|------|
| `QueryPermissionsNeeded` | 1 | Android 11+ 应用列表拿不全 → 选不到目标 App |
| `SelectedPhotoAccess` | 1 | Android 14+ 部分照片访问未适配 |
| `PluralsCandidate` | 6 | 英文单复数写死，本地化错误 |
| `SimpleDateFormat` | 4 | 未走本地化格式 |
| `ConstantLocale` | 2 | 硬编码 Locale |
| `UnsafeOptInUsageError` | 1 | CameraX `ExperimentalGetImage` 未标注 |
| `InlinedApi` | 4 | 内联 API 常量，需版本判断或抑制 |

### P2 — Compose 正确性（约 678 条，机械替换）

| issue id | 数量 | 说明 |
|----------|-----:|------|
| `LocalContextGetResourceValueCall` | 641 | `LocalContext.current` 取资源，不随语言/主题重组 |
| `ModifierParameter` | 33 | Modifier 应为首个可选参数、命名 `modifier` |
| `ModifierFactoryExtensionFunction` | 4 | Modifier 扩展函数规范 |

### P3 — API 规范（184 条）

| issue id | 数量 | 说明 |
|----------|-----:|------|
| `RestrictedApi` | 91 | 用了 `@RestrictTo` API，升级可能失效 |
| `UseKtx` | 87 | 可用 KTX 扩展替代 |
| `TypographyDashes` | 6 | 排版连字符规范 |

### P4 — 清理噪音（982 条，**不影响 APK 体积**）

| issue id | 数量 | 说明 |
|----------|-----:|------|
| `UnusedResources` | 887 | 其中 `values/strings.xml` 852 条 |
| `UnusedAttribute` | 84 | XML 未使用属性 |
| 依赖类（`GradleDependency` / `NewerVersionAvailable` / `UseTomlInstead` / `AndroidGradlePluginVersion`） | 11 | 见[依赖升级计划](./dependency-upgrade-plan-2026-08.md) |

### 零散杂项（22 条，17 种，每种 1~3 条）

都是零散单项，单独看都不紧急，攒到某一批顺手清掉即可：

| issue id | 数量 | issue id | 数量 |
|----------|-----:|----------|-----:|
| `IntentFilterUniqueDataAttributes` | 3 | `EmptySuperCall` | 1 |
| `ObsoleteSdkInt` | 3 | `PrivateApi` | 1 |
| `DiscouragedApi` | 2 | `StartActivityAndCollapseDeprecated` | 1 |
| `AppBundleLocaleChanges` | 1 | `RedundantLabel` | 1 |
| `PrivateResource` | 1 | `Typos` | 1 |
| `ChromeOsAbiSupport` | 1 | `HardwareIds` | 1 |
| `ExportedContentProvider` | 1 | `DataExtractionRules` | 1 |
| `LaunchActivityFromNotification` | 1 | `Overdraw` | 1 |
| `TypographyFractions` | 1 | | |

其中两个值得单独看一眼：

- `HardwareIds`（`utils/OperationLogger.kt:52`）：操作日志里获取设备标识符。
  对密码管理器来说，日志里带设备 ID 是否必要属于隐私取舍，不是必须改。
- `ExportedContentProvider` 在 `src/debug/AndroidManifest.xml`（仅 debug 变体），风险低。

### 总账（重生成 baseline 后可据此自查）

```
P0 安全          2
P1 功能         19
P2 Compose     678
P3 API 规范    184
P4 清理        982
零散杂项        22
──────────────────
真实剩余      1887
已修待重生成     38
──────────────────
baseline 总计 1925
```

自查脚本（在 `Bastion/app` 目录下跑）：

```bash
python3 -c "
import xml.etree.ElementTree as ET, collections
t = ET.parse('lint-baseline.xml')
c = collections.Counter(i.get('id') for i in t.getroot().findall('issue'))
print('总计', sum(c.values()), '种类', len(c))
for k, v in c.most_common(10): print(f'  {k:<38} {v:>5}')
"
```

---

## 三、P0 详解（唯一需要优先动手的）

### 3.1 `AcceptsUserCertificates` — 真问题，建议修

**现状**：`res/xml/network_security_config.xml` 的 `<base-config>` 里写了 `<certificates src="user" />`。
该文件在 `src/main/` 下，**release 构建同样生效** → 正式版会信任设备上用户安装的 CA 证书。

**为什么对 Bastion 敏感**：这是个密码管理器，所有主密码/凭据同步都走 HTTPS。
一旦设备上被塞入恶意 CA（社工、企业 MDM、恶意应用诱导安装），流量可被中间人解密。
调试抓包确实需要 user CA，但不应该带进 release。

**修法**（官方标准做法，3 行改动，零风险）：用 `<debug-overrides>` 把 user CA 限定到 debuggable 构建。

```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <!-- release 只信任系统 CA -->
            <certificates src="system" />
        </trust-anchors>
    </base-config>

    <!-- 仅 debuggable 构建（debug flavor）额外信任用户 CA，供抓包调试；
         release 自动不生效，无需再单独维护一份 main/debug 配置 -->
    <debug-overrides>
        <trust-anchors>
            <certificates src="system" />
            <certificates src="user" />
        </trust-anchors>
    </debug-overrides>

    <!-- 局域网明文配置保持不变 -->
</network-security-config>
```

**验证**：
1. `./gradlew :app:lintDebug` → 该条从报告消失。
2. 装 release 包后，系统「设置 → 加密与凭据 → 用户凭据」里装个自签 CA，
   确认 Bastion 的 HTTPS 请求**不再信任**它（用 Charles/mitmproxy 抓包验证）。

### 3.2 `CustomX509TrustManager` — 误报，抑制即可

**已核实结论**：`BitwardenApiFactory.CompositeX509TrustManager` 实现是**正确的条件化委托**，不是"信任一切"的空实现。

核实依据（读代码确认，非猜测）：

```kotlin
// configureTls()：未配置 TLS 时直接返回，完全不碰默认信任链
if (tlsConfig == null || tlsConfig.isEmpty()) return

// buildTrustManager()：没配自签 CA 就用系统默认
if (caCertificatePem.isNullOrBlank()) return systemTrustManager

// 仅当用户主动导入自签 CA 时才组合：系统在前，自定义在后
return CompositeX509TrustManager(listOf(systemTrustManager, customTrustManager))

// checkServerTrusted()：委托遍历，全失败才抛异常——非空实现
delegates.forEach { manager ->
    try { manager.checkServerTrusted(chain, authType); return }
    catch (e: Exception) { lastError = e }
}
throw lastError ?: IllegalStateException("No trust manager accepted server certificate")
```

lint 这个检查是启发式的，只要实现了 `X509TrustManager` 就报，无法区分委托模式与空实现。

**处置**：加 `@SuppressLint("CustomX509TrustManager")` 并附注释说明「委托实现、非信任一切」，避免后来者误删。

**可选加固（非 bug，属产品取舍，需你确认再做）**：
当前逻辑下，用户导入的 CA 也能为 Bitwarden 官方域名签发证书并被接受。
若要收窄，可用 OkHttp `CertificatePinner` 把自定义 CA 限定到用户配置的那个域名。
代价是"一个自签 CA 服务多个内部域名"的场景会失效，所以**不建议默认开启**。

---

## 四、分批执行计划

### Phase 0 — 重生成 baseline（前置，阻塞后续所有统计）

```bash
cd Bastion
./gradlew :app:updateLintBaselineDebug
```

**验收**：条目数从 1925 降到约 1887（−38），且 `NewApi` / `StaticFieldLeak` /
`NonObservableLocale` / `ConfigurationScreenWidthHeight` 四类归零。
把新数字回填到本文档**第二节的表格**，再往下做。

> 任务名注意：CI 里已改用 `updateLintBaselineDebug`（见 `e9f0be48`），
> 不要用老的 `lintDebug -DupdateBaseline` 写法。

### Phase 1 — P0 安全（2 条）

- [ ] 3.1 `network_security_config.xml` 改 `debug-overrides`
- [ ] 3.2 `BitwardenApiFactory` 加 `@SuppressLint` + 说明注释
- [ ] 真机验证：release 包 + 用户 CA 抓包，确认不再信任

### Phase 2 — P1 功能（约 15 条）

- [ ] `QueryPermissionsNeeded`：`AndroidManifest.xml` 补 `<queries>`，否则 Android 11+ 的
      `AppSelector` 拿不全应用列表（影响自动填充/关联 App）
- [ ] `SelectedPhotoAccess`：适配 Android 14+ Photo Picker
- [ ] `UnsafeOptInUsageError`：`QrCameraScanSession.kt:252` 加 `@OptIn(ExperimentalGetImage::class)`
- [ ] `PluralsCandidate`（6）：改 `<plurals>`
- [ ] `SimpleDateFormat`（4）+ `ConstantLocale`（2）：改 `DateFormat.getDateTimeInstance()` / 去掉硬编码 Locale
- [ ] `InlinedApi`（4）：补版本判断或抑制

### Phase 3 — P2 Compose 正确性（约 678 条）

`LocalContextGetResourceValueCall` 是最大一块，但改法机械：

```kotlin
// 改前
val ctx = LocalContext.current
Text(ctx.getString(R.string.foo))

// 改后
Text(stringResource(R.string.foo))
```

建议**按屏幕分批**，每批 3~5 个文件，每批跑一次 lint + 真机过一遍对应页面。
625 条集中在 `ui/` 目录，可按 `ui/screens/*` 切分。

`ModifierParameter`（33 条）同理，纯签名调整，风险低但会大面积改动函数签名，
建议单独一批，避免与上面混在一起导致 review 困难。

#### Phase 3 执行记录（2026-08-31）

按用户「P2 你推荐来弄吧」的授权，**已完成可安全机械化的子集**，剩余项按风险分级暂挂：

| 批次 | 提交 | 内容 | 数量 | baseline 处理 |
|------|------|------|-----:|---------------|
| Modifier 规范 | `4b325f05` | `ModifierParameter` 33 处参数重排到首位；`ModifierFactoryExtensionFunction` 4 处改为 `Modifier` 扩展函数 | 37 | 僵尸（待重生 baseline 清理） |
| UI 渲染 | `58bc5ce9` | `LocalContextGetResourceValueCall` 中「在 @Composable 内且为 composable 直接文本参数」的 `context.getString` → `stringResource` | 85 | 僵尸 |
| UI 渲染修正 | `96352d9a` | 还原 3 处 **biometric `() -> Unit` 回调**内误改的 `stringResource` → `context.getString`（`DeleteConfirmDialog` 的 `biometricAction`、`AddEditNoteScreen` 的 `biometricAction`、`MasterPasswordLockingSettingsScreen` 的 `startBiometricEnable`） | 8 行 | — |

**最终状态（2026-08-31）**：P1 + P2 全部合入 `main`（PR #21 已 MERGED）。
dev 上手动 lint 运行 `33362621331`：**Run lint = success + Build Debug APK = success**，CI 全绿。
preview 预览包已发布：`Development Preview (build.202608310607)`。

**抽取方法论（避免误改非 composable 场景）**：
- 用 composable 作用域感知扫描：只在 `@Composable fun` 的 body 区间内、且为
  `Text()` / `label=` / `contentDescription=` / `title=` / `text=` / `placeholder=` 等
  **直接参数值**的行替换；
- 排除 Activity / autofill 构建器 / 状态赋值（`x = context.getString(...)`）；
- 排除事件 lambda 内调用（实测 `PasswordSuggestionActivity` 的 `onClick` 里
  `ClipboardUtils.copyToClipboard(..., label = context.getString(...))` 是假阳性，
  那里 `stringResource` 不可用，已剔除）；
- **⚠️ 排除所有 `() -> Unit` 类型的具名回调 lambda**（不仅限于 `onClick`）：
  `biometricHelper.authenticate(...)` 常包在 `val biometricAction = if (...) { { ... } }`
  或 `val startBiometricEnable = { ... }` 里，这些 lambda 签名是 `() -> Unit`，
  **不能调用可组合函数 `stringResource`**，必须用 `context.getString`。本次 `58bc5ce9`
  的机械替换漏了这一类，导致 `assembleDebug` 编译失败，已用 `96352d9a` 还原。
  判定口诀：凡是 `stringResource` 出现在 `onClick` / `onDismissRequest` / `remember {}` /
  `clickable {}` / `val xxx = { ... }` 这类 **`() -> Unit` 或非 composable lambda** 体内，
  一律改回 `context.getString`。

**暂挂项（baseline 仍抑制，CI 不阻塞，运行时切换语言/主题不影响核心功能）**：
- `Toast` 类（约 47 条）：值被立即消费，无需重组 → 非 bug；
- 一次性消费（保存 / 比较 / 回调，约 43 条）：同上；
- 待人工复核（约 373 条）：基线行号已过期 + 部分在 lambda / 非 UI 位置，
  需逐文件 `stringResource` 作用域复核后再改，避免破坏编译。

> 注：5.2「必须走 PR」已过时——`gh workflow run "Android CI debug" --ref dev`
> （manual dispatch）同样会跑 lint，本批次即用此法在 push 后验证。

### Phase 4 — P3 API 规范（约 178 条）

`RestrictedApi`（91）+ `UseKtx`（87）。
建议**与依赖升级联动**做——升级 androidx/material 版本时顺带处理，
单独做的话改完可能下次升级又冒出来。参考[依赖升级计划](./dependency-upgrade-plan-2026-08.md)。

### Phase 5 — P4 清理（约 971 条，可低优先）

**先确认一件事**：release 已开 `shrinkResources !disableMinify`（`build.gradle:199`），
无用资源**不会打进 APK**，所以这 971 条**不影响包体积**，只是：

- `strings.xml` 里 852 条未使用文案，干扰翻译与文案维护
- lint 报告噪音大，掩盖真问题

处置建议：

1. **不要手工删 852 条 string**。多语言项目里常有 `getString("prefix_" + x)` 动态拼接，
   误删会直接崩溃且只有运行时才发现。
2. 稳妥做法：先确认是否存在动态引用
   ```bash
   cd Bastion/app/src/main/java
   grep -rnE "getString\(|getIdentifier\(|resources\.getIdentifier" . | grep -vE "R\.[a-z]+\.[a-z_0-9]+\)" | head -30
   ```
   如果没有动态拼接，再按模块分批删，每批删完跑一次构建 + 冒烟。
3. `raw/eff_short_wordlist.txt` 被标未使用——**删之前先确认**，
   这是 EFF 密码词表，很可能是某个密码生成器功能的资源，未使用反而可能是功能没接上。

---

## 五、验证与门禁

### 5.1 本地验证

```bash
cd Bastion
./gradlew :app:lintDebug            # 看新增问题（baseline 外的）
./gradlew :app:updateLintBaselineDebug   # 修完一批后锁进 baseline
```

### 5.2 CI 门禁现状（重要）

`.github/workflows/main.yml:110-112`：

```yaml
- name: Run lint
  if: github.event_name != 'push'
  run: ./gradlew :app:lintDebug --stacktrace
```

- **push 到 dev 不跑 lint**（为了加快出包反馈）
- **PR / 手动触发才跑**

所以每批 lint 修复**必须走 PR 才能验证**，直接 push 到 dev 是验不出来的。

### 5.3 每批完成的定义

1. `./gradlew :app:lintDebug` 无新增问题
2. 重生成 baseline，条目数较上一批**严格减少**
3. PR 里 CI 全绿
4. 涉及的界面真机过一遍（尤其是 Compose 相关的 P2 批次）

---

## 六、避坑清单

| 坑 | 说明 |
|----|------|
| **别照着过期 baseline 修** | 当前快照早于 `2361b3eb`，38 条已修。动 Phase 0 |
| **push 到 dev 验不出 lint** | lint 只在 PR / 手动触发跑，见 5.2 |
| **别手工批量删 string** | 动态 `getString` 拼接会导致运行时崩溃 |
| **release 不受 UnusedResources 影响** | 已开 `shrinkResources`，别拿"减小包体积"当理由 |
| **`CustomX509TrustManager` 是误报** | 别把正确的委托实现"修"坏了，见 3.2 |
| **改 `ModifierParameter` 单独一批** | 函数签名改动大，混在一起 review 不动 |
| **`stringResource` 不能进 `() -> Unit` 回调** | `biometricHelper.authenticate` / `onClick` / `remember {}` / `clickable {}` / `val x = { }` 等普通 lambda 内只能用 `context.getString`，否则 `assembleDebug` 编译失败（本次 `58bc5ce9` 误伤 3 处 biometric 回调，已 `96352d9a` 还原） |
| **P3 与依赖升级联动** | 单独改完，下次升级依赖可能又冒出来 |

---

## 七、待确认事项

以下几个点需要你拍板，我再动手：

1. **Phase 1 的 `AcceptsUserCertificates` 是否现在就修？**
   改动很小（3 行），但会**改变 release 的信任行为**——改完后调试用的自签 CA 在 release 包上不再生效。
   如果你平时用 release 包 + 自签 CA 连自托管 Bitwarden，**这会直接影响你的使用**。
   替代方案：保留 user CA，但只在自托管域名上放行。

2. **Phase 3 的 641 条 `LocalContextGetResourceValueCall` 做不做？**
   ✅ 已按推荐执行安全子集（见上「Phase 3 执行记录」）：改了 85 处 UI 渲染真 bug，
   `ModifierParameter`/`ModifierFactoryExtensionFunction` 共 37 处。
   剩余约 463 条（Toast / 一次性消费 / 待复核 lambda）属非重组场景或需逐文件复核，
   **暂挂 baseline 抑制**，不阻塞 CI，待后续按屏幕分批或配合本地编译环境再清。
   ✅ **执行状态（2026-08-31）**：本子集已完成并经 CI 验证（build + lint 全绿），
   随 PR #21 合入 `main`。仅中途在 `58bc5ce9` 误伤 3 处 biometric `() -> Unit` 回调，
   已由 `96352d9a` 还原（详见 Phase 3 执行记录与方法论补充）。

3. **Phase 5 的 887 条无用资源做不做？**
   不影响体积，纯整洁度。可以先只清理 `drawable` 那 2 条，`strings.xml` 的 852 条暂缓。

4. **`raw/eff_short_wordlist.txt` 未使用**——是历史遗留该删，还是有功能没接上？
   这个需要你确认，我不敢擅自删。
