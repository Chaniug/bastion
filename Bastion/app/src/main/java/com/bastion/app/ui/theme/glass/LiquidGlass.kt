package com.bastion.app.ui.theme.glass

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawLayer
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface

/**
 * 全局开关：液态玻璃是否启用。
 * 由 [com.bastion.app.ui.theme.BastionTheme] 从用户设置注入，
 * 各玻璃材质组件读取 [LocalLiquidGlass.current] 决定是否启用。
 */
val LocalLiquidGlass = compositionLocalOf { false }

// ============================================================
// 设备能力探测：真模糊支持判定 + 厂商黑名单
// ============================================================

/**
 * 已知在 `RenderEffect` + `GraphicsLayer` 组合下会触发渲染线程 native 崩溃
 *（SIGSEGV/SIGABRT，Java try/catch 无法捕获）的厂商 GPU 驱动黑名单。
 *
 * 根因：荣耀 MagicOS（基于 Android 12-16）的定制 GPU 驱动在
 * `RenderEffect.createBlurEffect` 走 Skia RuntimeEffect 路径时存在不稳定，
 * Google Issue Tracker (issuetracker.google.com/issues/241546169) 亦记录同类问题。
 * Haze 库底层同样无法捕获该 native 崩溃，只能规避。
 *
 * 黑名单内的设备一律走纯 Compose 磨砂降级路径（[LiquidGlassFallbackNode]），
 * 100% 不崩。黑名单外的设备在 API31+ 走真背景模糊（[LiquidGlassBlurNode]）。
 */
private val NATIVE_BLUR_BLOCKLIST: Set<String> = setOf(
    "honor",
    "huawei"
)

/**
 * 设备是否在 native 模糊崩溃黑名单内。
 * 匹配 [Build.MANUFACTURER]（大小写不敏感，去空白）。
 */
private val isBlocklistedManufacturer: Boolean by lazy {
    val mfr = Build.MANUFACTURER?.trim()?.lowercase().orEmpty()
    NATIVE_BLUR_BLOCKLIST.any { mfr.contains(it) }
}

/**
 * 设备是否真正支持安全的真背景模糊。
 *
 * 综合判定：API ≥ 31（RenderEffect 可用）**且** 厂商不在黑名单内。
 * 荣耀/华为即便系统版本够高也降级，因为其 GPU 驱动在 RenderEffect 路径上会 native 崩溃。
 */
val GLASS_BLUR_SUPPORTED: Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !isBlocklistedManufacturer

// ============================================================
// 背景捕获：GlassBackdropState + glazeSource
// ============================================================

/**
 * 背景捕获状态：持有内容层的 [GraphicsLayer]，供玻璃组件采样并模糊"背后内容"。
 *
 * - **真模糊设备**（[GLASS_BLUR_SUPPORTED] = true）：[BastionTheme][com.bastion.app.ui.theme.BastionTheme]
 *   用 `rememberGraphicsLayer()` 创建 layer 并存入此类，[glazeSource] 把内容录制到该 layer，
 *   玻璃组件用 `BlurEffect` 对该 layer 做模糊采样。
 * - **降级设备**（荣耀/华为/API<31）：[backdropLayer] 为 null，[glazeSource] 为 no-op 透传，
 *   玻璃组件走纯 Compose 磨砂（[LiquidGlassFallbackNode]）。
 */
class GlassBackdropState {
    /**
     * 由 [BastionTheme][com.bastion.app.ui.theme.BastionTheme] 在组合时注入的背景图层。
     * null 表示降级模式（不录制背景，玻璃走磨砂降级）。
     */
    @Volatile
    internal var backdropLayer: GraphicsLayer? = null
}

/**
 * [GlassBackdropState] 的 CompositionLocal。由 [com.bastion.app.ui.theme.BastionTheme]
 * 提供；玻璃组件从中读取背景图层（真模糊设备）。
 */
val LocalGlassBackdrop = compositionLocalOf { GlassBackdropState() }

/**
 * 把修饰对象背后的内容录制进 [state.backdropLayer]（离屏图层），供液态玻璃组件模糊采样。
 *
 * - **真模糊设备**：用 `drawWithContent` + `GraphicsLayer.record` 录制内容层，
 *   随后仍正常绘制内容到屏幕（录制只产生离屏副本，不替代屏幕绘制）。
 * - **降级设备**：no-op 透传，不产生任何离屏图层或 GPU 操作。
 *
 * 仅在 [com.bastion.app.ui.theme.BastionTheme] 包裹整棵内容树时调用一次。
 */
