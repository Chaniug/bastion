# 计划：targetSdk 拉到 Android 17 (API 37) — 跨代工具链升级

> **文档目的**：承接 Phase D 的 D.5（targetSdk 34→35），将 `targetSdk` 进一步拉到 **Android 17（API 37）**，并固化 OneDrive / Bitwarden 同步入口图标区分的修复。
> 本计划属**重点改动**，按项目规范 #5「先计划、确认后再改」推进；按规范 #6 上传至 GitHub `/doc` 供其他 agent 接力。
>
> **创建时间**：2026-08-06 ｜ **最后更新**：2026-08-06
> **状态**：方案 A（targetSdk 37 / API 37）已由你确认（"兼容安卓17"）；工具链跨代级联细节已澄清；**待你确认执行批次后开干**
> **仓库**：https://github.com/Chaniug/bastion ｜ dev / main 均位于 `95aa030b`
> **真机测试**：荣耀 Android 17（≈ API 37）

---

## 0. 当前状态（已核实，修正旧文档两处错误）

- `dev` / `main` 均位于 **`95aa030b`**（图标修复已合并 main，preview APK 已发布，tag `preview`）。
- `compileSdk` / `targetSdk` = **35**（D.5 已落地，**且已在 main 上**，旧文档"尚未合并 main"说法已过时）。
- **图标 bug 已修复，方式为「区分的 Material 图标」而非品牌矢量图**：因品牌矢量图 + `Painter`/`painterResource` 方案导致 CI 编译失败且 CI 日志在沙箱内不可读（见 §7 诊断通道），已回退为 `Icons.Default.CloudUpload`（OneDrive）与 `Icons.Default.Lock`（Bitwarden）区分。旧文档"已按品牌矢量图完成"说法**错误**，以此版为准。
  - 位置：`Bastion/app/src/main/java/com/bastion/app/ui/screens/SyncBackupScreen.kt`（`SyncBackupItem.icon: ImageVector`，L243/268；调用处 L129/L143）。
- 本计划**只改工具链 + Manifest + CI**，图标代码已落位，不重复改动。

---

## 1. 关键约束：targetSdk 37 ⇒ compileSdk ≥ 37 ⇒ 必然跨代升级 AGP 9 / Gradle 9

> 你已确认走方案 A（API 37），即**推翻 Phase D「不做 AGP 9 / Gradle 9」红线**。版本矩阵按官方 `developer.android.com/build/releases/about-agp`（2026-07-14 更新）核实。

| Android API | 最低 AGP | 最低 Gradle |
|-------------|----------|-------------|
| **37（Android 17）** | **9.1.1** | **9.3.1** |
| 36（Android 16） | 8.9.1 | 8.11.1 |
| 35（Android 15，当前） | 8.6.0 | 8.9（当前 8.9） |

- `targetSdk = 37` ⇒ `compileSdk ≥ 37`（AGP 要求 `target ≤ compile`；Manifest 的 `tools:targetApi` 也需对应 `android.jar`）。
- **API 37 强制 AGP ≥ 9.1.1，而 AGP 9.1.1 强制 Gradle ≥ 9.3.1** ⇒ 必然进入 AGP 9.x + Gradle 9.x。

### 1.1 连带版本级联（Gradle 9 / AGP 9 的硬约束）

Gradle 9.x + AGP 9.x 不再是"小版本升级"，会级联拉动整条工具链：

| 组件 | 当前 | 目标（批次 1） | 必要性 |
|------|------|----------------|--------|
| **AGP** | 8.7.3 | **9.1.1** | 支持 API 37 的最低版本（官方矩阵） |
| **Gradle** | 8.9 | **9.3.1** | AGP 9.1.1 的最低 Gradle（改 `gradle-wrapper.properties`） |
| **Kotlin** | 2.0.21 | **2.2.x**（建议 2.2.21） | Gradle 9 / AGP 9 时代应配 Kotlin 2.1+；2.2 与 mockk 1.14 metadata 对齐 |
| **KSP** | 2.0.21-1.0.25 | **2.2.x-1.0.y**（与 Kotlin 精确对齐） | KSP 必须严格匹配 Kotlin 主版本，否则 Room 编译器报错 |
| **mockk** | 1.13.17 | **1.14.x**（建议 1.14.4+） | 1.13.17 由 Kotlin 2.0 编译（metadata 2.0）；1.14.x 由 Kotlin 2.2 编译（metadata 2.2），与 Kotlin 2.2 一致；旧 `libs.versions.toml` 注释"勿升 1.14"的前提（Kotlin 2.0.21）已不成立，需改写注释 |
| **Compose BOM** | 2026.03.00 | 暂保持，按需升 | 与 Kotlin 2.2 / AGP 9.1.1 兼容性待 CI 验证 |
| **kotpass** | 0.10.0 | 暂保持，坏了再升 | 注释称"兼容 Kotlin 1.9"，2.2 下大概率仍编译（向后兼容），CI 验证 |
| `app/build.gradle` 内 `configurations.configureEach` 强锁 | kotlin-bom/stdlib **2.1.20** | **2.2.x**（与 Kotlin 一致） | ⚠️ 必须同步改，否则 Kotlin 插件 2.2 与强锁的 2.1.20 标准库错位，报 "runtime library version is older than compiler" 或直接编译失败 |

