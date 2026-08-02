package com.bastion.app.ui.theme.glass

import android.os.Build
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.graphicsLayer
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
 * 颜色取自当前 [MaterialTheme.colorScheme]，因此天然适配深浅色与任意配色方案
 * （海洋蓝 / 日落橙 / Catppuccin / 动态取色 ……）。
 *
 * 玻璃感由「半透明填充 + 发丝级高光描边 + 顶部镜面反光 + 柔和投影」共同构成，
 * 内容可透过玻璃看到，这正是 Liquid Glass 的核心观感。
 * 在 API 31+ 设备上，[LiquidGlassSurface] 还会在背景层叠加真实 [BlurEffect] 折射模糊。
 */
data class LiquidGlassTokens(
    val containerColor: Color, // 半透明填充（染色自 surface）
    val borderColor: Color,    // 发丝级高光描边
    val highlightColor: Color, // 顶部镜面高光渐变末端色
    val elevation: Dp,
    val blurRadius: Dp         // API31+ 背景层折射模糊半径
) {
    companion object {
        @Composable
        fun fromCurrent(isDark: Boolean): LiquidGlassTokens {
            val surface = MaterialTheme.colorScheme.surface
            return if (isDark) {
                LiquidGlassTokens(
                    // 深色：以 surface 为基底，掺入极少量白提亮，半透明
                    containerColor = blendSurfaceWhite(surface, surfaceFactor = 0.72f, whiteFactor = 0.06f, alpha = 0.62f),
                    borderColor = Color.White.copy(alpha = 0.18f),
                    highlightColor = Color.White.copy(alpha = 0.12f),
                    elevation = 6.dp,
                    blurRadius = 20.dp
                )
            } else {
                LiquidGlassTokens(
                    // 浅色：surface 偏白带，半透明，镜面更亮
                    containerColor = blendSurfaceWhite(surface, surfaceFactor = 0.55f, whiteFactor = 0.45f, alpha = 0.58f),
                    borderColor = Color.White.copy(alpha = 0.6f),
                    highlightColor = Color.White.copy(alpha = 0.4f),
                    elevation = 3.dp,
                    blurRadius = 16.dp
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
 * 把任意 [Surface]/[androidx.compose.foundation.layout.Box] 变成玻璃材质。
 *
 * - 半透明填充（内容可透出）
 * - 发丝级高光描边
 * - 顶部镜面反光（竖向渐变，高度 50% 处淡出）
 * - 柔和投影
 *
 * 兼容全 API 等级；不模糊内容本身（Compose 无内建「背后内容模糊」，
 * 玻璃观感由半透明 + 反光实现，这是业界通行的做法）。降级场景（API < 31）
 * 与启用场景外观一致，仅缺少背景层折射模糊。
 */
@Composable
fun Modifier.liquidGlass(
    enabled: Boolean = LocalLiquidGlass.current,
    tokens: LiquidGlassTokens = LiquidGlassTokens.fromCurrent(isSystemInDarkTheme()),
    shape: Shape = RoundedCornerShape(0.dp)
): Modifier = if (!enabled) this else this
    .background(tokens.containerColor, shape)
    .drawWithContent {
        drawContent()
        val gloss = Brush.verticalGradient(
            colors = listOf(tokens.highlightColor, Color.Transparent),
            startY = 0f,
            endY = size.height * 0.5f
        )
        drawRect(brush = gloss, topLeft = Offset.Zero, size = Size(size.width, size.height))
    }
    .border(width = 1.dp, color = tokens.borderColor, shape = shape)

/**
 * 玻璃容器。内容不会被模糊；在 API 31+ 设备上，背景层叠加真实 [BlurEffect] 折射模糊，
 * 让玻璃更有「液态」厚度感。低于 API 31 时降级为半透明玻璃（视觉一致）。
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
        // 背景玻璃层（先画，置于底层；API31+ 真实折射模糊，仅作用于本层，绝不模糊内容）
        val bgModifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .background(tokens.containerColor, shape)
        val blurPx = with(LocalDensity.current) { tokens.blurRadius.toPx() }
        val bgWithBlur = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bgModifier.graphicsLayer {
                renderEffect = BlurEffect(blurPx, blurPx)
            }
        } else {
            bgModifier
        }
        Box(modifier = bgWithBlur)
        // 内容层（在上层，不模糊）+ 顶部镜面高光
        BoxWithGlass(content = content, shape = shape, highlightColor = tokens.highlightColor)
    }
}

@Composable
private fun BoxWithGlass(
    content: @Composable () -> Unit,
    shape: Shape,
    highlightColor: Color
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(highlightColor, Color.Transparent),
                        startY = 0f,
                        endY = size.height * 0.5f
                    )
                )
            }
    ) {
        content()
    }
}