fun Modifier.glazeSource(state: GlassBackdropState): Modifier {
    if (!GLASS_BLUR_SUPPORTED) return this
    return this.drawWithContent {
        val layer = state.backdropLayer
        if (layer != null) {
            layer.record(
                size = IntSize(size.width.toInt().coerceAtLeast(0), size.height.toInt().coerceAtLeast(0))
            ) {
                this@drawWithContent.drawContent()
            }
        }
        // 录制后仍把内容正常画到屏幕
        drawContent()
    }
}

// ============================================================
// 玻璃材质参数
// ============================================================

/**
 * 液态玻璃材质参数。
 *
 * 设计依据综合自三个成熟的 Android 玻璃态实现方案：
 *
 * 1. **Haze 风格（Chris Banes 的 RenderEffect 背景捕获）**：真背景模糊的核心技法，
 *    本实现在非荣耀设备上采用此思路（自研、无外部依赖）。
 * 2. **Yang-Ya-Chao 玻璃态设计系统**：纯 Compose 降级实现，
 *    核心是 `1dp 顶部内高光线` + `1dp 渐变描边` + 低透明填充 + 外投影。
 * 3. **androidengineers Glassmorphism 指南**：低透明填充 + 渐变描边 + 投影。
 *
 * 视觉构成（从底到顶）：
 * - **真模糊设备**：背景层模糊采样 + 半透明 tint + 1dp 顶部内高光 + 渐变描边 + 外投影
 * - **降级设备**：半透明 tint + 1dp 顶部内高光 + 渐变描边 + 外投影（无真模糊）
 */
data class LiquidGlassTokens(
    val containerColor: Color,      // 半透明 tint（极透明，让内容透出 + vibrancy）
    val innerHighlightColor: Color, // 顶部 1dp 内高光线颜色
    val borderTopColor: Color,      // 描边上端（较亮）
    val borderBotColor: Color,      // 描边下端（较暗/近透明）
    val elevation: Dp,              // 投影高度
    val blurRadiusPx: Float         // 真模糊半径（像素，真模糊设备用；降级设备忽略）
) {
    companion object {
        @Composable
        fun fromCurrent(isDark: Boolean): LiquidGlassTokens {
            val surface = MaterialTheme.colorScheme.surface
            return if (isDark) {
                LiquidGlassTokens(
                    containerColor = blendSurfaceWhite(surface, surfaceFactor = 0.78f, whiteFactor = 0.10f, alpha = 0.14f),
                    innerHighlightColor = Color.White.copy(alpha = 0.40f),
                    borderTopColor = Color.White.copy(alpha = 0.50f),
                    borderBotColor = Color.White.copy(alpha = 0.08f),
                    elevation = 6.dp,
                    blurRadiusPx = 24f
                )
            } else {
                LiquidGlassTokens(
                    containerColor = blendSurfaceWhite(surface, surfaceFactor = 0.40f, whiteFactor = 0.60f, alpha = 0.18f),
                    innerHighlightColor = Color.White.copy(alpha = 0.55f),
                    borderTopColor = Color.White.copy(alpha = 0.70f),
                    borderBotColor = Color.Black.copy(alpha = 0.06f),
                    elevation = 3.dp,
                    blurRadiusPx = 20f
                )
            }
        }
    }
}

/**
 * 将 surface 与白色按比例混合，并设定整体不透明度。
 */
private fun blendSurfaceWhite(
    surface: Color,
    surfaceFactor: Float,
    whiteFactor: Float,
    alpha: Float
): Color = Color(
    red = surface.red * surfaceFactor + whiteFactor,
    green = surface.green * surfaceFactor + whiteFactor,
    blue = surface.blue * surfaceFactor + whiteFactor,
    alpha = alpha
)

/**
 * 在 [DrawScope] 内绘制玻璃的两层光学叠加：1dp 顶部内高光线 + 渐变描边。
 */
private fun DrawScope.glassHighlights(tokens: LiquidGlassTokens) {
    // 1) 顶部内高光线：仅 1dp 高的细线
    drawRect(
        color = tokens.innerHighlightColor,
        topLeft = Offset.Zero,
        size = size.copy(height = 1.dp.toPx())
    )
    // 2) 渐变描边：上端较亮 → 下端近透明
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(tokens.borderTopColor, tokens.borderBotColor)
        ),
        style = Stroke(width = 1.dp.toPx())
    )
}

// ============================================================
// 玻璃材质：liquidGlass
// ============================================================

