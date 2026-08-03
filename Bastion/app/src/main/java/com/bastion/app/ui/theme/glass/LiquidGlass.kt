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
 * 设计依据：Apple HIG「Materials / Liquid Glass」——
 * 玻璃是一种浮在内容层之上的**功能层**，让背后内容透出并滚动，
 * 通过半透明 + 镜面边缘高光（specular rim）+ 斜向反光（sheen）+ 投影建立层次。
 * 颜色取自当前 [MaterialTheme.colorScheme]，因此天然适配深浅色与任意配色方案。
 *
 * 说明：Compose 的模糊（BlurEffect/Modifier.blur）只作用于节点「自身内容」，
 * 无法像系统合成器那样折射背后真实的内容（列表/背景）。因此玻璃的「通透感」由
 * 半透明填充 + 高光实现——这与 Apple 的 clear Liquid Glass 变体一致，也避免了
 * 折射导致文字不可读的争议。真机（API 31+）上 [LiquidGlassSurface] 保留
 * 折射模糊钩子，供需要更强磨砂效果时启用。
 *
 * @param containerColor 半透明填充（染色自 surface），内容可透出，承载玻璃质感
 * @param rimColor       顶部镜面高光描边（specular rim）的亮色
 * @param sheenColor     斜向反光（liquid sheen）颜色，模拟光线掠过玻璃
 * @param bottomShadowColor 底部内阴影颜色，营造玻璃的厚度/体积感
 * @param rimAlpha       发丝级描边（边缘 catch）的不透明度
 * @param elevation      投影高度，建立与内容的层次
 */
data class LiquidGlassTokens(
    val containerColor: Color,
    val rimColor: Color,
    val sheenColor: Color,
    val bottomShadowColor: Color,
    val rimAlpha: Float,
    val elevation: Dp
) {
    companion object {
        @Composable
        fun fromCurrent(isDark: Boolean): LiquidGlassTokens {
            val surface = MaterialTheme.colorScheme.surface
            return if (isDark) {
                LiquidGlassTokens(
                    // 深色：以 surface 为基底提亮，半透明，背后内容透出
                    containerColor = blendSurfaceWhite(surface, surfaceFactor = 0.80f, whiteFactor = 0.12f, alpha = 0.52f),
                    rimColor = Color.White.copy(alpha = 0.9f),
                    sheenColor = Color.White.copy(alpha = 0.14f),
                    bottomShadowColor = Color.Black.copy(alpha = 0.30f),
                    rimAlpha = 0.5f,
                    elevation = 8.dp
                )
            } else {
                LiquidGlassTokens(
                    // 浅色：surface 偏白带，半透明，镜面更亮
                    containerColor = blendSurfaceWhite(surface, surfaceFactor = 0.42f, whiteFactor = 0.58f, alpha = 0.60f),
                    rimColor = Color.White,
                    sheenColor = Color.White.copy(alpha = 0.28f),
                    bottomShadowColor = Color.Black.copy(alpha = 0.10f),
                    rimAlpha = 0.6f,
                    elevation = 4.dp
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
 * 在 [DrawScope] 内绘制玻璃的三层光学叠加：顶部镜面高光、斜向反光、底部内阴影。
 * 调用方需先 [DrawScope.drawContent] 画出前景内容，再调用本方法叠加高光。
 */
private fun DrawScope.glassHighlights(tokens: LiquidGlassTokens) {
    // 1) 顶部镜面高光（specular rim）：贴顶的一束亮线，向下快速淡出
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(tokens.rimColor, Color.Transparent),
            startY = 0f,
            endY = size.height * 0.16f
        )
    )
    // 2) 斜向反光（liquid sheen）：左上到右下的柔和光斑，模拟光线掠过玻璃
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(tokens.sheenColor, Color.Transparent),
            start = Offset(0f, 0f),
            end = Offset(size.width * 0.75f, size.height * 0.55f)
        )
    )
    // 3) 底部内阴影：营造玻璃的厚度/体积感
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.Transparent, tokens.bottomShadowColor),
            startY = size.height * 0.82f,
            endY = size.height
        )
    )
}

/**
 * 把任意 [androidx.compose.foundation.layout.Box] / [Surface] 变成玻璃材质。
 *
 * 视觉构成（参考 Apple Liquid Glass 规范）：
 * - 半透明填充：背后内容透出，建立「功能层浮于内容层之上」的层次
 * - 顶部镜面高光 + 斜向反光：玻璃的液体光泽
 * - 发丝级描边：锐利的边缘 catch
 * - 投影：与下方内容的深度分离
 *
 * 兼容全 API 等级；仅作用于本节点自身，不模糊背后真实内容（见 [LiquidGlassTokens] 说明）。
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
    .border(width = 0.75.dp, color = tokens.rimColor.copy(alpha = tokens.rimAlpha), shape = shape)

/**
 * 玻璃容器。内容不会被模糊；通过半透明 + 高光还原玻璃质感。
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
