# 条目字段兼容性与稳定性优化计划（草案）

> 状态：**P0 / P1 / P2 已在 dev 分支实现并提交**（见下方「实施记录」）。本计划文档保留原始调研与方案，供其他 agent 接力/复核。
> 关联上下文：app 密码条目里的 `monica_app_package` / `monica_app_name` 是旧品牌 Monica Pass 重品牌为 Bastion 前的遗留应用绑定字段；用户希望 (1) 评估一次性迁移可行性，(2) 优化「应用包名 + 显示名」匹配使其兼容 Bitwarden/KeePass 且效果更好，(3) 其他字段的兼容/稳定性优化。

## 实施记录（dev 分支）
- **P0 读取端别名兼容**：`KeePassKdbxService.analyzePasswordEntry` 的应用绑定提取扩展别名——补 `monica_app_package`/`monica_app_name`、`MonicaAppPackageName`/`MonicaAppName`、`bastion_app_package`/`bastion_app_name`、`KP2A_APP`/`KP2A_APP_NAME`，并新增 `extractAndroidAppPackage()` 扫描所有字段里的 `androidapp://<pkg>` URI；`KeePassFieldRegistry.bastionPasswordFields` 加入上述键以正确分类；`CipherSyncProcessor` 导入端补读 `monica_app_package`/`monica_app_name`。
- **P1 Bitwarden 导出对称化**：`BitwardenJsonExport.mapPasswordEntry` 把 `appPackageName` 以 `androidapp://<pkg>` 形式并入 `login.uris`；`buildPasswordCustomFields` 补写 `bastion_app_name`。修复「导出到 Bitwarden 再导回丢失应用绑定」往返 bug（与 `CipherSyncProcessor.parseLoginUris` / 导入键对齐）。
- **P2 稳定性**：`PasswordEntryAppBindings.parseLinkedAppBindings` 的应用名拆分分隔符与包名统一为 `|,;`，避免多绑定时名称与包名错位；显示名兜底（PackageManager 解析已装应用名）已在 autofill 各层（`AutofillPickerActivityV2`/`AutofillSaveActivity`/`AppIconCache` 等）普遍存在，未重复实现。

> 注：一次性「改写数据库」的迁移方案未采用——改为读取端别名兼容，零数据改写、对所有存量数据与未来导入生效、且不会被同步覆盖（同步条目本地改写会被远端覆盖，收益有限）。

---

## 一、当前代码事实（调研结论）

### 1. 本地数据模型
- `PasswordEntry.appPackageName` / `appName` 为 TEXT 列，可存**多绑定**，以 `|`、`,`、`;` 分隔（`PasswordEntryAppBindings.kt` 的 `parseLinkedAppPackageNames` / `parseLinkedAppBindings`）。
- 绑定用于：自动填充按包名匹配、App 图标显示、去重身份键 `app|loginType|package|username`（`DedupPasswordIdentity.kt`）、搜索。

### 2. KeePass 侧（`KeePassKdbxService.kt` / `KeePassFieldRegistry.kt`）
- 读取包名别名：`App Package Name` / `AppPackageName` / `BastionAppPackageName` / `AndroidAppPackageName` / `PackageName`（3749-3757）。
- 读取应用名别名：`App Name` / `AppName` / `BastionAppName` / `Application` / `Application Name`（3758-3766）。
- 写入用通用键 `App Package Name` / `App Name`（2572-2573）。
- **不含** `monica_*`、`KP2A_*`（KeePass2Android）、`androidapp://` URL 识别。

### 3. Bitwarden 侧（关键兼容性问题）
- 导入（`CipherSyncProcessor`）：包名来自 `androidapp://<pkg>` URI（1729-1743）或自定义字段 `appPackageName`（294）；应用名来自 `bastion_app_name` / `appName`（296-297）。
- 导出（`BitwardenJsonExport.buildPasswordCustomFields` 307）：**只写 `bastion_app_package`**，不写 `bastion_app_name`（应用名丢失），**也不回写 `androidapp://` URI 到 `login.uris`**。
- → **往返不对称 bug**：Bastion 导出到 Bitwarden 后，再导入时导入侧读 `appPackageName`/`androidapp://`，与导出的 `bastion_app_package` 不匹配 → 应用绑定整段丢失。

