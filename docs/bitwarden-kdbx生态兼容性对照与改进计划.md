# Bitwarden / KDBX 生态兼容性对照与改进计划

> 状态：**T1 / T2 / T3 / T5 / T7 已落地 `dev` 分支（2026-08-22）且 CI 全绿**（Android CI debug run 32560145716，627 单测通过，预览 APK 已发布）；**T4 待单独设计（高风险，见文末）**
> 创建：2026-08-22
> 更新：2026-08-22（补充落地后的 CI 修复实证与遗留观察项，见第七、九节）
> 目的：对齐生态标准（Bitwarden 官方导出格式 / KeePassDX / Keepass2Android / KeePassXC KPH），梳理 bastion 兼容性现状与改进候选，供后续开发接力。

---

## 一、结论摘要

- **KDBX 兼容性优于 Bitwarden**：passkey（KPH 字段逐字对齐）、TOTP（otp + TOTP Seed 双格式）、WiFi（kp2a WLan 模板对齐）均参考生态实现；主要缺口是"外部编辑条目的重建保真"。
- **Bitwarden 核心字段对齐官方格式**（type 1-4、login.totp、fields type 0/1/2）；缺口是**多网址拼接畸形 URI**、**WiFi/SSO 等扩展元数据无通道静默丢失**、linked(3) 字段降级。
- 评级：两侧均"中"。日常登录条目（账号/密码/网址/备注/TOTP）往返可靠。

---

## 二、Bitwarden 官方标准 vs bastion

官方 JSON 导出/导入格式（bitwarden.com/help/condition-bitwarden-import）：

```
items[]: {
  type: 1=login / 2=secureNote / 3=card / 4=identity
  name, notes, favorite, folderId, reprompt, passwordHistory[]
  fields[]: { name, value, type: 0=text / 1=hidden / 2=boolean / 3=linked }
  login: { uris: [{ match, uri }], username, password, totp }
  card: { cardholderName, brand, number, expMonth, expYear, code }
  identity: { title, firstName, ... }
  secureNote: { type }
}
```

| 官方字段 | bastion 现状 | 对齐度 |
|---|---|---|
| type 1-4 条目类型 | REST/JSON 双向映射（login/note/card/identity） | ✅ |
| login.username/password/notes | 双向加密映射 | ✅ |
| login.totp (otpauth URI) | 双向（`TotpMapper`，SHA 算法/digits/period 经 notes `[Bastion TOTP Config]` 块保留） | ✅ |
| fields type 0/1/2 | 完全对应（TEXT/HIDDEN/BOOLEAN） | ✅ |
| fields type 3 (linked) | **读回降级为 TEXT**（CipherSyncProcessor 约 1790 行） | ⚠️ 简化 |
| `uris[]` 数组（多 URI + 每项 match） | 下载多 uri 逗号拼接进 `PasswordEntry.website`（单列）；导出把整串当单个 `BwUri`（BitwardenJsonExport 约 255 行）；match 丢弃 | ⚠️ **畸形 URI** |
| passwordHistory / reprompt | 不往返 | ⚠️ 未做 |
| 附件 / Sends | 无映射通道 | ⚠️ 未做 |
| favorite / deleted / archived / folder | 双向 | ✅ |
| 图标 | 不同步 | ⚠️ |

**已知静默丢失**（上传/导出无通道）：
1. WiFi 扩展元数据（`wifiMetadata` 等）——REST 同步与 JSON 导出均不写入
2. SSO 配置、条形码(BARCODE) 扩展字段
3. 证件照片 `imagePaths`
4. JSON 导出不含 SSH 私钥 / app 绑定 / passkey 绑定（REST 通道才有）
5. BILLING_ADDRESS / PAYMENT_ACCOUNT 类型 REST 上传直接 `Error("Unsupported item type")`

**冲突处理**：本地修改 + revision 变化 → 冲突备份 → UI 手动二选一（无自动合并）；`BitwardenMapper.merge(LOCAL/REMOTE/LATEST)` 为死代码。冲突备份不含密码明文。

---

## 三、KDBX 生态标准 vs bastion（KeePassDX / kp2a / KeePassXC）

