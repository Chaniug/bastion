# Bitwarden 同步性能优化（2026-08，B1：D1 + D4）

## 背景
真机反馈：Bitwarden 同步慢，尤其自建服务器——网页登录很快但 Bastion app 同步很慢。调研根因（按可能性排序）：

1. **每次全量** `GET /api/sync`（`BitwardenApi.kt:135-139`），无 `sinceRevisionDate`/分页；页面进入(45s 节流)/前台恢复(60s)/解锁后静默/手动都会触发（`BitwardenSyncOrchestrator.kt:54-62`）。
2. **写库无单事务**：`processSyncResponse`（`BitwardenSyncService.kt:232-412`）逐条 cipher 独立事务 + 每条多次 DAO 查询。
3. **网络慢失败**：OkHttp connect 30s / read 60s，无 `connectionPool`/`pingInterval`（`BitwardenApiFactory.kt:85-87`），无 401 Authenticator。
4. 本地脏数据时 HTTP N+1（`updateRemoteCipher` GET+PUT、`reconcilePendingPasswordUploadsFromRemote` 额外全量 sync）。

## 本次改动（B1 最小可行）

### D1：写库包进单事务
- `BitwardenSyncService.processSyncResponse`（`:232-412`）整体包进 `database.withTransaction { }`。
- 千条 cipher 不会变成千次 fsync，自建服务器/手机闪存下大 vault 同步显著加速。
- `createConflictBackup`（仅 DB/磁盘写）与 `attachmentReconciler.reconcile`（仅元数据对账，不下载字节）均留在事务内——`is_locked` 与 sync 闸门确保无并发写。
- 解密（CPU）留在事务内：只延长写锁持有时间，不引入死锁。
- 失败时整段回滚，行为与原 try/catch 抛出一致。

### D4：OkHttp 快失败
- `BitwardenApiFactory.kt:84-109`：
  - `connectTimeout` 30s → **10s**
  - 新增 `connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))`：自建高 RTT 下避免每次 sync 重建 TCP/TLS
  - 新增 `pingInterval(30, TimeUnit.SECONDS)`：HTTP/2 keepalive，VPN/自签 CA 抖动时快速发现死连接

## 未做（后续可选）
- **D2** 拉长 `pageEnterThrottleMs`(45→90s)/`appResumeThrottleMs`(60→180s)：减少进页/回前台的全量同步次数（新鲜度略降）
- **D5** OkHttp `Authenticator` 401→refresh+replay：消除"中途 401→整次失败重试"的整段延迟
- 真增量同步：若服务端支持 `sinceRevisionDate`/`excludeDomains=false` 等增量参数，可大幅降低大 vault 同步耗时

## 验证
- Android CI debug + CodeQL Advanced 需保持 success。
- 真机（荣耀/安卓17）：
  - 首次进 Bitwarden 页同步耗时应有可感知下降（大 vault 尤为明显）
  - 切网络后失败的同步应更快返回（不再卡满 30s）
  - 同步期间 DB 写不再逐条 fsync，UI 流畅度提升

## 排错指引
- 若 `withTransaction` 报 `IllegalStateException`，检查是否有其它后台协程在事务外并发写同一 vault 的 cipher 表
- `connectTimeout=10s` 在极慢网络下可能误杀；若真机反馈"切到 2G 同步直接失败"，可酌情调回 15-20s

## 2026-08-26 追加：P1 增量同步 + P2 节流（基于真机 diag 日志）

真机 diag 日志证实：自建服务器（bit.valk.ccwu.cc）单次 HTTP 请求 RTT 2.4~6.5 秒（prelogin 2448ms / token 6546ms / 2FA 2935ms），"一直在连接"= 切页频繁触发全量同步 × 高 RTT。为此实施：

### P1 增量同步（核心）
- `BitwardenApi.kt`：`GET /api/sync` 加 `sinceRevisionDate` 查询参数（空=全量）；`SyncResponse` 加顶层 `revisionDate` 字段
- `BitwardenSyncService.fullSync`：加 `sinceRevisionDate` 参数，非空即增量；**增量模式下跳过空 Vault 保护**（响应里 cipher 数只是变更数，会误判）
- `processSyncResponse`：加 `isIncremental` 参数；增量模式下：
  - 跳过全量 delete-wins 清理（`deleteBitwardenEntriesNotIn`）与文件夹 `deleteNotIn`——增量响应只含变更，not-in 会把未返回条目误删
  - 删除语义交给 `syncCipherFromServer`（对 `deletedDate` 非空的 cipher 软删）
  - send 改为按 `deletedDate` 删除（`sendDao.deleteBySendId`），不做 not-in 清理
- `BitwardenRepository.sync`：fullSync 传 `sinceRevisionDate = vault.revisionDate`（首次同步 vault.revisionDate 为 null → 全量，之后自动增量）
- vault 的 revisionDate 游标：优先用 sync 响应顶层 RevisionDate，回退 `profile.securityStamp`

### P2 节流
- `BitwardenSyncOrchestrator.SyncManagerConfig`：`pageEnterThrottleMs` 45s→90s、`appResumeThrottleMs` 60s→180s

### 验证点（真机）
- 首次同步后再次切页：应只拉变更（快），不再整包全量
- 日志出现 `Starting incremental sync for vault`（非 full）即生效
- 删除一个 Bitwarden 条目后再同步：本地应正确移除（增量删除语义）
- 若增量同步后列表异常（条目凭空消失），说明服务端不兼容 sinceRevisionDate 或语义处理有误，回退方案：把 `BitwardenRepository.sync` 的 sinceRevisionDate 传参去掉即回到全量

