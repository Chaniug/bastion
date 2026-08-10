# Bitwarden 同步问题排查：对照 Monica-Pass/Monica 的修复

> 排查时间：2026-08-10
> 起因：用户发现上游项目 `Monica-Pass/Monica` 修复了若干 Bitwarden 同步相关问题，质疑 bastion 是否存在同类问题。
> 结论：bastion 与 Monica 架构高度同源（同名 `BitwardenSyncService` / `CipherSyncProcessor` / `BitwardenSyncOrchestrator`），但**存在 1 个明确的数据保真 bug（自定义字段下载丢失）和 1 个性能隐患（多账号被动同步未串行化）**。另 2 个 Monica 修复在 bastion 上不适用或已规避。

## 一、Monica 的 4 个相关修复速览

| Commit | 标题 | 类别 |
|---|---|---|
| `5595324d78` | fix(bitwarden): scope auto sync to selected vault | 自动同步范围 |
| `047e1efdda` | feat(bitwarden): 完善自动同步逻辑，缓解多账号同步卡顿 | 自动同步性能 |
| `e348623f3f` | fix(android): sync Bitwarden password custom fields | 数据保真 |
| `afaf23d01f` | fix(android): harden external vault imports | 外部导入（Steam/KeePass/附件） |

## 二、逐项对照 bastion 现状

### 1. scope auto sync to selected vault（`5595324d78`）—— bastion 不存在同类 bug ✅

- Monica 修前：自动同步未限定到所选/活跃 vault，可能同步错误的 vault。
- bastion 现状：`BitwardenSyncOrchestrator` 的 `requestSync(vaultId: Long, …)` 天然按 `vaultId` 隔离，每个 vault 有独立 `VaultRuntime`，全部状态以 `runtimes: MutableMap<Long, VaultRuntime>` 分桶。调用方 `BitwardenViewModel.requestAutoSyncForUnlockedVaults` / `maybeTriggerSilentAutoSync` 明确传入具体 `vaultId`。
- 结论：**架构上已规避**，无需改动。（bastion 设计为启动时同步所有已解锁 vault，属有意行为，非 bug。）

### 2. 多账号同步卡顿（`047e1efdda`）—— bastion 存在同类隐患（已修复 ✅）

- Monica 修法：新增 `passiveAutoSyncMutex` 全局串行所有被动同步（PAGE_ENTER / APP_RESUME / PERIODIC），并引入 `MULTI_VAULT_AUTO_SYNC_STAGGER_MS = 5_000L` 错峰，避免冷启动多 vault 并发同步造成 UI 卡顿。
- bastion 现状（已修复）：原 `requestSync` 为每个 vault 独立协程、执行 `executeSync` 时无跨 vault 串行锁，多 vault 冷启动会并发同步。现新增全局 `passiveAutoSyncMutex`，仅对**被动同步**（`PAGE_ENTER`/`APP_RESUME`/`PERIODIC`）在 `executeSync` 外包锁串行化；主动同步（`MANUAL`/`LOCAL_MUTATION`/`RETRY`）不受限，不拖慢用户操作与重试。新增 `SyncManagerConfig.multiVaultAutoSyncStaggerMs = 5000L`，前一个被动同步完成后间隔错峰再放下一个。原有的 per-vault `mutex` 与 throttle 保持不变。
- 影响：多 vault 冷启动/切后台的并发同步已收敛为串行 + 错峰，缓解 UI 卡顿；属性能/体验改进，非数据错误。
- 是否修：✅ 已修复（dev）：`BitwardenSyncOrchestrator.kt` 加 `passiveAutoSyncMutex` + `maybeStaggerPassiveAutoSync()`，新增单测 `passiveAutoSyncAcrossVaultsIsSerialized` 验证跨 vault 串行。

### 3. 自定义字段同步（`e348623f3f`）—— bastion 存在同类 bug（下载侧数据丢失）🔴

