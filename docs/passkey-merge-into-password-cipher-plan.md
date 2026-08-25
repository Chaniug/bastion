# Passkey 合并进密码 cipher 改造（passkey-merge-into-password-cipher）

> 状态：**已实现并合并到 dev（CI 全绿）**，待真机验证。
> 分支：dev（提交 d692acaa / f0fd1d03 / 9700492d / 5d29d3ca / d259b113）
> 关联问题：绑定型 passkey 在 Bitwarden 服务器端显示为独立 [Passkey] 条目，无法与密码条目同条目显示。

## 一、背景与问题

用户在 bastion 把 passkey「绑定到密码条目」，但同步到自建 Bitwarden 服务器时创建的是**独立 login cipher**（`name="xxx [Passkey]"`、`login.fido2Credentials` 单元素、`POST /ciphers`），服务器端看不到「passkey 与密码同条目」。

**Bitwarden 官方模型**（`bitwarden/server` 源码 `CipherLoginData.cs` 实锤）：passkey 存放在密码 cipher 的 `login.fido2Credentials` 数组里（与 `Username`/`Password` 平级，一个 cipher 可挂多个 passkey）。保存 passkey 时官方客户端会问「加到哪个已有条目或新建」——选已有条目 = 把 credential 塞进该条目后 PUT。

## 二、改造目标（用户已确认）

1. **绑定型 passkey**（`boundPasswordId != null` 且密码条目有 `bitwardenCipherId`）：同步时**合并进密码 cipher 的 `login.fido2Credentials`**（PUT 更新密码 cipher），不再创建独立 cipher；本地 `PasskeyEntry.bitwardenCipherId` 指向密码 cipher。
2. **未绑定 passkey**：维持现状（创建独立 login cipher，与官方「新建条目」一致）。
3. **旧数据迁移**：已上传的独立 `[Passkey]` cipher 合并进对应密码 cipher 并**软删**（DELETE /ciphers/{id}）；同步时自动迁移（仿 `BitwardenHistoricalTotpRepairService`），失败可重试、幂等。
4. **下载侧**：密码 cipher 带 fido2Credentials 时本地生成 1 密码条目 + N passkey 条目，并**回填 `boundPasswordId`**（此前恒 null）。

## 三、核心设计决策

1. **fido2 合并只动 `fido2Credentials` 字段**，其余字段（name/notes/username/password/uris/fields）基于 GET baseline **原样回传**——避免整体 PUT 覆盖密码 cipher 的其它内容（与 `passwordEntryToCipherUpdateRequest` 的 BUG-2 保护思路一致）。
2. **解密/加密/去重逻辑提取为 `Fido2CredentialCodec`** 公共纯逻辑，上传合并、迁移、下载三处复用。
3. **fido2 变更（增/改/删）统一由 passkey 表 `syncStatus` 驱动**，不走密码条目的 `bitwardenLocalModified`；密码编辑 PUT 的 BUG-2 逻辑保持不动，两路互不干扰。
4. **迁移服务复用上传合并函数**，额外做「扫描 + 软删独立 cipher + 清理下载侧遗留空密码条目」。

## 四、改动清单

| 文件 | 改动 |
|---|---|
| `Bastion/app/src/main/java/com/bastion/app/bitwarden/mapper/Fido2CredentialCodec.kt`（新增） | 解密（decodeFido2Credentials / decryptCredentialsToPlainApiData / decryptOrPlain）、加密（encryptCredential(s)）、去重（mergeByCredentialId，按 `PasskeyCredentialIdCodec` 规范化比较） |
| `.../bitwarden/service/CipherUploadProcessor.kt` | 新增 `mergePasskeyIntoPasswordCipher`（GET baseline → 解密合并去重 → 重加密 → PUT 密码 cipher → markSynced 指向密码 cipherId）、`removeFido2CredentialFromPasswordCipher`（删除语义）；`uploadPendingPasskeys` 按绑定关系分流；`uploadModifiedPasskeys` 支持 DELETE_PENDING / 绑定型 merge 分支 |
| `.../bitwarden/service/CipherSyncProcessor.kt` | `syncPasskeyCipher` 按 cipherId 查密码条目回填 `boundPasswordId`（三处构造）；`decodeFido2Credentials` 委托 codec |
| `.../data/PasskeyDao.kt` | 新增 `getBoundPasskeysPendingUpload` / `getBoundPasskeysOnStandaloneCipher` / `getPasskeysPendingFido2Removal`（JOIN password_entries） |
| `.../data/PasskeyEntry.kt` | 新增 `SYNC_STATUS_DELETE_PENDING` 常量 |
| `.../bitwarden/service/BitwardenHistoricalPasskeyMergeService.kt`（新增） | 自动迁移：扫描独立 cipher → 校验 isPasskeyCipher + 含本地 credentialId → 合并 → 软删独立 cipher → 清理空密码条目；失败标记 FAILED 下轮重试 |
| `.../bitwarden/repository/BitwardenRepository.kt` | sync 流程 processPendingOperations 之后、uploadLocalEntries 之前接入迁移服务 |
| `.../ui/screens/PasskeyListScreen.kt` | `deletePasskeyWithBinding` 修复：绑定型不再 queueCipherDelete（原实现会误删整个密码 cipher！），改 DELETE_PENDING 由同步移除 credential 后删本地 |

