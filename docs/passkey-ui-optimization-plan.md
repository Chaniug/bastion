# Passkey（通行密钥）UI 与文案优化计划

> 状态：计划草案（待确认）
> 关联截图：用户在荣耀 Android 17 上登录 GitHub 时弹出的 Passkey 选择/确认页
> 分支策略：本计划全部改动在 `dev` 实施，验证后由用户下令合入 `main`

---

## 0. 范围澄清（最重要）

用户截图里的弹窗是 **Android 系统 Credential Manager 渲染的系统 UI**，不是 Bastion 的 Compose 页面。在该流程中，Bastion 作为「通行密钥提供方」被系统调用，**只能向系统注入少量数据字段**（图标、RP 名称、账号字段、manifest 标签），而**标题文案、副标题句式、按钮文案、双行 RP 呈现、账号截断策略都是 Android 系统决定的**，应用层改不了。

因此优化要分成两层，预期收益差别很大：

| 层 | 可控性 | 收益 | 说明 |
|---|---|---|---|
| **A. 系统弹窗层**（截图那个） | Bastion 仅能注入字段 | 有限 | 图标、RP 名、账号字段、manifest label 可改；核心文案/版式改不了 |
| **B. 应用内 Passkey 页** | Bastion 完全可控 | 高（ROI 最高） | 鉴权页、创建页、列表、详情、设置，纯 Compose，可大改 |

> 建议：截图里「文案/UI 不好看」的体感，绝大多数来自系统层、Bastion 改不动；要真正改善体验，应把重心放在 **B 层（应用内页）**，并在 **A 层做力所能及的字段/图标优化 + 把系统层问题反馈给 Google**。

---

## 1. 系统弹窗层（针对截图，Bastion 能影响的字段）

### 1.1 可改项（注入字段）

| 字段 | 当前代码 | 现显示 | 可优化方向 |
|---|---|---|---|
| 提供者图标 | `BastionCredentialProviderService.kt:318` `setIcon(ic_passkey)` | 通用指纹图标 | 换成 **Bastion 品牌盾牌图标**（圆形/方形品牌色），让用户一眼认出「这是我的密码管理器」 |
| 条目展示名 | `:317` `setDisplayName(passkey.rpName)` | "GitHub" | 可对 RP 名做友好化映射（如保留官方品牌名，避免被截断） |
| 创建条目标题 | `:212` `CreateEntry.Builder("Bastion - $rpName", …)` | "Bastion - GitHub" | 措辞可微调，但系统会二次排版，空间有限 |
| 创建条目描述 | `:215` `setDescription("为 $userName 创建通行密钥")` | "为 valkjin 创建通行密钥" | 可接受；可加品牌前缀 |
| 账号 userName | `:191-192` 解析 `userJson.name` / `displayName` | 小写 handle "valkjin" → 系统截断成 "@v…" | 见 1.3 |
| manifest 标签/图标 | `AndroidManifest.xml` `<service android:label android:icon>` | "Bastion" | 检查是否精炼、图标是否与品牌一致 |

### 1.2 改不了的项（系统 Credential Manager 控制，需反馈 Google）

以下属于 `androidx.credentials` / 系统 UI 行为，应用层无法修改，整理成 issue 反馈给 Android Issue Tracker：

- 顶部标题「使用通行密钥登录」读起来拗口
- 副标题「正在通过 Bastion **中的**通行密钥登录 GitHub」句式啰嗦，"中的"属冗余
- 「服务」卡片同时显示 `GitHub` + `github.com` 双行重复
- 账号下方 username 被截断成 `@v…`（单行裁切策略）
- 底部提示「…才会把通行密钥发送给**该应用**」指代不清
- 「登录」主按钮文案

### 1.3 账号截断的有限缓解

GitHub 注册时下发 `name="valkjin"`（小写 handle）+ `displayName="Valkjin"`。系统选择器中账号行通常显示 `displayName`，但部分系统版本会优先展示 `name` 导致小写 handle 截断感。

可在 provider 返回 `PublicKeyCredential` 时调整注入的 `displayName` 优先级（让友好名优先），但**不改变 RP 下发的原始数据**，仅影响展示层。改动集中在 `BastionCredentialProviderService.kt:191-192` 的解析逻辑（displayName 为空时回退策略、长 handle 折行/省略策略）。这是尽力而为，不能 100% 解决系统截断。

---

## 2. 应用内 Passkey 页（Bastion 完全可控，ROI 最高）

### 2.1 鉴权确认页 — `passkey/PasskeyAuthActivity.kt`（983 行）

