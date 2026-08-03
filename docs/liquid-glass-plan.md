# 液态玻璃（Liquid Glass）方案 E：条件真模糊 + 厂商降级

> 状态：🟡 实现中（CI 待验证）
> 分支：`dev`
> 关联提交：`47791f28`（降级版）→ 本方案恢复真模糊

## 一、背景与根因

### 1.1 问题
`LiquidGlass.kt` 此前用 `GraphicsLayer.record` + `RenderEffect.createBlurEffect` 做真背景模糊，
在荣耀手机（MagicOS，系统显示"安卓 17"，底层 API 等级约 36）上**连续 3 版触发渲染线程 native 崩溃**
（SIGSEGV/SIGABRT，Java try/catch 无法捕获）。

`47791f28` 把整条 native 路径移除，退化为纯 Compose 磨砂玻璃（半透明 tint + 高光 + 描边），
100% 不崩，但**没有真背景模糊**，视觉上只是带高光的半透明色块。

### 1.2 根因
- 荣耀 MagicOS 定制的 GPU 驱动（Mali/Adreno 在 MagicOS 上的定制版）在
  `RenderEffect.createBlurEffect` 走 Skia RuntimeEffect 路径时存在不稳定。
- Google Issue Tracker（[issuetracker.google.com/issues/241546169](https://issuetracker.google.com/issues/241546169)）
  记录了 `graphicsLayer { renderEffect = BlurEffect(...) }` 在部分设备 native 崩溃。
- Haze 库（dev.chrisbanes.haze）底层同样无法捕获该 native 崩溃，只能规避。

### 1.3 关键结论
**升 compileSdk 到 36 对治玻璃崩溃无帮助**——崩溃是 GPU 驱动问题，不是 SDK 版本问题。
compileSdk 36 需要先升 AGP 8.7.3 → 8.9.1+（链式升级有风险），但对玻璃崩溃无修复作用。
故 SDK 升级与玻璃修复**解耦**，本次只做玻璃修复。

## 二、方案 E：条件真模糊 + 厂商降级

### 2.1 设备分流

| 设备类型 | 判定条件 | 渲染路径 | 效果 |
|---------|---------|---------|------|
| **真模糊设备** | API ≥ 31 **且** 厂商不在黑名单 | `GraphicsLayer.record` + `BlurEffect` | 真背景模糊 + tint + 高光 + 描边 |
| **降级设备** | 荣耀/华为（黑名单）或 API < 31 | 纯 Compose 磨砂 | 半透明 tint + 高光 + 描边（无真模糊） |

### 2.2 厂商黑名单

```kotlin
private val NATIVE_BLUR_BLOCKLIST: Set<String> = setOf("honor", "huawei")
```

匹配 `Build.MANUFACTURER`（大小写不敏感）。荣耀/华为即便系统版本够高也降级，
因为其 GPU 驱动在 RenderEffect 路径上会 native 崩溃。

### 2.3 架构

```
BastionTheme
  ├─ rememberGraphicsLayer() → 注入 GlassBackdropState.backdropLayer
  ├─ glazeSource(backdropState)  ← 录制内容层到 GraphicsLayer
  └─ CompositionLocalProvider(LocalGlassBackdrop provides backdropState)
       └─ 内容树
            └─ liquidGlass()
                 ├─ 真模糊设备：LiquidGlassBlurNode
│   ├─ backdrop.backdropLayer.renderEffect = BlurEffect(...)
│   ├─ drawLayer(layer)  ← 模糊背景
│   ├─ drawRect(containerColor)  ← tint
│   ├─ drawContent()  ← 原内容
│   └─ glassHighlights()  ← 高光+描边
                 └─ 降级设备：LiquidGlassFallbackNode
                      ├─ drawRect(containerColor)
                      ├─ drawContent()
                      └─ glassHighlights()
```

### 2.4 关键 API

| API | 包名 | 说明 |
|-----|------|------|
| `GraphicsLayer` | `androidx.compose.ui.graphics.layer` | 离屏图层（Compose 1.7+ stable） |
| `rememberGraphicsLayer()` | `androidx.compose.ui.graphics` | 自动管理 layer 生命周期 |
| `GraphicsLayer.record(size) { }` | — | 录制内容到 layer |
| `drawLayer(layer)` | `androidx.compose.ui.graphics.drawLayer` | 把 layer 画到 DrawScope |
| `layer.renderEffect = BlurEffect(...)` | `androidx.compose.ui.graphics` | Compose 的 BlurEffect（非 Android framework） |
| `BlurEffect(radiusX, radiusY, TileMode)` | `androidx.compose.ui.graphics` | edgeTreatment 用 `TileMode.Decal` |

### 2.5 安全措施
- 模糊半径 clamp 到 RenderEffect 安全上限 **25px**，避免极端半径触发 GPU 问题
- `GlassBackdropState.backdropLayer` 为 `@Volatile`，跨 modifier node 安全读取
- 降级路径完全不涉及 `GraphicsLayer` / `RenderEffect`，零 GPU 离屏操作

## 三、SDK 升级（独立后续任务）

| 配置 | 当前 | 目标 | 前置条件 | 状态 |
|------|------|------|---------|------|
| `compileSdk` | 35 | 36 | AGP ≥ 8.9.1 | ⏸ 暂缓 |
| `targetSdk` | 34 | 36 | AGP ≥ 8.9.1 | ⏸ 暂缓 |
| `minSdk` | 26 | 26 | — | 不变 |
| AGP | 8.7.3 | 8.9.1+ | Gradle 8.11.1+ | ⏸ 暂缓 |

**暂缓理由**：升 AGP 是链式升级（AGP→Gradle→可能波及 KSP/Kotlin），有回归风险，
但对玻璃崩溃无修复作用。玻璃修复完成后可单独立项升级 SDK。

## 四、真机验证清单

| 设备 | 预期行为 | 验证点 |
|------|---------|--------|
| 荣耀（安卓 17 / API 36） | **降级**为纯 Compose 磨砂 | 不闪退；半透明+高光可见；无真模糊 |
| 非荣耀 API 31+ | **真背景模糊** | 顶栏/底部导航/弹层背后内容真模糊 |
| API < 31 | **降级**为纯 Compose 磨砂 | 不闪退；无真模糊（API 限制） |

验证步骤：
1. 设置中开启液态玻璃
2. 滑动列表，观察顶栏/底部导航背后是否有真模糊（非荣耀设备）
3. 荣耀设备确认不闪退，半透明+高光效果正常
4. 开/关玻璃切换无崩溃

## 五、接口兼容性

`liquidGlass` / `LiquidGlassSurface` / `glazeSource` 签名保持不变，
调用方（5 处：SimpleMainScreen / ExpressiveTopBar ×2 / DraggableBottomNav / BottomSheetStability）零改动。

`blurRadiusDp: Dp` 参数改为 `blurRadiusPx: Float`（内部使用，所有调用方均用默认值，无外部影响）。

## 六、接力说明

后续 agent 如需：
- **恢复荣耀真模糊**：等荣耀修复 GPU 驱动后，从 `NATIVE_BLUR_BLOCKLIST` 移除 "honor" 即可
- **升级 SDK**：按 §三 表格，先升 AGP 到 8.9.1 + Gradle 8.11.1，再升 compileSdk/targetSdk
- **引入 Haze 库**：如需更成熟的降级逻辑，可引 `dev.chrisbanes.haze`，但注意此前 KDoc 禁止该字面量