### 生态基准（Web 调研实证）
- **KeePassDX**（Kunzisoft，KeePassDroid fork，非 kotpass）：`.kdb` + `.kdbx` v1-v4、AES/Twofish/ChaCha20/Argon2、OTP 走 **`otp` 字段**、Passkeys（KPH 格式）、动态模板、条目历史。
- **Keepass2Android** WLan 模板（`AddTemplateEntries.cs` 实证）：**SSID 自定义字段（Inline）+ Password 标准字段 + IRCommunication 图标**，模板 UUID `46B56A7E-9040-7545-B646-E8DC-488A5FA2`。
- **KeePassXC/KeePassPasskey KPH passkey 标准字段**：`KPEX_PASSKEY_CREDENTIAL_ID`、`KPEX_PASSKEY_PRIVATE_KEY_PEM`（PKCS#8 PEM）、`KPEX_PASSKEY_RELYING_PARTY`、`KPEX_PASSKEY_USERNAME`、`KPEX_PASSKEY_USER_HANDLE`、`KPEX_PASSKEY_FLAG_BE`、`KPEX_PASSKEY_FLAG_BS`；算法编码在 PKCS#8 OID，无单独字段。

### 对照

| 生态约定 | bastion 现状 | 对齐度 |
|---|---|---|
| TOTP：`otp`(otpauth URI) + `TOTP Seed`/`TOTP Settings` | **双写**（`KeePassTotpCodec`）：`otp` + `TOTP Seed` + Settings/Period/Digits/Algorithm | ✅ 对齐（小瑕疵：Settings 写键值式 `period=30;digits=6`，KeePassXC 惯用位置式 `30;6`，靠 TOTP Seed 兜底） |
| passkey KPH 字段 | `KeePassDxPasskeyCodec` 7 个字段名与标准**逐字一致**，私钥 PKCS#8 PEM | ✅✅ 完全对齐（KeePassXC/KeePassDX/KeePassPasskey 互读） |
| WiFi（kp2a WLan 模板） | `SSID` + `BastionLoginType=WIFI` + `BastionWifiData`(JSON) + IRCommunication 图标 | ✅ 基本对齐 |
| 标准字段 Title/UserName/Password/URL/Notes | 双向投影；读时别名兼容（Title/Name、User/Login、URL/Website 等）；密码兜底从 UNKNOWN 保护字段提取 | ✅ |
| Bastion 专有字段 | 投影为同名字段（Email/Phone/地址/银行卡/SSH/SSO/WiFi），敏感项 Encrypted 保护；`BastionItemData`(Encrypted JSON) | ✅ |
| `.kdb` (KeePass 1.x) | 不支持（`KeePassFormatInspector`） | ⚠️ 差异（收益低） |
| 条目历史 / 多 URL / 图标 UUID / tags | **仅"匹配条目 patch"时保留**；未匹配条目重建时**全部丢失** | ⚠️ 有损 |
| 未知字段（第三方写的） | 匹配条目时保留（patch 只移 overlay 字段）；`_etm_` 插件字段保留但 UI 不可见 | ⚠️ 重建路径丢失 |
| KDBX3/4 + AES/ChaCha20/Argon2/Twofish | 原版本保持 | ✅ |

---

## 四、改进候选清单（优先级排序）

| 优先级 | 改进项 | 对齐对象 | 收益 | 规模 |
|---|---|---|---|---|
| **T1 高** | Bitwarden 多网址**对称拆分**：导出/上传时按逗号拆成多个 `BwUri`（读取侧已有 `parseLoginUris` 拆分，仅需导出侧对称） | 官方 `uris[]` | 消除畸形 URI，第三方往返保真 | 小（无迁移） |
| **T2 高** | Bitwarden WiFi/SSO 元数据走 custom fields 通道（`bastion_wifi_*` 等隐藏字段） | 官方 fields 机制 | 消除静默丢数据 | 中 |
| **T3 中** | Bitwarden linked(3) 字段保留类型 | 官方 fields type 3 | 保真度 | 小 |
| **T4 中** | KDBX 未匹配条目重建时保留外来字段/历史（改进重建策略，防"洗白"） | KeePassDX/KeePassXC | 外部编辑保真 | 中-大 |
| **T5 低** | Bitwarden JSON 导出补 SSH/app 绑定/passkey 绑定 | 官方格式 | 导出完整性 | 中 |
| **T6 低** | KDBX `.kdb` 支持 | KeePassDX | 老用户 | 大（收益低，不建议） |
| **T7 低** | TOTP Settings 位置式 `30;6` 双写 | KeePassXC 惯用 | 消除兜底 | 小 |

---

## 五、T1 详细计划（建议先做，低风险高收益）

**目标**：Bitwarden 导出/上传把 `PasswordEntry.website`（逗号分隔多 URL）拆分为多个独立 `BwUri`，与读取侧 `parseLoginUris` 对称。

