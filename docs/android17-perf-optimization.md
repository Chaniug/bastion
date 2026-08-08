# Android 17 专项优化计划（稳定性 / 流畅性 / 功耗 / 内存）

> 目标：针对 Android 17 (API 37) 做专项优化，提升稳定性与流畅性，降低运行功耗与内存占用。
> 分支策略：dev 开发验证 → 合并 main。所有改动以 release 包行为为准评估收益。
> 状态：阶段一已上线（dev→main，CI 全绿）；阶段二 #6 resConfigs 已上线；#9 LeakCanary 因 Android 17 启动崩溃已回退；#5/#7/#8 待计划确认。

## 现状调研摘要（关键痛点）

| 类别 | 问题 | 风险 |
|------|------|------|
| 功耗 | `MainThreadStallMonitor` 每秒主线程心跳 + 线程池检查，release 包常驻永不停止 | 🔴 |
| 内存 | `BitwardenApiFactory` 三个 `MutableMap` 缓存 OkHttp/Retrofit，永不驱逐（各带连接池+线程池） | 🔴 |
| 内存/稳定 | 7 处大图全尺寸解码无 `inSampleSize`（附件/图标/favicon/二维码/支持作者图） | 🔴 |
| 内存 | LruCache 按条数计量，`Bitmap` 大图占满不淘汰 | 🟡 |
| 流畅性 | 缺 Baseline Profile，冷启动/首帧抖动 | 🟡 |
| 包体/内存 | 未设 `resConfigs`，80+ 冗余语言资源常驻 | 🟡 |
| 流畅性 | Room 183 处 `SELECT *` 过度取列 | 🟡 |
| 流畅性 | `BaseBastionActivity` 主线程 `runBlocking` | 🟡 |
| 功耗 | `enableMultiInstanceInvalidation` 跨进程失效唤醒 | 🟡 |
| 稳定 | 无 LeakCanary（debug 缺位） | 🟡 |

## 阶段一 · 功耗与内存（已实施 ✅）

### 1. MainThreadStallMonitor 常驻唤醒治理
- 文件：`perf/MainThreadStallMonitor.kt`、`BastionApplication.kt`
- 改动：
  - `start(application)` 仅在 `BuildConfig.DEBUG` 时启动，**release 包默认不启动**，消除 1Hz 主线程心跳 + 线程池检查的无谓唤醒。
  - 通过 `ProcessLifecycleOwner` 监听 App 前后台：`onResume` 恢复、`onPause` 暂停，debug 包后台也不唤醒。
  - `executor` 改为 `lazy` 初始化，release 包因直接 return 不创建守护线程池，运行期零额外开销。
- 预期收益：release 待机功耗下降（消除持续 1Hz 唤醒）。

### 2. BitwardenApiFactory 无界缓存加界
- 文件：`bitwarden/api/BitwardenApiFactory.kt`（`BitwardenApiManager`）
- 改动：三个缓存 `okHttpClientCache / identityApiCache / vaultApiCache` 由无界 `mutableMapOf` 改为带容量上限的线程安全 LRU（`LinkedHashMap(accessOrder=true)` + `Collections.synchronizedMap`）。
- 阈值：OkHttpClient 8、identity/vault API 各 16。
- 预期收益：避免多 vault / 多 TLS 配置下 OkHttp 连接池、线程池长期累积导致内存增长。

### 3. 大图解码补 inSampleSize 采样
- 文件：`attachments/ui/AttachmentPreviewDialog.kt`、`autofill_ng/ui/FaviconCache.kt`、`ui/icons/PasswordCustomIconSupport.kt`、`ui/screens/QrScannerScreen.kt`、`ui/screens/SupportAuthorScreen.kt`
- 改动：7 处全尺寸解码（:65/:127/:550/:589/:617/:133/:556/:101）先 `inJustDecodeBounds` 探测尺寸，再按目标边长计算 `inSampleSize` 二次解码；网络流/资源流采用读字节数组或二次打开源的方式。
- 目标边长：附件/二维码 2048、stratum 图标 256、用户上传图标 512、favicon 128、支持作者图 1024。
- 预期收益：降低 OOM 风险，减少解码耗时与内存峰值。

### 4. LruCache 改按字节计量
- 文件：`autofill_ng/ui/AppIconCache.kt`、`autofill_ng/ui/FaviconCache.kt`、`ui/icons/PasswordCustomIconSupport.kt`
- 改动：`LruCache` 由按条数改为 `sizeOf()` 返回 `asAndroidBitmap().allocationByteCount / 1024`（KB），`maxSize` 同步改为约 4MB（KB 计）。
- 预期收益：大图标/大 favicon 能正确被 LRU 淘汰，防止内存膨胀。

## 阶段二 · 流畅性与构建（部分已实施）

5. 接入 Baseline Profile（`baseline-prof.txt` + `ProfileInstaller`）：`profileinstaller:1.4.1` 早已在 `implementation` 依赖中，但尚无 `baseline-prof.txt`。**真正生成需在真机/模拟器跑 `generateBaselineProfile`**，沙箱无设备，且属重点改动 → 暂缓，单独提计划确认后再做。
6. ✅ `app/build.gradle` 加 `resConfigs "en","zh"`，仅保留默认英文 + 中文，移除 80+ 冗余语言资源常驻（包体/内存）。
7. Room 投影：高频列表查询 `SELECT *` 收敛为投影列（183 处，需逐条核对，属重点改动 → 暂缓，单独提计划）。
8. 清理 `BaseBastionActivity` 主线程 `runBlocking`（涉初始化时序，属重点改动 → 暂缓，单独提计划）。
9. ⚠️ 已回退：`leakcanary-android:2.14` 在 Android 17(API 37) 进程启动期(ContentProvider 自启)崩溃，导致预览 debug 版无法启动（桌面无图标/点不开）。已回退该依赖。待 LeakCanary 3.0 兼容性确认后，作为重点改动单独提计划重做。

## 阶段三 · 进一步打磨（待做 ⬜）

10. 评估 `enableMultiInstanceInvalidation` 是否必要（跨进程唤醒代价）。
11. `assets/stratum_icons` 6MB PNG 转 WebP / 按需下载。

## 风险与回滚

- 阶段一均为运行时优化，不影响 ABI / 数据库 schema，CI 风险低。
- 若 `MainThreadStallMonitor` 改为 debug-only 后影响线上卡顿遥测，可后续加远程开关恢复（当前为降功耗首选）。
- 回滚：`git revert` 对应 commit 即可。
