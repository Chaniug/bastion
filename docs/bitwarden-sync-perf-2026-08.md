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