**现状证据**：
- 本地模型：`PasswordEntry.website: String`（单列，Room 实体，不动数据库）
- 读取：`CipherSyncProcessor.parseLoginUris`（约 1710 行）把多 uri 拆回逗号串 + `androidapp://` → appPackageName
- 导出：`BitwardenJsonExport.mapPasswordEntry`（约 255 行）`listOf(BwUri(uri = entry.website))` 整串单 URI ← **问题点**

**改动点（文件级）**：
1. `bitwarden/export/BitwardenJsonExport.kt`：`mapPasswordEntry` 中把 `entry.website` 按 `,\s*` 拆分为多个 `BwUri`；每项 `match = null`（官方默认）；`androidapp://` 前缀项保持原样（官方 URI 语义）。
2. `bitwarden/service/CipherUploadProcessor.kt`：上传 `login.uris` 构建处做同样的拆分（与读取对称）。
3. 补守卫测试：仿 `BitwardenJsonImport` 现有测试，新增"多网址导出 → 多 BwUri"与"导入多 uri → 逗号串"往返断言。

**风险**：无数据库迁移；旧数据（已逗号拼接的 website）拆分规则与读取侧 `joinToString(", ")` 严格对称，往返不丢。需注意 website 内本身含逗号的 URL（罕见，URL 规范不允许裸逗号，可接受）。

**验证**：真机导出 JSON → Bitwarden 官方导入 → 检查条目 URI 完整；CI 单测。

---

## 六、T2 详细计划（建议 T1 后做）

**目标**：WiFi / SSO / BARCODE 扩展元数据在 Bitwarden 侧走 custom fields（`bastion_*` 隐藏字段），消除静默丢失。

**改动点（文件级，需先细化）**：
1. 上传侧：`CipherUploadProcessor` 的 `buildPasswordCustomFields` 类函数，补充写入 `bastion_wifi_data`(Hidden) / `bastion_sso_config`(Hidden) 等字段（值 = JSON 序列化，与 KDBX 侧 `BastionWifiData` 同构）。
2. 读取侧：`CipherSyncProcessor` 解析 custom fields 时识别 `bastion_wifi_data` 等并还原到本地模型。
3. 排除规则：`BitwardenSyncService`（约 1949 行）已有 `bastion_*` 排除列表，需把新字段名加入（避免服务端重复/冲突）。
4. 空值清理：旧条目（升级前已同步）缺失这些字段时保持本地数据不变（向前兼容）。

**风险**：custom fields 数量/长度限制（Bitwarden 服务端限制需核实）；字段名与 `mergeCipherFields` 基线合并逻辑的交互。

---

## 七、决策记录与实施状态

| 项 | 状态 | 说明 |
|---|---|---|
| **T1** Bitwarden 多网址对称拆分 | ✅ 已落地 dev | `BitwardenJsonExport.mapPasswordEntry` 用 `PasswordWebsiteCodec.parse` 拆多 BwUri；已补 `BitwardenJsonExportTest` 守卫测试（多网址/单网址/空/中文逗号） |
| **T2** Bitwarden WiFi/SSO 元数据通道 | ✅ 已落地 dev | REST 上传 `buildEncryptedPasswordCustomFields` 补 `bastion_login_type`/`bastion_wifi_data`/`bastion_sso_provider`；两套读回（`CipherSyncProcessor.syncPasswordCipher` + `BitwardenSyncService.convert/update`）还原 `wifiMetadata`/`ssoProvider`/`loginType(WIFI/SSO)` |
| **T3** Bitwarden linked(3) 字段保留类型 | ✅ 已落地 dev | `SecureCustomFieldType` 加 `LINKED`；导出映射 `TEXT/HIDDEN/BOOLEAN/LINKED → 0/1/2/3`；读回 `toSecureCustomField` 的 `3 → LINKED` 对称 |
| **T5** JSON 导出补绑定 | ✅ 已落地 dev | `BitwardenJsonExport.buildPasswordCustomFields` 补隐藏字段：`bastion_app_package`/`bastion_login_type`/`bastion_wifi_data`(WIFI)/`bastion_sso_provider`(SSO)/`bastion_passkey_bindings`/`bastion_ssh_*`(解码 `SshKeyDataCodec`) |
| **T7** KeePassXC TOTP 位置式 | ✅ 已落地 dev | `KeePassTotpCodec.toKeePassFields` 的 `TOTP Settings` 改为位置式 `period;digits;algorithm`；独立 `Period/Digits/Algorithm` 字段保留兜底 |
| **T4** KDBX 未匹配条目重建保真 | ⏳ 待单独设计 | 见第八章，高风险大改动，本轮未实现代码 |
| **T6** `.kdb` 支持 | ❌ 已否 | 收益低 |
| Bitwarden 图标同步 | ❌ 已否 | 成本高收益低 |