这是 Bastion 自己弹出的确认页（系统弹窗之外的应用内页）。

- **标题**：`:869` `passkey_auth_title` = "使用通行密钥登录" → 用户觉得拗口，建议改为更口语的「确认使用通行密钥登录？」或「用通行密钥登录 %1$s？」
- **副标题**：`:876` `passkey_auth_message` = "正在通过 Bastion 中的通行密钥登录 %1$s" → 去掉"中的"冗余，改为「Bastion 将使用你的通行密钥登录 %1$s」
- **账号行**：`:903-908` caption="账号"、title=`displayTitle()`、subtitle=`userName`。当前 subtitle 直接显示 RP 下发的 userName，可加友好化处理
- **服务行**：`:894-898` caption="服务"、title=rpName、subtitle=rpId。rpId（如 github.com）作为副标题偏技术，可改为更易懂的呈现
- **安全提示**：`:938` `passkey_auth_security_note` = "需通过指纹、面容或主密码验证后，才会把通行密钥发送给该应用。" → "该应用"指代不清，改为「…发送给 %1$s（GitHub）」或「…才会用于登录」
- **按钮**：`:799` 登录 / `:811` 取消 / `:819` 使用主密码 → 文案可接受，重点在排版
- **组件 `PasskeyAuthInfoRow`**：`:948-983` 当前图标 + caption + title + subtitle 四段。可增强：图标改用语义化图标（服务=公众图标、账号=人像、次数=历史）、增加行间距与分隔、title 用 `titleSmall`/subtitle 用 `bodySmall` 已合理，主要是让三行信息层级更清晰

### 2.2 创建流程页 — `passkey/PasskeyCreateActivity.kt`（1616 行）

- `passkey_create_title` = "创建通行密钥" → 可改为「为 %1$s 创建通行密钥」
- `passkey_create_message` = "为 %1$s 创建通行密钥，无需密码即可登录" → 已较清晰，可补一句安全说明（"仅此设备可登录，无需记住密码"）
- 创建确认按钮 `passkey_create_confirm` = "创建通行密钥" → 可接受

### 2.3 列表页 — `ui/screens/PasskeyListScreen.kt`（2464 行）

- 卡片视觉应与**已优化过的密码列表**风格对齐（统一圆角、阴影、图标、标题/副标题层级）
- 关键 Composable 入口：`PasskeyListScreen` (`:135`)、列表项卡片约在 `:1701`/`:1760`/`:1826` 一带
- **空状态**：`passkey_empty_title`="暂无通行密钥" + `passkey_empty_message`（文案已不错，重点在插画/图标 + 引导按钮样式）
- **搜索空**：`passkey_no_search_results` / `passkey_search_empty`
- **失败态**：`:394` `sync_status_failed_short` 的 Toast 提示，可改为更友好的 Snackbar + 重试入口
- 列表项建议显示：RP 图标/品牌字、账号名、最近使用时间、同步状态徽标

### 2.4 详情页 — `ui/screens/PasskeyDetailScreen.kt`（454 行）+ `ui/passkey/PasskeyDetailPanes.kt`（573 行）

- 已有分组：账号信息 / 安全与存储 / 使用记录 / 技术信息（`strings.xml:3512-3515`）
- 优化：分组标题层级、行标签（caption）与值（value）的对齐、增加 RP 信任域说明、同步状态用彩色徽标而非纯文本
- `:286` 备注编辑、`passkey_remark_hint` 已清晰

### 2.5 设置页 — `ui/screens/PasskeySettingsScreen.kt`（660 行）

- `passkey_settings_subtitle`="无需密码即可登录应用和网站"
- 特性卡片：`passkey_feature_security/biometric/sync/phishing`（`:1409-1416`）文案已较规范
- 优化：卡片图标语义化、间距统一；校验模式（影子/严格）说明 `:1421-1424` 可加「推荐默认」标识

### 2.6 文案一致性 Bug（必须修）

`strings.xml` 中存在「通行秘钥」（错字，应为「通行密钥」）混用，需全局统一：

- `:2117` `passkey_bound_label` = "已绑定通行**秘**钥" → 改「密钥」
- `:3223` `icon_settings_passkey_page_title` = "通行**秘**钥页面" → 改「密钥」
- 全量 grep 确认无其他「通行秘钥」残留

---

## 3. 实施顺序（建议分 4 个 Phase）

> 按「低风险高感知优先」排序，每个 Phase 独立可合、可回滚。