### 4. 字段别名散落
别名列表散在 4+ 处（KeePassFieldRegistry、KeePassKdbxService 读取、CipherSyncProcessor 导入、BitwardenJsonExport 导出），互不统一，是兼容性 bug 的**根因**。

---

## 二、Q1：一次性迁移可行性

- **可行**，但有明显代价：对**同步条目**（Bitwarden/KeePass）本地改写后，下次同步会被远端覆盖，收益有限；且改库有风险。
- **推荐主方案：读取端别名兼容**（零数据改写、对存量旧数据 + 未来导入同时生效），一次性迁移仅作为「本地-only 条目」的可选项。
- 即：把 `monica_app_package`/`monica_app_name`（及 `MonicaAppPackageName`/`MonicaAppName`）加入读取别名层，旧 Monica 条目在每次同步/读取时自动被识别，无需搬运数据。

---

## 三、Q2：应用绑定匹配优化（Bitwarden/KeePass 兼容 + 效果更好）

1. **统一字段别名映射表（根因修复）**
   - 收敛散落别名为一张规范表（包名键集合 / 应用名键集合），导入、导出、本地匹配共用，消除漂移。
2. **Bitwarden 对称化（修复往返丢失）**
   - 导出：除 `bastion_app_package` 外，**回写 `androidapp://<pkg>` 到 `login.uris`** 且**补写 `bastion_app_name`**（应用名不再丢）。
   - 导入：优先级 `androidapp://` → `appPackageName` → `bastion_app_package`；应用名 `bastion_app_name` → `appName`。
3. **KeePass 补齐原生别名**
   - 增加 `KP2A_APP` / `KP2A_URL`（KeePass2Android）、`androidapp://` URL 字段识别、更多 Application 变体。
4. **显示名兜底（提升效果）**
   - 若 `appName` 为空但包名已知，用 `PackageManager` 解析已安装应用名填充图标/名称，提升展示与命中体验。

---

## 四、Q3：其他字段兼容 / 稳定性

1. **自定义字段全量往返**
   - 确保 KeePass/Bitwarden 同步不丢用户自由自定义字段（参考 `bitwarden-sync-monica-comparison.md` 中自定义字段保真缺陷），非保留字段一律落 `custom_fields` 表。
2. **扩展字段导入/导出对称**
   - 校验 SSH(`bastion_ssh_*`)、WiFi(`bastion_wifi_data`)、SSO(`bastion_sso_provider`)、Passkey(`bastion_passkey_bindings`) 导出键在导入侧可被还原，避免单向丢失。
3. **去重/合并稳定性**
   - `monica_*` 纳入别名层后，`dedup` 身份键自然兼容；避免一次性改写引发去重抖动（合并时取 firstNonBlank）。

---

## 五、实施阶段（建议）

- **P0（低风险，立即见效）**：统一字段别名映射表 + 读取侧补齐 `monica_*` / `KP2A_*` / `androidapp://` / `bastion_app_name` / `bastion_app_package`。立刻修复旧数据识别、图标/名称显示、自动填充命中。
- **P1（修复往返 bug）**：Bitwarden 导出对称化（回写 `androidapp://` URI + 应用名）。
- **P2（保真/稳定）**：自定义字段全量往返 + 扩展字段对称校验 + 显示名兜底。
- **（可选）P3**：本地-only 条目一次性规范化迁移脚本。

---

## 六、验证

- 单测扩充：`KeePassCustomFieldExtractionTest`、`BitwardenJsonExportTest` 增加 `monica_*` / `KP2A_*` / `androidapp://` 用例。
- 真机（荣耀 Android 17）：导入含旧 Monica 字段的 kdbx，确认应用绑定恢复、图标/名称显示、自动填充命中；Bitwarden 导出再导入往返不丢应用绑定。
- CI：Android CI debug 全绿。

---

## 七、待确认

- 范围：仅 P0？还是 P0+P1？或全做？
- 是否接受「读取端别名兼容」替代「一次性迁移」作为主方案？
- 是否现在就实现，还是先保留本计划文档待后续接力？