/**
 * 把任意 Box / Surface 变成玻璃材质。
 *
 * - **真模糊设备**（[GLASS_BLUR_SUPPORTED] = true）：从 [LocalGlassBackdrop] 取背景图层，
 *   用 `BlurEffect` 模糊后绘制，再叠加 tint + 高光 + 描边。
 * - **降级设备**（荣耀/华为/API<31）：纯 Compose 磨砂 —— 半透明 tint + 1dp 顶部内高光 +
 *   渐变描边 + 外投影。不使用 GraphicsLayer / RenderEffect，100% 不崩。
 *
 * @param enabled 是否启用玻璃材质（false 时直接返回原 Modifier）
 * @param tokens 玻璃材质参数
 * @param shape 玻璃形状（用于裁剪与投影）
 * @param blurRadiusPx 模糊半径（像素，真模糊设备用；降级设备忽略）
 */
@Composable
fun Modifier.liquidGlass(
    enabled: Boolean = LocalLiquidGlass.current,
    tokens: LiquidGlassTokens = LiquidGlassTokens.fromCurrent(isSystemInDarkTheme()),
    shape: Shape = RoundedCornerShape(0.dp),
    blurRadiusPx: Float = tokens.blurRadiusPx
): Modifier {
    if (!enabled) return this
    val useRealBlur = GLASS_BLUR_SUPPORTED
    val backdrop = LocalGlassBackdrop.current
    return this
        .shadow(elevation = tokens.elevation, shape = shape, clip = false)
        .clip(shape)
        .then(
            if (useRealBlur) {
                LiquidGlassBlurElement(tokens, backdrop, blurRadiusPx)
            } else {
                LiquidGlassFallbackElement(tokens)
            }
        )
}

/**
 * 真模糊玻璃节点：用 BlurEffect 模糊背景层 + tint + 高光 + 描边。
 * 仅在 [GLASS_BLUR_SUPPORTED] = true 的设备上创建。
 */
private data class LiquidGlassBlurElement(
    val tokens: LiquidGlassTokens,
    val backdrop: GlassBackdropState,
    val blurRadiusPx: Float
) : ModifierNodeElement<LiquidGlassBlurNode>() {
    override fun create(): LiquidGlassBlurNode = LiquidGlassBlurNode(tokens, backdrop, blurRadiusPx)
    override fun update(node: LiquidGlassBlurNode) {
        node.tokens = tokens
        node.backdrop = backdrop
        node.blurRadiusPx = blurRadiusPx
    }
}

private class LiquidGlassBlurNode(
    var tokens: LiquidGlassTokens,
    var backdrop: GlassBackdropState,
    var blurRadiusPx: Float
) : Modifier.Node(), DrawModifierNode {

    override val shouldAutoInvalidate: Boolean = true

    override fun ContentDrawScope.draw() {
        val layer = backdrop.backdropLayer
        if (layer != null) {
            // 真背景模糊：对录制的背景层施加 BlurEffect 后绘制
            // clamp 到 RenderEffect 安全上限（25px），避免极端半径触发 GPU 问题
            val safeRadius = blurRadiusPx.coerceAtMost(25f).coerceAtLeast(1f)
            layer.renderEffect = BlurEffect(safeRadius, safeRadius, TileMode.Decal)
            drawLayer(layer)
        }
        // 1) 半透明 tint
        drawRect(color = tokens.containerColor)
        // 2) 原内容
        drawContent()
        // 3) 顶部内高光 + 渐变描边
        glassHighlights(tokens)
    }
}

/**
 * 降级玻璃节点：纯 Compose 磨砂（荣耀/华为/API<31），无 GPU 离屏操作。
 */
private data class LiquidGlassFallbackElement(
    val tokens: LiquidGlassTokens
) : ModifierNodeElement<LiquidGlassFallbackNode>() {
    override fun create(): LiquidGlassFallbackNode = LiquidGlassFallbackNode(tokens)
    override fun update(node: LiquidGlassFallbackNode) {
        node.tokens = tokens
    }
}

private class LiquidGlassFallbackNode(
    var tokens: LiquidGlassTokens
) : Modifier.Node(), DrawModifierNode {

    override val shouldAutoInvalidate: Boolean = true

    override fun ContentDrawScope.draw() {
        // 降级路径：半透明 tint + 内容 + 高光描边（无真模糊）
        drawRect(color = tokens.containerColor)
        drawContent()
        glassHighlights(tokens)
    }
}

/**
 * 玻璃容器。通过半透明 + 微妙高光还原玻璃质感。
 */
@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(0.dp),
    enabled: Boolean = LocalLiquidGlass.current,
    tokens: LiquidGlassTokens = LiquidGlassTokens.fromCurrent(isSystemInDarkTheme()),
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = if (enabled) Color.Transparent else MaterialTheme.colorScheme.surface
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .liquidGlass(enabled = enabled, tokens = tokens, shape = shape)
        ) {
            content()
        }
    }
}