**Phase 1 — 文案打磨（低风险、高感知）**
- 文件：`strings.xml` + `PasskeyAuthActivity.kt` 少量行
- 内容：2.1/2.2 文案重写、2.6 错别字统一
- 风险：极低，纯字符串/少数引用

**Phase 2 — 品牌图标与 manifest（中等）**
- 文件：`res/drawable/ic_passkey.xml`（替换为品牌盾牌）、`AndroidManifest.xml` provider 标签
- 内容：1.1 图标与标签优化
- 风险：低，需确认图标在深浅色主题下均清晰

**Phase 3 — 应用内 UI 视觉对齐（较高）**
- 文件：`PasskeyListScreen.kt` / `PasskeyDetailScreen.kt` / `PasskeyDetailPanes.kt` / `PasskeySettingsScreen.kt` / `PasskeyAuthActivity.kt`（`PasskeyAuthInfoRow`）
- 内容：2.1/2.3/2.4/2.5 卡片与列表视觉统一到已优化的密码列表风格
- 风险：中，涉及 Compose 布局改动，需真机回归

**Phase 4 — 系统弹窗可注入字段优化（有限收益）**
- 文件：`BastionCredentialProviderService.kt:191-192, 212, 215, 317-318`
- 内容：1.1/1.3 RP 名友好映射、账号 displayName 优先级、截断缓解
- 风险：低，但收益有限（系统仍主导版式）

---

## 4. 涉及文件清单

| 文件 | 行号（基于 d8d1a4a） | 改动类型 |
|---|---|---|
| `Bastion/app/src/main/res/values/strings.xml` | 870-898, 1400-1432, 2117, 3223 | 文案重写 + 错别字 |
| `passkey/BastionCredentialProviderService.kt` | 191-192, 212, 215, 317-318 | 注入字段/图标 |
| `passkey/PasskeyAuthActivity.kt` | 862, 869, 876, 894-908, 938, 948-983 | 鉴权页文案/组件 |
| `passkey/PasskeyCreateActivity.kt` | 创建页文案引用 | 创建页文案 |
| `ui/screens/PasskeyListScreen.kt` | 135, 1701, 1760, 1826, 394 | 列表卡片/空态/失败态 |
| `ui/screens/PasskeyDetailScreen.kt` | 全文件分组与行 | 详情页排版 |
| `ui/passkey/PasskeyDetailPanes.kt` | 全文件 | 详情面板 |
| `ui/screens/PasskeySettingsScreen.kt` | 特性/校验卡片 | 设置页排版 |
| `res/drawable/ic_passkey.xml` | 整文件 | 图标替换 |
| `AndroidManifest.xml` | provider service 标签 | label/icon |

> 注：行号基于当前工作副本 `d8d1a4a`（落后远程 dev 两个仅含 codeql/docs 的提交，passkey 代码不受影响）。实施前会先 `git fetch` 更新到最新 `dev` 再开工，行号以最新代码为准。

---

## 5. 真机验证（荣耀 Android 17）

- 系统弹窗：在 GitHub / 支持 Passkey 的网站触发登录，确认 Bastion 图标已替换为品牌图标、账号显示改善
- 应用内页：进入「通行密钥」列表 → 详情 → 设置，核对文案与卡片视觉
- 创建流程：在网站发起创建，核对创建页文案
- 验证同步状态徽标、空状态、失败态提示

---

## 6. 风险与回滚

- **系统层改不动**：Phase 4 收益有限属预期，不会破坏功能；若 RP 名映射导致显示异常，回退 `:317` 单行即可
- **图标替换**：需验证深浅色主题对比度，避免低对比不可见
- **Compose 布局改动**（Phase 3）：在每个 Phase 独立提交，CI（dev-only）通过后由用户真机验证再合 main
- **回滚**：每个 Phase 独立 commit，可 `git revert` 单 Phase

---

## 7. 系统层问题反馈（给 Google，不在本仓库范围）

整理截图中的系统 UI 问题（见 1.2），在 Android Issue Tracker（`androidx.credentials` 组件）提交反馈，推动官方改善系统弹窗文案与版式。本仓库无法解决这部分。

---

## 8. 待用户确认的事项

1. 是否同意「重心放在应用内页（B 层）+ 系统层只做图标/字段优化 + 反馈 Google」的总体策略？
2. 是否按 Phase 1→4 顺序实施，还是只做 Phase 1（文案）+ Phase 2（图标）这类低风险项？
3. 系统弹窗里 Bastion 作为提供方显示的「Bastion」字样/图标，是否要换品牌名或新图标？
