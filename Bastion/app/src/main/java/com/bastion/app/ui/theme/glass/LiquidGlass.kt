package com.bastion.app.ui.theme.glass

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface

/**
 * 全局开关：液态玻璃是否启用。
 * 由 [com.bastion.app.ui.theme.BastionTheme] 从用户设置注入，
 * 各玻璃材质组件读取 [LocalLiquidGlass.current] 决定是否启用。
 */
val LocalLiquidGlass = compositionLocalOf { false }

/**
 * 背景捕获状态的占位容器。
 *
 * **当前实现（纯 Compose 降级版）**：不再录制离屏图层，此类仅作为 CompositionLocal
 * 的占位存在，保持 [com.bastion.app.ui.theme.BastionTheme] 与各玻璃组件的调用链不变。
 * 以后若找到在 Honor / 各厂商 GPU 驱动上稳定的真模糊方案，可在此恢复图层捕获逻辑。
 *
 * 历史背景：此前用 `GraphicsLayer.record` + `RenderEffect.createBlurEffect` 做真背景
 * 模糊，在 Honor（Android 17 / API36）上触发渲染线程 native 崩溃（Java try/catch 无法
 * 捕获），连续 3 版闪退。根因是嵌套图层录制 + RenderEffect 在部分厂商 GPU 驱动上
 * 不稳定。故先移除整条 native 路径，退化为纯 Compose 磨砂玻璃，100% 不崩。
 */
class GlassBackdropState

/**
 * [GlassBackdropState] 的 CompositionLocal。由 [com.bastion.app.ui.theme.BastionTheme]
 * 提供；当前为占位，玻璃组件不再从中读取图层。
 */
val LocalGlassBackdrop = compositionLocalOf { GlassBackdropState() }

/**
 * 保留给 [com.bastion.app.ui.theme.BastionTheme] 判断是否需要包裹 glazeSource。
 * 当前始终为 true（让 Theme.kt 的条件分支保持原样），但 glazeSource 本身已是 no-op。
 */
internal val GLASS_BLUR_SUPPORTED: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * 液态玻璃材质参数。
 *
 * 设计依据综合自三个成熟的 Android 玻璃态实现方案：
 *
 * 1. **Haze 风格（Chris Banes 的 RenderEffect 背景捕获）**：真背景模糊的核心技法，
 *    本实现曾采用此思路（自研、无外部依赖），但在 Honor 真机上触发 native 崩溃，
 *    故暂时降级为纯 Compose 方案。
 * 2. **Yang-Ya-Chao 玻璃态设计系统**：纯 Compose 实现，
 *    核心是 `1dp 顶部内高光线` + `1dp 渐变描边` + 低透明填充 + 外投影。
 * 3. **androidengineers Glassmorphism 指南**：推荐低透明填充 + 渐变描边 + 投影。
 *
 * 视觉构成（从底到顶，当前为纯 Compose 降级版，无真模糊）：
 * - 低透明 tint（半透明填充，模拟磨砂玻璃的通透感）
 * - 1dp 顶部内高光线（模拟玻璃上边缘的光反射，克制，不出现矩形带）
 * - 1dp 上亮下暗渐变描边（模拟光照衰减）
 * - 轻微外投影（建立与内容的深度分离）
 *
 * 此方案只使用 drawRect / verticalGradient / shadow，不涉及 GraphicsLayer 或
 * RenderEffect，不会触发 GPU 驱动 native 崩溃。
 */
data class LiquidGlassTokens(
    val containerColor: Color,     // 半透明 tint（极透明，让内容透出 + vibrancy）
    val innerHighlightColor: Color, // 顶部 1dp 内高光线颜色
    val borderTopColor: Color,      // 描边上端（较亮）
    val borderBotColor: Color,      // 描边下端（较暗/近透明）
    val elevation: Dp               // 投影高度
) {
    companion object {
        @Composable
        fun fromCurrent(isDark: Boolean): LiquidGlassTokens {
            val surface = MaterialTheme.colorScheme.surface
            return if (isDark) {
                LiquidGlassTokens(
                    // 深色模式：更透明的 tint + 较亮的内高光 + 白色渐变描边
                    containerColor = blendSurfaceWhite(surface, surfaceFactor = 0.78f, whiteFactor = 0.10f, alpha = 0.14f),
                    innerHighlightColor = Color.White.copy(alpha = 0.40f),
                    borderTopColor = Color.White.copy(alpha = 0.50f),
                    borderBotColor = Color.White.copy(alpha = 0.08f),
                    elevation = 6.dp
                )
            } else {
                LiquidGlassTokens(
                    // 浅色模式：稍不透明的 tint + 更亮的内高光 + 白→灰渐变描边
                    containerColor = blendSurfaceWhite(surface, surfaceFactor = 0.40f, whiteFactor = 0.60f, alpha = 0.18f),
                    innerHighlightColor = Color.White.copy(alpha = 0.55f),
                    borderTopColor = Color.White.copy(alpha = 0.70f),
                    borderBotColor = Color.Black.copy(alpha = 0.06f),
                    elevation = 3.dp
                )
            }
        }
    }
}

