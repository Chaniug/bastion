package com.bastion.app.ui.theme.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 全局开关：液态玻璃是否启用。
 * 由 [com.bastion.app.ui.theme.BastionTheme] 从用户设置注入，
 * 各玻璃材质组件读取 [LocalLiquidGlass.current] 决定是否启用。
 */
val LocalLiquidGlass = compositionLocalOf { false }

/**
 * 液态玻璃材质参数。
 *
 * 设计依据综合自三个成熟的 Android 玻璃态实现方案：
 *
 * 1. **Haze 库 / sinasamaki**（Chris Banes）：用 [dev.chrisbanes.haze] 实现真 backdrop blur，
 *    配合 `blurRadius=30dp` + `tint=Black(0.2f)` + `Hairline 渐变描边`。
 *    参见 https://www.sinasamaki.com/glassmorphic-bottom-navigation-in-jetpack-compose/
 *
 * 2. **Yang-Ya-Chao 玻璃态设计系统**：纯 Compose 实现，
 *    核心是 `1dp 顶部内高光线` + `1dp 渐变描边` + `alpha 12~18% 填充` + 外投影。
 *    参见 https://github.com/Yang-Ya-Chao/android-design-system-skills/blob/master/glassmorphism.md
 *
 * 3. **androidengineers Glassmorphism 指南**：推荐 `alpha=0.15 默认填充` +
 *    `border alpha 0.1~0.4` + `elevation=8dp`。
 *    参见 https://androidengineers.substack.com/p/creating-stunning-glassmorphism-effects
 *
 * 本实现采用**纯 Compose 方案**（方案 2+3 的融合），无需额外依赖。
 * 如需真 backdrop blur 可后续引入 Haze 库升级为方案 1。
 *
 * 视觉构成（从底到顶）：
 * - 半透明填充（alpha 12~22%，背后内容清晰透出）
 * - 1dp 顶部内高光线（模拟玻璃上边缘的光反射）
 * - 1dp 渐变描边（上亮下暗，模拟光照衰减）
 * - 轻微外投影（建立与内容的深度分离）
 */
data class LiquidGlassTokens(
    val containerColor: Color,     // 半透明填充（极透明，让内容透出）
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
                    // 深色模式：更透明的填充 + 较亮的内高光 + 白色渐变描边
                    containerColor = blendSurfaceWhite(surface, surfaceFactor = 0.78f, whiteFactor = 0.10f, alpha = 0.18f),
                    innerHighlightColor = Color.White.copy(alpha = 0.45f),
                    borderTopColor = Color.White.copy(alpha = 0.55f),
                    borderBotColor = Color.White.copy(alpha = 0.10f),
                    elevation = 6.dp
                )
            } else {
                LiquidGlassTokens(
                    // 浅色模式：稍不透明的填充 + 更亮的内高光 + 白→灰渐变描边
                    containerColor = blendSurfaceWhite(surface, surfaceFactor = 0.40f, whiteFactor = 0.60f, alpha = 0.22f),
                    innerHighlightColor = Color.White.copy(alpha = 0.50f),
                    borderTopColor = Color.White.copy(alpha = 0.75f),
                    borderBotColor = Color.Black.copy(alpha = 0.08f),
                    elevation = 3.dp
                )
            }
        }
    }
}

/**
 * 将 surface 与白色按比例混合（白色各通道 = 1f），并设定整体不透明度。
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

/**
 * 把任意 [androidx.compose.foundation.layout.Box] / [Surface] 变成玻璃材质。
 *
 * 视觉效果（对齐 Haze / Yang-Ya-Chao / androidengineers 三大成熟方案）：
 * - 极透明填充（alpha 12~22%），背后内容清晰透出
 * - 1dp 顶部内高光线（不是矩形带）
 * - 1dp 上亮下暗渐变描边（不是实色）
 * - 轻微外投影
 *
 * 兼容全 API 等级；不模糊背后真实内容（Compose 层限制）。
 * 如需真 backdrop blur，可引入 [dev.chrisbanes.haze:haze-jetpack-compose] 升级。
 */
@Composable
fun Modifier.liquidGlass(
    enabled: Boolean = LocalLiquidGlass.current,
    tokens: LiquidGlassTokens = LiquidGlassTokens.fromCurrent(isSystemInDarkTheme()),
    shape: Shape = RoundedCornerShape(0.dp)
): Modifier = if (!enabled) this else this
    .shadow(elevation = tokens.elevation, shape = shape, clip = false)
    .background(tokens.containerColor, shape)
    .clip(shape)
    .drawWithContent {
        drawContent()
        glassHighlights(tokens)
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
    if (!enabled) {
        Surface(modifier = modifier, shape = shape, color = MaterialTheme.colorScheme.surface) {
            content()
        }
        return
    }
    Surface(
        modifier = modifier,
        shape = shape,
        color = Color.Transparent,
        shadowElevation = tokens.elevation,
        tonalElevation = 0.dp
    ) {
        BoxWithGlass(content = content, shape = shape, tokens = tokens)
    }
}

@Composable
private fun BoxWithGlass(
    content: @Composable () -> Unit,
    shape: Shape,
    tokens: LiquidGlassTokens
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .background(tokens.containerColor, shape)
            .drawWithContent {
                drawContent()
                glassHighlights(tokens)
            }
    ) {
        content()
    }
}
