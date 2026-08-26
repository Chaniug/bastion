# Bitwarden 同步提速/提稳方案：上传与全量下载解耦（2026-08-26）

> 背景：真机（荣耀/安卓17，自建服务器 `bit.valk.ccwu.cc`，高 RTT 2.4~6.5s）反馈：
> 新建一个密码条目想同步到 Bitwarden 自建服务器，但「一直在同步中」，很慢。
> 本文给出根因诊断、对照 Bitwarden 官方与 Keyguard 的实现、以及分期实施方案。
> 属于重点改动，**先出计划、确认后再改**；本文即接力开发用的设计文档。

---

## 一、根因诊断

### 1. 自建服务器（Vaultwarden）根本没有增量同步
经核查 Vaultwarden 源码与社区文档（`src/api/core/ciphers.rs` 的 `GET /sync`、
dev.to 长文《Why Vaultwarden Feels Slow》）：

> "Vaultwarden has no incremental sync. Every GET /api/sync returns the whole vault."

- `sinceRevisionDate` 是**官方 Bitwarden 服务器**的参数，Vaultwarden 的 `/sync` 只认
  `excludeDomains`，**忽略增量游标**。
- 因此上一轮 P1「增量同步」对 Vaultwarden **实际无效**——每次 `GET /api/sync` 仍拉整个保险库。
- 代价对 Vaultwarden 是**线性于条目数 + 每条密文大小**：条目上千、或有长安全笔记/多 URI 时显著变慢。

### 2. 新建条目的上传，被「整库下载」挡在前面
代码链路（`requestLocalMutationSync` → 编排器 `LOCAL_MUTATION` → `executeSync`）：

- `BitwardenRepository.sync()`（`BitwardenRepository.kt:755`）先 `fullSync()`（**整库下载**），
  再 `processPendingOperations()`（上传 pending 的增删改）。
- 即：本地新建条目 → 去抖 700ms → **先把整个 vault 下载回来** → 才 `POST /api/ciphers` 把新条目推上去。
- 在 Vaultwarden + 高 RTT 下，"整库下载" 动辄数秒到十几秒，新条目**要等整库下载完才真正落到服务器**，
  这就是「一直在同步中、很慢」的本体。

### 3. 全量同步与「上传新条目」被同一个 spinner 绑架
- `BitwardenSettingsScreen.kt:355` 的 `isSyncing = isUserVisibleSyncInProgress()`，
  判定为 `(isRunning || queued) && !isSilent`（`VaultSyncStatusExtensions.kt:7`）。
- 页面进入（`PAGE_ENTER`）/手动触发的是**非 silent 全量同步**，会直接显示「同步中」；
  而新建条目的 `LOCAL_MUTATION` 虽是 silent，但它也走同一条 `executeSync`，
  整段耗时仍由「整库下载」主导，体验上就是「卡在同步里」。

### 4. 稳定性隐患：401 未做自动刷新（D5 未做）
- 性能文档 `bitwarden-sync-perf-2026-08.md` 把 D5（OkHttp `Authenticator` 401→刷新+重放）列为「未做」。
- 同步中途令牌过期拿到 401 → 整次 sync 失败 → 按 `SyncManagerConfig` 退避重试（5s~15min），
  每次重试又从头整库下载 → 雪上加霜的「卡很久」。

---

## 二、对照：Bitwarden 官方 & Keyguard 怎么做

| 维度 | 官方 Bitwarden (Android) | Keyguard (KMP) | 当前 Bastion |
|---|---|---|---|
| **单条保存** | `CipherService` 直接 `POST/PUT /api/ciphers`，立即返回服务端 cipher（含 id+revisionDate），**不依赖**全量 sync | `CipherRepository.create/update/delete` 直接调 API 并原地更新内存 vault 状态 | 被 `executeSync` 的整库下载挡在后面 |
| **全量同步** | `SyncService` 单独跑 `GET /api/sync`，低频/登录/回前台/手动；与保存解耦 | `SyncService` 独立做全量，与逐条 mutation 解耦 | 与上传耦合在同一次 `executeSync` |
| **乐观 UI** | 条目立即出现在列表，保存成功才消「上传中」 | 内存 vault 立即更新，后台 reconcile | 只有全局「同步中」spinner |
| **增量同步** | 官方服务器支持 `sinceRevisionDate` | 同官方（依赖服务器） | 已加但 Vaultwarden 不认，等于空转 |

**可借鉴的两点核心思想：**
1. **上传单条改动 = 一次独立的轻量请求，绝不等全量下载。** 新建条目只需 `POST /api/ciphers`，
   服务器返回即视为已同步，无需先 `GET /api/sync`。
2. **全量下载只用于「拉取其他设备的变更」，是低频后台 reconcile，与「我刚保存的这条」无关。**

---

## 三、实施方案（分期，建议全部走 `dev` 分支）

### P0 — 服务器能力探针（诊断，无代码改动，先做）
- 在真机/日志里：发一次 `GET /api/sync?sinceRevisionDate=<近1分钟>` 与一次全量，对比
  `revisionDate` 与响应体大小。