- Monica 修法：新增 `BitwardenPasswordCustomFieldAdapter`，把 Bitwarden cipher 上的**任意用户自定义字段**正确纳入同步（下载落库 + 上传保留），并修复上传时覆盖服务端未知字段的问题。
- bastion 现状（已实证）：
  - **关键发现：bastion 已有完整的自定义字段基础设施**，无需新增 `PasswordEntry` 列或 Room 迁移：
    - 独立表 `custom_fields`（`data/CustomField.kt` 的 `CustomField` 实体 + `CustomFieldDao`，`replaceFieldsForEntry` 事务提供「删后重插」）。
    - 密码详情页 `PasswordDetailScreen` **已经**通过 `getCustomFieldsByEntryIdSync` 加载并渲染 `custom_fields`；`AddEditPasswordScreen` 也支持编辑保存。
    - Card/Document（SecureItem）路径已在 `CipherSyncProcessor.syncCardCipher/syncIdentityCipher` 中通过 `toCardCustomFields()/toDocumentCustomFields()` 把自定义字段落库。
  - **真正的 bug 范围收窄到 Login（Type 1）路径**：`CipherSyncProcessor.syncPasswordCipher` 调用 `decryptCustomFieldMap(cipher.fields, …)` 解密了全部字段，但仅提取 bastion **保留字段**（appPackageName/email/phone/地址/ssh 等映射到 `PasswordEntry` 专用列），其余用户自定义字段（如「PIN」「密保问题」「会员号」「License Key」）被**完全忽略，且未写入 `custom_fields` 表** → 本地视图丢失数据。
  - 上传侧原本也会丢：旧 `buildEncryptedPasswordCustomFields` 只回传保留字段，从不读 `custom_fields` 表，导致 bastion 把条目推回 Bitwarden 时用户自定义字段丢失。
- 影响：用户从 Bitwarden 同步的登录项若带自定义字段，在 bastion 中**不可见、且回传 Bitwarden 时也会丢**。这是真实的数据保真缺陷，与 Monica 该修复直接对应。
- 实际修复（已实现于 dev，无需 DB 迁移，纯同步层改动）：
  1. `CipherSyncProcessor`：新增 `customFieldDao`；新增 `buildLocalCustomFields(cipher.fields, key)`，用 `decryptCustomFields`（保留 type）解密后，**排除 bastion 保留名**（`bastion_*` 前缀 + appPackageName/email/phone/地址类），其余映射为 `CustomField`；在 `syncPasswordCipher` 的「新建」与「更新」分支落库（`replaceFieldsForEntry`）。本地已编辑（`bitwardenLocalModified=true`）的条目在更早分支返回、不会被覆盖。
  2. `BitwardenSyncService`：`buildEncryptedPasswordCustomFields` 改为 `suspend`，回读 `custom_fields` 表，把用户字段追加进 `CipherFieldApiData`（`isProtected → type 1`）；同时将 `passwordEntryToCipherRequest` / `passwordEntryToCipherUpdateRequest` 标为 `suspend`（唯一调用方 `uploadLocalEntry` 本就 suspend）。保留名校验避免与专用列字段重复上报。

### 4. 外部 vault 导入加固（`afaf23d01f`）—— 基本不适用 ⚪

- 该提交核心是恢复 **Steam maFile 检测、KeePass 外部导入、附件元数据解码**。bastion 没有 Steam / KeePass 外部 vault 体系（仅 `api/BitwardenApi.kt` 含附件上传/下载接口定义）。
- 附件：bastion 已有附件 API，但本次修复涉及的「附件元数据解码（`BitwardenAttachmentMetadataDecoder`）」在 bastion 中未见对应实现——可视为未来 enh，但非本次「同步」范畴，且与 Monica 的 Steam/KeePass 路径强耦合，不强行移植。

## 三、行动建议

| 优先级 | 问题 | 状态 |
|---|---|---|
| 🔴 P0 | 自定义字段下载丢失（`e348623f3f` 同类） | ✅ 已修复（dev）：复用现有 `custom_fields` 表，无需 DB 迁移 |
| ✅ P2 | 多账号被动同步未串行化（`047e1efdda` 同类） | 已修复（dev）：加 `passiveAutoSyncMutex` 串行被动同步 + `multiVaultAutoSyncStaggerMs=5000L` 错峰 |
| ✅ 已规避 | 自动同步范围（`5595324d78`） | 无需改动 |
| ⚪ 不适用 | 外部导入加固（`afaf23d01f`） | 不在本范围 |

## 四、待办 / 待确认

- **P0 已落地 dev**，需经 CI（debug + 真机预览）验证：带自定义字段的 Bitwarden 登录项同步后，在 bastion 详情页可见；回传 Bitwarden 不丢。
- **P2 多账号串行化**已落地 dev（全局 `passiveAutoSyncMutex` + 错峰），待 CI 验证后随 stable 发布。
- 重点改动计划同步落到 `docs/` 以便 agent 接力（遵循规范 #6）。