### 落地后的 CI 修复实证（2026-08-22）

T 项首次推送后 CI 门禁暴露 3 个问题，均已修复并随 `dev` 通过全部检查（run 32560145716）。后续若改动相关代码，注意这几类"实现/测试不同步"错误：

| 提交 | 问题 | 根因与修复 |
|---|---|---|
| `244af59` | `compileDebugKotlin` 失败：`when` 不穷尽 | T3 给 `SecureCustomFieldType` 加 `LINKED` 后，`CipherUploadProcessor.buildEncryptedFields`（约 1449 行）的 `when` 未处理；补 `LINKED -> 3`（与下载/导出侧映射一致） |
| `a905479` | 单测结果 XML 缺失（基线闸门报错） | 同批引入的 `BitwardenJsonExportTest` 构造 `PasswordEntry` 漏传 `website/username/password` 必填参数，测试代码编译失败；且 `Run unit tests` 步骤 `continue-on-error` 吞掉失败信号，只留下游基线闸门暴露。**教训：单测步骤无产物 ≠ 测试通过，看基线闸门的 XML 缺失/失败名单** |
| `7042f9e` | 2 个 `KeePassTotpCodecTest` 断言失败 | T7 实现有意改位置式 settings（`45;8;SHA256` / `30;6;HOTP;12`），同提交的守卫测试仍断言旧键值式；按 B.2.1 哲学重写断言对齐实现 |

排错通道提示：本仓库 CI 日志经 `results-receiver.actions.githubusercontent.com` 下发，协作环境 DNS 会劫持到保留段导致 `gh run view --log` 拿不到数据；基线闸门步骤已把失败名单打进 checks API annotations（`gh api repos/<owner>/<repo>/check-runs/<id>/annotations`），这是最稳定的排错入口。

## 八、T4 设计要点（待接力，本轮未实现）

**问题**：KeePass 条目在 bastion 外部被编辑后，若未被"匹配条目 patch"路径命中（如 UUID/标题变化），bastion 会以本地模型**重建**条目，导致外来字段（第三方插件字段、自定义图标 UUID、条目历史 `History`）被"洗白"丢弃。

**设计方向（供后续 agent 接力）**：
1. 重建前对远端条目做**全字段快照**（`KeePassEntry` 的 `customFields` 中非 bastion 前缀的、`binaryPool` 引用、`History` 列表），存入变更集或临时补丁。
2. 重建后以**叠加（overlay）**方式回填外来字段，仅覆盖 bastion 管理的标准字段，保留其余。
3. `KeePassChangeSetApplier` / `KeePassRebase` 现有 patch 逻辑已能保留匹配条目的外来字段，可抽象出"保留外来字段"的通用策略复用到重建路径。
4. 需配套守卫测试：构造带第三方字段 + 历史的 KeePass 条目 → 走重建路径 → 断言外来字段/历史不丢。

**风险提示**：KDBX 重建涉及 `KeePassKdbxService`（数千行）与 `mdbx` 本地库，改动需真机回归 + 多轮 CI；建议单独分支、单独 PR，避免与 Bitwarden 兼容性改动耦合。

---

## 九、遗留观察项（2026-08-22 补充，不阻塞，供后续加固）

**`KeePassTotpCodec.parseSettings` 对位置式 HOTP settings 的单独解析有歧义**：

- 现象：`toKeePassFields` 输出 `"30;6;HOTP;12"` 后，若**仅凭该 settings 字符串**调用 `parse`（无独立字段），"30" 与默认值 30 无法区分 → "6" 被误读为 period、"12" 被误读为 digits，counter 丢失。
- 实际影响：**无**。bastion 写条目时同时落独立字段 `TOTP Period`/`TOTP Digits`/`HOTP Counter`，`parseSettings` 对独立字段优先级更高（`fields.period/digits/counter` 覆盖），完整 roundtrip 正确。
- 加固方向（如需）：位置式解析时，`HOTP` token 之后的数字视为 counter；或在 TOTP 默认值场景下改用键值式双写。属行为改动，需单独验证。
