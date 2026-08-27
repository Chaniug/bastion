# Bitwarden 同步诊断日志（阶段 0）实施记录 — 2026-08-27

## 背景

用户反馈：已登录状态下点击 Bitwarden 同步「很慢很慢，感觉就没同步」，退出重登后正常。
导出日志（bastion_logs_20260827_082109.txt，构建 `git_sha=91438176`）中：

- `=== Bitwarden Persisted Logs ===` **完全为空**（仅有 session 元数据，零条同步记录）
- `Autofill Persisted Logs` 中 `candidatePasswords=215` → 本地已有 215 条密码离线缓存，数据在、autofill 可用
- 全文件搜 `fullSync / revision-date / precheck / 401 / refreshToken / pull / push` 零命中

结论：同步过程**基本不打诊断日志（可观测性为零）**，且失败路径静默、UI 报错通道未覆盖主 Vault 界面。

## 阶段 0 已做：补全 sync() / refreshToken() 全程诊断日志

文件：`Bastion/app/.../bitwarden/repository/BitwardenRepository.kt`
机制：复用已有 `BitwardenDiagLogger.append()`（持久化 ring buffer，上限 1MB，会写入导出报告的 `=== Bitwarden Persisted Logs ===` 段）。

新增日志点（均为 `BitwardenDiagLogger.append`）：

| 位置 | 日志标签 | 用途 |
|------|----------|------|
| `sync()` 入口 | `sync START` | 确认被调用（vaultId / forced / pullAfterPush / thread） |
| 各 early-return | `sync ABORT: vault not found / vault locked / symmetric key unavailable / access token unavailable` | 早期失败可视化 |
| token 刷新触发 | `accessToken expiring/Expired -> attempt refreshToken` | 是否走到刷新 |
| token 刷新完成 | `refreshToken finished in Xms, success=...` | 刷新耗时与成败 |
| `refreshToken()` 无 refreshToken | `refreshToken ABORT: no encryptedRefreshToken stored` | |
| `refreshToken()` 成功 | `refreshToken SUCCESS: expiresIn=...` | |
| `refreshToken()` 接口失败 | `refreshToken FAILED: identity/connect/token returned failure` | 区分 401/invalid_grant |
| `refreshToken()` 异常 | `refreshToken EXCEPTION: type=... msg=...` | 网络异常类型 |
| 预检防抖跳过 | `precheck: debounce skip` | |
| 预检 revision-date | `precheck: getRevisionDate HTTP=... serverRev=... localRev=... took Xms` | **定位卡在哪个网络请求（getRevisionDate 还是 fullSync）** |
| 预检失败/抛错 | `precheck: getRevisionDate FAILED/threw -> NOT skip` | |
| `fullSync` 开始 | `fullSync START: sinceRevisionDate=...` | |
| `fullSync` 结束 | `fullSync DONE: took Xms, resultType=...` | **卡顿/超时直接可见** |
| `fullSync` Error | `sync fullSync Error: message=...` | |
| `sync()` 异常兜底 | `sync EXCEPTION: type=... msg=...` | 网络超时等未捕获异常 |

## 重要发现（待后续处理）

1. **UI 报错通道缺口**：`BitwardenEvent.ShowError/ShowWarning/ShowSuccess` 仅在 `SendScreen.kt` 被消费（snackbar）。
   主 Vault 界面（`VaultV2Pane` 等，用户最常点同步处）**没有消费这些事件** → 同步失败时用户看不到任何提示，直接表现为「感觉没同步」。
2. **token 会话失效无 401 反应式恢复**：`sync()` 仅按 `accessTokenExpiresAt` 预判刷新一次，`fullSync` 撞 401 直接 `SyncResult.Error`，无自动重刷拦截器（详见 `bastion_bitwarden_sync_401_diagnosis_plan.md`）。

## 验证方式（下一步）

1. 提交到 dev → 观察 GitHub Actions（Android CI）编译/测试是否通过。
2. 用户在荣耀 Android 17 真机更新预览版，**复现「已登录点同步很慢」** → 开发者设置导出日志。
3. 看 `=== Bitwarden Persisted Logs ===` 段，按时间线定位：
   - 若 `sync START` 后紧跟 `refreshToken FAILED/EXCEPTION` 或 `ABORT: token refresh failed` → 确认 token 失效，走阶段 1（401 自动恢复）。
   - 若卡在 `precheck: getRevisionDate` 或 `fullSync START` 与 `DONE` 之间数十秒 → 网络层挂起，需查超时/连接复用。
   - 若 `sync START` 都没有 → `sync()` 根本未被调用（UI 入口问题）。

## 阶段 1（待做，需再次确认）

- 401 反应式自动恢复（OkHttp Authenticator / 拦截器）。
- 主 Vault 界面消费 `BitwardenEvent.ShowError` 等，让同步失败对用户可见。
