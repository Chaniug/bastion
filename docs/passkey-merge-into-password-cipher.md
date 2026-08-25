# 绑定型 Passkey 合并进密码 cipher 改造说明

> 状态：已实现并合入 dev（2026-08-25），Android CI debug 通过、preview APK 已发布。
> 分支：dev（commit d692acaa / f0fd1d03 / 5d29d3ca / d259b113）

## 背景

用户在 bastion 把 passkey「绑定到密码条目」后，同步到自建 Bitwarden 服务器时创建的是**独立 login cipher**（`name="xxx [Passkey]"`、`login.fido2Credentials` 单元素、`POST /ciphers`），服务器端（含自建 Vaultwarden）看到的是独立条目，无法与密码条目合并显示。

Bitwarden 官方模型（`bitwarden/server` 源码 `CipherLoginData.cs` 实锤）：**passkey 存放在密码 cipher 的 `login.fido2Credentials` 数组里**，与 `username/password` 平级，一个 cipher 可挂多个 passkey。官方客户端在创建 passkey 时会询问「加到已有条目还是新建」，选已有条目即把 credential 塞进该条目的 `fido2Credentials` 后 `PUT /ciphers/{id}`。

## 改动内容

### P1 核心（d692acaa）：绑定型 passkey 合并进密码 cipher

| 文件 | 改动 |
|---|---|
| `bitwarden/mapper/Fido2CredentialCodec.kt`（新增） | fido2 credential 解密 / 加密 / `credentialId`（规范化）去重合并的公共纯逻辑；`CipherSyncProcessor` 的 `decodeFido2Credentials` 改为委托复用 |
| `bitwarden/service/CipherUploadProcessor.kt` | 新增 `mergePasskeyIntoPasswordCipher`：GET 密码 cipher baseline → 解密已有 fido2 → `mergeByCredentialId` 明文去重合并（本地覆盖同名、保留其他）→ 重加密 → **仅替换 `login.fido2Credentials`**，其余字段（name/notes/username/password/uris/fields 等）基于 baseline 原样回传 → `PUT /ciphers/{密码cipherId}` → 本地 passkey 的 `bitwardenCipherId` 指向密码 cipher。baseline 获取失败返回 Error 可重试，**禁止盲 PUT 清空服务器 passkey** |
| 同上 | 新增 `removeFido2CredentialFromPasswordCipher`（删除语义）：GET baseline → 解密 → 按 `credentialId` 过滤移除 → 重加密 → PUT，成功后删本地 passkey |
| 同上 | `uploadPendingPasskeys` 按绑定关系分流：绑定型且密码已有 cipherId → 合并；未绑定 / 密码未同步 → 维持独立 cipher 创建（与官方「新建条目」一致） |
| 同上 | `uploadModifiedPasskeys` 支持 `DELETE_PENDING` 分支与绑定型合并刷新 |
| `bitwarden/service/CipherSyncProcessor.kt` | `syncPasskeyCipher` 按 `bitwardenCipherId` 查本地密码条目并**回填 `boundPasswordId`**（密码 cipher 带 fido2 时 passkey 关联回密码条目） |
| `data/PasskeyDao.kt` | 新增 `getBoundPasskeysPendingUpload` 等 JOIN 查询 |
| `data/PasskeyEntry.kt` | 新增 `SYNC_STATUS_DELETE_PENDING` 常量 |

### P2 迁移 + 删除语义（f0fd1d03）

| 文件 | 改动 |
|---|---|
| `bitwarden/service/BitwardenHistoricalPasskeyMergeService.kt`（新增） | 同步时自动扫描「绑定型但仍在独立 cipher 上」的 passkey（`bitwarden_cipher_id != 密码 cipherId`）：GET 独立 cipher 校验 `PasskeyMapper.isPasskeyCipher` + 含本地 credentialId → 复用合并函数 → **软删独立 cipher**（404 视为成功）→ 清理下载侧遗留空密码条目。单条失败 `markFailed` 下轮自动重试，幂等 |
| `bitwarden/repository/BitwardenRepository.kt` | `sync()` 在 `processPendingOperations` 之后、`uploadLocalEntries` 之前接入迁移服务（避免待迁移 passkey 被当独立条目上传） |
| `ui/screens/PasskeyListScreen.kt` | `deletePasskeyWithBinding` 修复：**绑定型 passkey 不再 `queueCipherDelete`**（其 cipherId 指向密码 cipher，旧逻辑会误删整个密码条目！），改为标记 `DELETE_PENDING`，由同步从密码 cipher 的 fido2 数组移除后删本地；未绑定 / 独立 cipher 删除逻辑保持现状 |