> **执行时版本核对**：Kotlin 2.2 的最新 patch 与「对应 KSP 版本」需在动手前查 Kotlin / KSP 发布页确认（本沙箱 `dl.google.com` 被拦无法本地编，全部依赖 CI 验证，见 §5/§7）。建议锁定一组已互相验证的版本（如 Kotlin 2.2.21 + KSP `2.2.21-1.0.30` 类），不要混搭跨 patch。

### 1.2 CI 工作流缺口（必须补）

`.github/workflows/main.yml` **当前没有 `sdkmanager` 安装步骤**，依赖 runner 预装 SDK。升 `compileSdk 37` 后需**新增一步**装 `platforms;android-37` + 对应 `build-tools`（runner 镜像大概率不自带 API 37）。新增位置：在 `Set up JDK 17` / `Set up Gradle` 之后、`Build Debug APK` 之前。

```yaml
      - name: Install Android SDK platform 37
        run: |
          yes | "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null || true
          "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" \
            "platforms;android-37" "build-tools;35.0.0"
        env:
          ANDROID_SDK_ROOT: /usr/local/lib/android/sdk
```

> `build-tools` 版本取 runner 实际可用的最新档（API 37 时代多为 `35.0.0` 或 `37.0.0`），以 `sdkmanager --list` 实际为准；AGP 9.1.1 会自动选用。若 `cmdline-tools/latest` 路径不符，按 runner 实际布局调整。

---

## 2. 图标修复状态（已落位，不在本计划改动范围）

- 已用区分的 Material 图标修复撞图：`CloudUpload`（OneDrive）/ `Lock`（Bitwarden），合并 main（`95aa030b`）。
- 品牌矢量图方案（`ic_onedrive.xml` / `ic_bitwarden.xml`）曾建又被 `git rm`，因 `Painter`/`painterResource` 组合在 CI 编译失败且日志不可读。若日后要在**可诊断环境**重做品牌图，需单独排期，不在本计划内。

---

## 3. Android 16（API 36）行为变更适配清单

（target 37 一并覆盖；逐项对照官方 `behavior-changes-16`，对涉及本 app 的条目做适配。）

- **Live Updates / 进行中通知规范**：OTP 通知、同步进度通知需符合 Android 16 新的「持续活动 / 进度」通知要求，避免被系统降权或折叠。→ 核查 `AutofillOtpNotificationService` / `NotificationValidatorService` / 同步 Worker 通知样式（见 §4 临时权限与 FGS 类型）。
- **Predictive back 默认开启**：Manifest 已 `android:enableOnBackInvokedCallback="false"`（走旧 `onBackPressed`），Compose `BackHandler` 不受影响；重点查自定义返回拦截。
- **大屏默认可 Resize**：与 17 同方向，本 app 为 Compose 响应式布局，影响很小（§4 已核查无 `requestedOrientation` / `resizeableActivity`）。
- **Health Connect 等隐私**：本 app 未用，跳过。

---

## 4. Android 17（API 37）行为变更适配清单

（target 37 生效；逐项对照官方 `behavior-changes-17`。）

- **大屏适配强化**：大屏设备可能忽略 `screenOrientation` / `setRequestedOrientation()` / `resizeableActivity=false` / `min/maxAspectRatio`。
  - **已核查**：`AndroidManifest.xml` 与全部 `app/src/main` 代码**均无** `requestedOrientation` / `resizeableActivity` 声明或调用 → 影响很小。
- **临时 / 临时权限模型（ephemeral permissions）**：target 17 下，部分敏感操作（如本地回环 / 网络 socket、媒体/剪贴板访问）需显式权限或临时授权，缺失会导致运行期失败。
  - **需核查**：本地 KeePass 文件访问、WebDAV（`sardine-android`）、autofill 本地通信是否触碰受新模型约束的 API；受影响则补权限或改用兼容 API。
- **16KB 页面大小**：target 35 已要求；native 库需 16KB 兼容。本 app 含 **`argon2kt` 原生 `.so`**（Bitwarden Argon2id KDF），`scrypt`/`zxing` 为纯 Java/已验证。→ 真机验证 Argon2 解密（KeePass/Bitwarden 导入场景）。
- **Bubbles 浮窗新窗口模式**：本 app 未用气泡，跳过。
- **私密空间 / 设备保护等安全特性**：如用到则适配；当前 autofill/凭据提供器走标准 API，逐项核查。

---