- 若两次大小几乎一致 → 证实服务器忽略增量游标（Vaultwarden）→ 坐实根因 #1，P1 成为必做项。

### P1 — 上传与下载解耦（核心、最大收益、最低风险）★ 必做
- 新增轻量入口 `BitwardenPushService.pushPendingNow(vault)`（或扩展 `BitwardenSyncService`）：
  **只跑 `processPendingOperations()`（上传 pending 增删改），不调用 `GET /api/sync` 下载。**
- `requestLocalMutationSync`（`BitwardenRepository.kt:1239`）改为：去抖后调用 `pushPendingNow()`，
  而不是 `executeSync` 整段。
- 全量 `GET /api/sync` 仍保留，但仅用于后台 reconcile（已有的 `PAGE_ENTER`/`APP_RESUME` 节流路径），
  不再作为「上传新条目」的前置条件。
- 效果：新建条目 → **一次 `POST /api/ciphers`（约 1 个 RTT，几百 ms~几 s）** → 服务器返回 cipher
  （带 id+revisionDate）→ 本地标记该条目已同步 → 清「同步中」。整库下载完全不需要。
- 涉及：`BitwardenRepository.kt`(755/780/1239)、`BitwardenSyncService.kt`(`processPendingOperations`:792)、
  `BitwardenMutationSyncBridge`、`BitwardenSyncOrchestrator.kt`(LOCAL_MUTATION 分支)。

### P2 — 乐观 UI + 逐条目同步态（体验，配套 P1）
- `VaultSyncStatus`（或新增 per-entry 状态）增加 `uploadingCount` / 逐条 `isDirty` 的可见态；
  新建条目**立即**显示在列表，带一个小的「↑ 上传中」标记，上传成功即清除。
- 不再用全局「同步中」代表「我刚保存的那条」，与 Bitwarden/Keyguard 一致；
  全量 reconcile 的「同步中」仅在该次手动/页面同步时短暂出现。
- 涉及：`BitwardenSettingsScreen.kt:355`、`VaultSyncStatusExtensions.kt:7`、`BitwardenViewModel.kt:175`。

### P3 — 稳定性：D5 OkHttp Authenticator（401→刷新+重放）★ 建议做
- `BitwardenApiFactory.kt` 的 OkHttp 增加 `Authenticator`：收到 401 → 用 refresh token
  调 `/identity/connect/token` 换新 access token → 重放原请求一次。
- 直接消除「中途 401 → 整次 sync 失败 → 从头整库重试」的长时间卡死。
- 配合 P1 后，即便要重试也只需重放那一次轻量 `POST`，不再整库重下。

### P4 — 下载侧降本（服务器相关，可选）
- 若确认是 Vaultwarden（增量无效）：靠**降频 + 压缩**降本——
  - 反向代理开启 gzip/brotli（Vaultwarden 的 sync JSON 高度重复，压缩比极高，零数据库改动）；
  - 客户端 `Accept-Encoding: gzip` 已默认，确认链路有压缩。
  - 维持/加大 `pageEnterThrottleMs`/`appResumeThrottleMs`（P2 节流已做 90s/180s）。
- 若服务器是官方 Bitwarden（支持 `sinceRevisionDate`）：保留 P1 增量，P4 可省。
- 关于 WebSocket：`Vaultwarden` 的移动端**不**支持实时推送（仅桌面/浏览器），所以移动端仍需轮询，
  方向是「低频后台轮询 + P1 即时上传」，而非依赖推送。

---

## 四、风险与回滚
- **P1 上传前不下载**：新建条目 `POST` 本就不需要先下载，安全；编辑已存在条目 `PUT` 也不需要先下载
  （Vaultwarden 用 `last_known_revision_date` 做乐观锁，过旧会 409/冲突——此时回退到一次全量 reconcile 再重试即可）。
- **冲突处理**：若 `pushPendingNow` 遇到服务端冲突/特定错误，自动降级为 `executeSync` 全量后再推，保证最终一致。
- **回滚粒度**：P0~P4 各自独立可回滚；P1 是收益最高、风险最低，可先行；任何一期出问题都能单独 revert。

## 五、验证
- **CI**：`Android CI debug` + `CodeQL Advanced` 保持 success（dev 推送触发）。
- **真机（荣耀/安卓17，自建服务器）**：
  - 新建一条密码 → 应在约 1 个 RTT 内出现在服务器（单 `POST`），「同步中」快速消除；
  - 全量下载不再是「上传新条目」的前置；日志出现 `push-only`（无 download）标记即生效；
  - 令牌过期场景下不再出现「整段从头重试」的长卡死（P3）。
- 诊断日志：P1 触发时打印走的路径（push-only vs full-sync），便于真机核对。

## 六、分支策略
- 全部在 `dev` 分支实现与自测，CI 绿、真机验证通过后再合入 `main`（遵循项目既定流程）。