## 五、安全与边界

- **禁止盲 PUT**：合并/移除前必须 GET baseline（拿不到 → Error 可重试），防止清空服务器上其他 passkey。
- **并发**：合并保留 baseline 全部既有 credential，只增改本地项；单 vault `syncMutexForVault` 串行。（P3 可选增强：PUT 前 revisionDate 二次校验，复用 `hasSameRemoteRevision` 先例。）
- **多设备**：A 删/B 增均以 baseline 为准，credentialId 去重不受影响。
- **KeePass 侧隔离**：DAO 带 `passkey_mode = BW_COMPAT` 过滤 + `canSyncPasskeyToBitwarden`，`MODE_KEEPASS_COMPAT` 不进入该流程。
- **SSH 分支**（passwordEntryToCipherRequest 的 SSH 分支）：不涉及 fido2，合并函数只对非 SSH 密码条目执行。
- **BUG-2 保护保持**：密码条目编辑 PUT 的 `fido2Credentials = baseline 原样回传` 不动。

## 六、测试

- CI（Android CI debug）：lint + 单测 + assembleDebug 全绿；CodeQL 通过。
- 新增源码守卫测试：`Bastion/app/src/test/java/com/bastion/app/sync/PasskeySyncMergeGuardTest.kt`（6 项断言：合并分流、baseline 保护、删除不误删密码 cipher、下载回填、迁移顺序、codec 能力）。
- 依赖说明：无新增 gradle 依赖；测试沿用 JUnit4 + coroutines-test + MockK。

## 七、真机验证步骤（荣耀 Android 17 + 自建 Vaultwarden）

预览 APK：GitHub Release `preview`（app-arm64-v8a-debug.apk，build.202608250323 起）。

1. **新建绑定**：创建/选择一个密码条目 → 创建 passkey 并绑定 → 同步 → 服务器端该密码条目 `login.fido2Credentials` 含该 credential，且**不再出现独立 [Passkey] 条目**。
2. **多端同步**：其他设备/官方客户端同步后，passkey 与密码在同一 login 条目内。
3. **历史数据迁移**：旧数据（独立 [Passkey] cipher）触发一次同步即完成合并 + 独立 cipher 进回收站（软删）；断网/失败下次同步自动重试；重复同步幂等。
4. **删除语义**：删除绑定型 passkey → 服务器密码 cipher 的 fido2 数组移除该 credential、密码 cipher 本体存活；删除未绑定 passkey → 软删独立 cipher（现状）。
5. **解绑边界**（未自动化，需人工确认）：解绑后 passkey 应从原密码 cipher 移除 credential 并重建为独立 cipher。
6. 与 KeePass 侧 `MODE_KEEPASS_COMPAT` passkey 并存无干扰。

## 八、后续事项（P3 候选）

- 解绑（boundPasswordId 置 null）的自动远端同步（当前只在 P2 删除语义中处理了绑定型；解绑重建独立 cipher 的远端清理列入验证清单）。
- 并发窗口增强：PUT 前 revisionDate 校验（复用 `hasSameRemoteRevision`）。
- 若需要，可加 `Fido2CredentialCodec` 纯逻辑单测（mergeByCredentialId 去重/加密/解密），当前由源码守卫覆盖核心行为。

## 九、协作提示（供接力 agent）

- 本地沙箱构建注意：`dl.google.com`/`repo.maven.apache.org`/`services.gradle.org` 被 DNS 劫持，`~/.gradle/init.gradle` 已配镜像注入（会被环境自动恢复为预设版，无需重复配置）；Android SDK 已装于 `/opt/android-sdk`（platform-37.0 + build-tools 36/37），`Bastion/local.properties` 指向它（已被 .gitignore 忽略）。
- GitHub API 直连：hosts 已把 `api.github.com → 140.82.112.6`、`github.com → 140.82.121.3`（若失效重新探测真实 IP，见仓库根 README 网络说明）。