## 5. 批次与 CI 验证策略

> 全部验证依赖 GitHub Actions（沙箱无 Android SDK、`dl.google.com` 被拦，无法本地编译，符合规范 #3/#4）。
> 每批独立提交推 `dev` 跑 CI：`Build Debug APK`（编译闸门）+ `Enforce unit test failure baseline`（`BASELINE_FAILURES: "0"`）。

| 批次 | 内容 | 提交/验证 |
|------|------|-----------|
| **批次 1（工具链 + Manifest + CI）** | `libs.versions.toml`：agp 8.7.3→9.1.1、kotlin 2.0.21→2.2.x、ksp→对应 2.2.x、mockk 1.13.17→1.14.x、改写 mockk 注释；`gradle-wrapper.properties`：8.9→9.3.1；`app/build.gradle`：compileSdk/targetSdk 35→37 + `configurations.configureEach` 强锁 2.1.20→2.2.x；`AndroidManifest.xml` 4 处 `tools:targetApi="35"`→`"37"`；`main.yml` 新增装 `platforms;android-37` + build-tools | 编译闸门 + 单测基线 0。**若失败＝工具链问题**，回退该提交诊断（见 §7） |
| **批次 2（行为变更适配）** | 按 §3/§4 核查结果：OTP/同步通知对齐 Live Updates 规范；必要时补临时权限模型所需权限/API；核查 FGS 类型仍合规 | 编译闸门 + 单测基线 0 |
| **批次 3（真机）** | 你（荣耀 Android 17）按 §6 清单验证；仅真机能暴露的项（autofill/OTP/前台服务/16KB/大屏） | 真机通过即收口 |

- 批次 1 通过且 dev CI 绿 → 合 dev→main → 发 preview APK（tag `preview`）供真机。
- 批次 2/3 在 main 已含 37 的基础上迭代，仍走 dev→main 流程。

---

## 6. 真机验证清单（你：荣耀 Android 17）

- autofill 填充（普通站点 + 凭据提供器/Passkey）
- OTP 通知：**锁屏可见性**（Android 15 私密通知）+ **Android 16 Live Updates 规范**（不被折叠/降权）
- 前台服务类型（OTP / 同步，必须匹配 `foregroundServiceType` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`）
- KeePass / Bitwarden 同步（手动 + 后台）+ **Argon2 解密（验证 16KB 原生 .so）**
- 附件预览
- 大屏 resize / 折叠分屏（如有）
- 安装/覆盖安装（preview 与 stable 同签名）

---

## 7. 风险、回滚与诊断通道

- **跨代风险面最大在批次 1**：AGP 9 / Gradle 9 / Kotlin 2.2 / KSP / mockk 任一版本错配即编译失败。缓解：批次 1 尽量"纯版本号 + Manifest + CI"，不夹带主代码改动，便于定位。
- **`configurations.configureEach` 强锁**是隐藏雷：升 Kotlin 不升强锁版本必错，务必同步。
- **kotpass / argon2kt / scrypt** 在 Kotlin 2.2 下的兼容性靠 CI + 真机验证；坏了再升对应库。
- **回滚**：每批独立提交，失败用 `git revert <commit>` 或 `git reset --hard <prev>` 回退该批，重新诊断后重推。
- **CI 日志诊断通道（沙箱内受限）**：
  - 首选：`gh api repos/Chaniug/bastion/actions/runs/<id>/annotations` 读 `build_gate` 与 `Failed test` 注解（CI 已把关键报错打成 `::notice`/`::error` 注解）。
  - 次选：`gh run view --log`（本沙箱曾报 EOF，不稳定）。
  - 兜底：**请你在 GitHub Actions 页面把失败日志贴给我**，或在真机验证时反馈现象。
  - 网络：写权限依赖 `/etc/hosts` 把 `github.com`→`20.205.243.166`、`api.github.com`→`20.205.243.168`（真实直连 IP）；若推送失败，先查 hosts / 代理（规范 #7）。

---

## 8. 待你确认（执行授权）

方案 A（API 37 / 跨代升级）方向已定，开干前请确认：

1. **版本锁定**：接受本计划批次 1 的版本级联（AGP 9.1.1 + Gradle 9.3.1 + Kotlin 2.2.x + KSP 对应 + mockk 1.14.x），执行时按 §1.1 核对最新 patch 后锁定一组互相验证的版本。
2. **批次节奏**：按 §5 三批次推进（工具链 → 行为适配 → 真机），每批独立提交推 dev 跑 CI，绿了再合 main 发 preview。
3. **图标**：维持当前 Material 图标区分方案（不再重做品牌矢量图，除非你另指定）。

确认后即从**批次 1**开始：改 `libs.versions.toml` / `gradle-wrapper.properties` / `app/build.gradle` / `AndroidManifest.xml` / `main.yml` → 提交推 dev → 盯 CI → 绿则合 main 发 preview。