### 测试（9700492d / 5d29d3ca / d259b113）

- `Fido2CredentialCodecTest`：去重合并（覆盖/追加/保留）、明文透传、空信号过滤、不可解密密文降级
- `PasskeySyncMergeGuardTest`：源码守卫（合并路径不新建独立条目、删除不误删密码 cipher、下载回填、迁移顺序）

## 行为变化

- 新建「绑定到密码」的 passkey → 同步后服务器密码 cipher 的 `login.fido2Credentials` 含该 credential，**不再产生独立 `[Passkey]` cipher**
- 历史独立 `[Passkey]` cipher → 下一次同步自动合并进密码 cipher 并软删（进回收站，可恢复）
- 删除绑定型 passkey → 从密码 cipher 的 fido2 数组移除该 credential，密码条目本体不受影响
- 未绑定 passkey → 行为与改造前一致（独立 login cipher）

## 关键安全点

1. **合并必须基于 GET baseline**：PUT 前实时拉取密码 cipher 最新数据（含其他设备/官方客户端添加的 passkey），按 `credentialId` 去重合并，绝不盲 PUT。
2. **只动 `fido2Credentials`**：其余字段基于 baseline 原样回传（与 `passwordEntryToCipherUpdateRequest` 的 BUG-2 保护一致）。
3. 密码条目编辑 PUT（`passwordEntryToCipherUpdateRequest`）**保持不动**（fido2 原样回传），fido2 变更一律由 passkey 表 `syncStatus` 驱动，两路互不干扰。
4. KeePass 侧 passkey（`MODE_KEEPASS_COMPAT`）不参与 Bitwarden 同步（`canSyncPasskeyToBitwarden` + DAO `passkey_mode` 过滤）。

## 已知边界（后续可优化）

- **解绑场景**：把绑定型 passkey 解绑（`boundPasswordId` 置 null）时，当前不会自动从密码 cipher 移除原 credential；下次同步会按「未绑定」重建独立 cipher，服务器上可能残留旧 credential。需要时补充「解绑即移除」逻辑。
- **并发覆盖**：两台设备同时向同一密码 cipher 追加 passkey 存在极短覆盖窗口（已用「保留 baseline 全部既有 credential」缓解；单 vault `syncMutexForVault` 串行）。如需更强保障可加 PUT 前 `revisionDate` 校验（复用 `hasSameRemoteRevision` 先例）。
- **Android SDK 37 本地构建**：沙箱内 `dl.google.com` 被 DNS 劫持，AGP 需要从远程 manifest 解析 `platforms;android-37.0`（ApiLevel 带小数），本地直连无法完成编译；**本地验证依赖 GitHub Actions**（runner 网络正常）。本地镜像注入方式：`~/.gradle/init.gradle` 用 `gradle.beforeSettings` 注入腾讯 maven 镜像（`repositories` 容器无 `clear()`，且 `settingsEvaluated` 中 `size/clear` 不可用）。

## 验证方式

1. 真机（荣耀 Android 17）安装 preview APK：`https://github.com/Chaniug/bastion/releases/tag/preview`
2. 新建绑定 passkey → 同步 → 在自建 Bitwarden 服务器确认密码条目内出现 passkey（无独立 `[Passkey]` 条目）
3. 删除绑定型 passkey → 确认密码条目本体仍在、passkey 被移除
4. CI：Android CI debug（lint + assembleDebug + 单测基线）+ CodeQL 全绿