/**
 * 将 surface 与白色按比例混合，并设定整体不透明度。
 * 不使用 Color 的算术运算符，兼容各 Compose 版本。
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
 *
 * 这是 Yang-Ya-Chao 设计系统的核心技巧：高光只有 **1dp 高**（不是矩形带），
 * 描边是 **上亮下暗的渐变**（不是均匀实色）。两者共同营造「光照在玻璃边缘」的效果。
 */
private fun DrawScope.glassHighlights(tokens: LiquidGlassTokens) {
    // 1) 顶部内高光线：仅 1dp 高的细线，模拟玻璃上边缘的光反射
    drawRect(
        color = tokens.innerHighlightColor,
        topLeft = Offset.Zero,
        size = size.copy(height = 1.dp.toPx())
    )
    // 2) 渐变描边：上端较亮 → 下端近透明，模拟光照沿玻璃表面衰减
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(tokens.borderTopColor, tokens.borderBotColor)
        ),
        style = Stroke(width = 1.dp.toPx())
    )
}

// ============================================================
// 背景捕获：glazeSource（当前为 no-op，保留接口兼容）
// ============================================================

/**
 * 把修饰对象背后的内容录制进 [GlassBackdropState]（离屏图层），
 * 供液态玻璃组件模糊采样。
 *
 * **当前为 no-op 透传**：Honor 真机上 `GraphicsLayer.record` + `RenderEffect`
 * 触发 native 崩溃，故移除录制逻辑。此函数保留签名，仅透传内容，不产生任何
 * 离屏图层或 GPU 操作。以后恢复真模糊时在此接回。
 */
fun Modifier.glazeSource(state: GlassBackdropState): Modifier = this

// ============================================================
// 玻璃材质：liquidGlass（纯 Compose 降级版，无真模糊）
// ============================================================

/**
 * 把任意 [androidx.compose.foundation.layout.Box] / [Surface] 变成玻璃材质。
 *
 * **当前实现**：纯 Compose 磨砂玻璃 —— 半透明 tint + 1dp 顶部内高光 + 渐变描边 +
 * 外投影。不使用 GraphicsLayer / RenderEffect，不会触发 GPU 驱动 native 崩溃。
 *
 * 视觉上是通透的磨砂玻璃质感（无真背景模糊），在 Honor 及各厂商机型上 100% 稳定。
 * 以后找到安全的真模糊方案时，在此恢复模糊绘制逻辑。
 *
 * @param enabled 是否启用玻璃材质（false 时直接返回原 Modifier）
 * @param tokens 玻璃材质参数（颜色/高光/描边/投影）
 * @param shape 玻璃形状（用于裁剪与投影）
 * @param blurRadiusDp 模糊半径（当前降级版未使用，保留参数兼容调用方）
 */
@Composable
fun Modifier.liquidGlass(
    enabled: Boolean = LocalLiquidGlass.current,
    tokens: LiquidGlassTokens = LiquidGlassTokens.fromCurrent(isSystemInDarkTheme()),
    shape: Shape = RoundedCornerShape(0.dp),
    blurRadiusDp: Dp = 10.dp
): Modifier = if (!enabled) this else this
    .shadow(elevation = tokens.elevation, shape = shape, clip = false)
    .clip(shape)
    .then(LiquidGlassElement(tokens))

private data class LiquidGlassElement(
    val tokens: LiquidGlassTokens
) : ModifierNodeElement<LiquidGlassNode>() {
    override fun create(): LiquidGlassNode = LiquidGlassNode(tokens)
    override fun update(node: LiquidGlassNode) {
        node.tokens = tokens
    }
}

/**
 * 玻璃绘制节点（纯 Compose，仅 drawRect / 渐变，无 GPU 离屏操作）。
 */
private class LiquidGlassNode(
    tokens: LiquidGlassTokens
) : Modifier.Node(), DrawModifierNode {

    var tokens: LiquidGlassTokens = tokens

    override val shouldAutoInvalidate: Boolean = true

    override fun ContentDrawScope.draw() {
        // 1) 半透明 tint：磨砂玻璃的通透感（让背后内容透出 + 轻微 vibrancy）
        drawRect(color = tokens.containerColor)

        // 2) 原内容（透明背景 + 子元素）画在 tint 之上
        drawContent()

        // 3) 顶部内高光线 + 渐变描边（光照边缘效果）
        glassHighlights(tokens)
    }
}

/**
 * 玻璃容器。通过半透明 + 微妙高光还原玻璃质感。
 *
 * 调用方需通过 [modifier] 或父布局为该容器提供尺寸。
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
