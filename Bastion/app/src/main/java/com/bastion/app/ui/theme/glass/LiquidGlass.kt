package com.bastion.app.ui.theme.glass

import android.graphics.RenderEffect as PlatformRenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect as ComposeRenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.translate

/**
 * 全局开关：液态玻璃是否启用。
 * 由 [com.bastion.app.ui.theme.BastionTheme] 从用户设置注入，
 * 各玻璃材质组件读取 [LocalLiquidGlass.current] 决定是否启用。
 */
val LocalLiquidGlass = compositionLocalOf { false }

/**
 * 背景捕获状态：由 [glazeSource] 写入"背后内容"的离屏图层与坐标，
 * 供玻璃材质组件（[liquidGlass]）采样并模糊，实现真正的背景模糊（非只模糊自身）。
 *
 * 这是 Haze 风格的成熟做法：用 [GraphicsLayer] 把背景录进离屏图层，
 * 再用 [PlatformRenderEffect] 做模糊，玻璃层平移正确偏移后绘制该图层。
 */
class GlassBackdropState {
    /** 背后内容录制的离屏图层（由 glazeSource 写入）。 */
    internal var contentLayer: GraphicsLayer? = null

    /** 背景源在 root 坐标系中的位置（由 glazeSource 写入）。 */
    internal var sourcePosition: Offset = Offset.Zero

    /** 背景源尺寸。 */
    internal var sourceSize: Size = Size.Unspecified

    /**
     * 源每次重新录制内容时自增。玻璃节点监听此值以在滚动/动画时实时重绘模糊背景。
     */
    internal var contentVersion: Long by mutableLongStateOf(0)

    /**
     * 源正在把自身内容录进 contentLayer 时为 true，避免玻璃节点在"被录制"时
     * 又去采样 contentLayer 造成图层套图层的反馈/崩溃（Haze 的 contentDrawing 机制）。
     */
    internal var contentDrawing: Boolean = false

    private val listeners = mutableListOf<() -> Unit>()

    internal fun addListener(l: () -> Unit) {
        if (l !in listeners) listeners += l
    }

    internal fun removeListener(l: () -> Unit) {
        listeners -= l
    }

    internal fun notifyListeners() {
        for (l in listeners) l()
    }
}

/**
 * 背景捕获状态的 CompositionLocal。由 [com.bastion.app.ui.theme.BastionTheme]
 * 提供；玻璃组件读取它来拿到要模糊的背景图层。
 */
val LocalGlassBackdrop = compositionLocalOf { GlassBackdropState() }

/** 真背景模糊需要 Android 12（API 31）以上的 RenderEffect；更低版本回退到半透明方案。 */
internal val GLASS_BLUR_SUPPORTED: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * 液态玻璃材质参数。
 *
 * 设计依据综合自三个成熟的 Android 玻璃态实现方案：
 *
 * 1. **Haze 风格（Chris Banes 的 RenderEffect 背景捕获）**：真背景模糊的核心技法，
 *    本实现即采用此思路（自研、无外部依赖）。
 * 2. **Yang-Ya-Chao 玻璃态设计系统**：纯 Compose 实现，
 *    核心是 `1dp 顶部内高光线` + `1dp 渐变描边` + 低透明填充 + 外投影。
 * 3. **androidengineers Glassmorphism 指南**：推荐低透明填充 + 渐变描边 + 投影。
 *
 * 视觉构成（从底到顶，API31+ 走真模糊）：
 * - 背后内容的实时模糊（RenderEffect，半径 ~18dp）
 * - 低透明 tint（背后内容透出 + 轻微 vibrancy）
 * - 1dp 顶部内高光线（模拟玻璃上边缘的光反射，克制，不出现矩形带）
 * - 1dp 上亮下暗渐变描边（模拟光照衰减）
 * - 轻微外投影（建立与内容的深度分离）
 *
 * < API31 或捕获不可用时，回退为"低透明填充 + 高光 + 渐变描边"（无真模糊，但不会崩）。
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
// 背景捕获：glazeSource
// ============================================================

/**
 * 把修饰对象背后的内容录制进 [GlassBackdropState.contentLayer]（离屏 [GraphicsLayer]），
 * 供液态玻璃组件模糊采样。应包裹在浮层（顶栏/底栏/弹窗）**背后**的内容上。
 *
 * - API31+：内容被录进独立 [GraphicsLayer]，玻璃层对其做 RenderEffect 模糊。
 * - <API31：无真模糊，玻璃回退为半透明方案（此修饰符仍正常上屏，只是玻璃不模糊）。
 */
fun Modifier.glazeSource(state: GlassBackdropState): Modifier = this.then(GlazeSourceElement(state))

private data class GlazeSourceElement(
    val state: GlassBackdropState
) : ModifierNodeElement<GlazeSourceNode>() {
    override fun create(): GlazeSourceNode = GlazeSourceNode(state)
    override fun update(node: GlazeSourceNode) {
        node.state = state
    }
}

private class GlazeSourceNode(
    state: GlassBackdropState
) : Modifier.Node(),
    CompositionLocalConsumerModifierNode,
    GlobalPositionAwareModifierNode,
    DrawModifierNode {

    var state: GlassBackdropState = state
        set(value) {
            if (value === field) return
            field = value
        }

    private var layer: GraphicsLayer? = null

    override val shouldAutoInvalidate: Boolean = false

    override fun onAttach() {
        // 图层在首次 draw 时惰性创建
    }

    override fun onGloballyPositioned(coordinates: androidx.compose.ui.layout.LayoutCoordinates) {
        Snapshot.withoutReadObservation {
            state.sourcePosition = coordinates.positionInRoot()
            state.sourceSize = coordinates.size.toSize()
        }
    }

    override fun ContentDrawScope.draw() {
        try {
            state.contentDrawing = true
            if (!isAttached) return
            if (size.minDimension >= 1f) {
                val ctx = currentValueOf(LocalGraphicsContext)
                val contentLayer = layer ?: ctx.createGraphicsLayer().also { layer = it }
                state.contentLayer = contentLayer
                // 把背后内容录进离屏图层
                contentLayer.record {
                    this@draw.drawContent()
                }
                state.contentVersion++
                // 再把内容正常画到屏幕上
                drawLayer(contentLayer)
                state.notifyListeners()
            } else {
                drawContent()
            }
        } finally {
            state.contentDrawing = false
        }
    }

    override fun onDetach() {
        layer?.let { currentValueOf(LocalGraphicsContext).releaseGraphicsLayer(it) }
        layer = null
        state.contentLayer = null
    }
}

// ============================================================
// 玻璃材质：liquidGlass
// ============================================================

/**
 * 把任意 [androidx.compose.foundation.layout.Box] / [Surface] 变成玻璃材质。
 *
 * - 启用且 API31+ 且已捕获到背景：绘制**背后内容的实时模糊** + tint + 高光 + 渐变描边。
 * - 否则回退为半透明填充 + 高光 + 渐变描边（不报错、不崩）。
 *
 * 模糊通过 [GlassBackdropState]（由 [glazeSource] 填充）采样背后内容，
 * 平移 `sourcePos - glassPos` 使背景对齐（同一 root 坐标系）。
 */
@Composable
fun Modifier.liquidGlass(
    enabled: Boolean = LocalLiquidGlass.current,
    tokens: LiquidGlassTokens = LiquidGlassTokens.fromCurrent(isSystemInDarkTheme()),
    shape: Shape = RoundedCornerShape(0.dp),
    blurRadiusDp: Dp = 18.dp
): Modifier = if (!enabled) this else this
    .shadow(elevation = tokens.elevation, shape = shape, clip = false)
    .clip(shape)
    .then(LiquidGlassElement(enabled, tokens, shape, blurRadiusDp))

private data class LiquidGlassElement(
    val enabled: Boolean,
    val tokens: LiquidGlassTokens,
    val shape: Shape,
    val blurRadiusDp: Dp
) : ModifierNodeElement<LiquidGlassNode>() {
    override fun create(): LiquidGlassNode = LiquidGlassNode(enabled, tokens, shape, blurRadiusDp)
    override fun update(node: LiquidGlassNode) {
        node.enabled = enabled
        node.tokens = tokens
        node.shape = shape
        node.blurRadiusDp = blurRadiusDp
    }
}

private class LiquidGlassNode(
    enabled: Boolean,
    tokens: LiquidGlassTokens,
    shape: Shape,
    blurRadiusDp: Dp
) : Modifier.Node(),
    CompositionLocalConsumerModifierNode,
    GlobalPositionAwareModifierNode,
    DrawModifierNode {

    var enabled: Boolean by mutableStateOf(enabled)
    var tokens: LiquidGlassTokens by mutableStateOf(tokens)
    var shape: Shape by mutableStateOf(shape)
    var blurRadiusDp: Dp by mutableStateOf(blurRadiusDp)

    private var glassPos: Offset = Offset.Zero
    private var effectLayer: GraphicsLayer? = null
    private var graphicsContext: androidx.compose.ui.graphics.GraphicsContext? = null
    private val backdropState: GlassBackdropState = currentValueOf(LocalGlassBackdrop)
    private val redrawListener: () -> Unit = { invalidateDraw() }

    override val shouldAutoInvalidate: Boolean = true

    override fun onAttach() {
        graphicsContext = currentValueOf(LocalGraphicsContext)
        backdropState.addListener(redrawListener)
    }

    override fun onGloballyPositioned(coordinates: androidx.compose.ui.layout.LayoutCoordinates) {
        val newPos = coordinates.positionInRoot()
        if (newPos != glassPos) {
            glassPos = newPos
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        // 源正在把自身内容录进 contentLayer 时（contentDrawing=true），本节点是源的子树，
        // 它的绘制会被一起录进图层。此时必须完全跳过玻璃绘制，否则玻璃自身的 tint/高光会
        // 被烘焙进源图层，后续采样时产生"双重影像"（玻璃在自己背后又模糊出一层自己）。
        // 真实的模糊绘制发生在源录制结束后的重绘（contentDrawing=false）里，由源
        // notifyListeners() 触发本节点的 invalidateDraw() 完成，绘制在源图层之上。
        if (backdropState.contentDrawing) return

        val showBlur = enabled &&
            GLASS_BLUR_SUPPORTED &&
            backdropState.contentLayer != null

        if (showBlur) {
            drawBlurredBackdrop()
            // tint：在模糊背景上叠一层极低透明填充，做出通透/vibrancy 感
            drawRect(color = tokens.containerColor)
        } else if (enabled) {
            // 回退：半透明填充（无真模糊 / 未捕获到背景）
            drawRect(color = tokens.containerColor)
        }

        // 原内容（透明背景 + 子元素）画在 tint/模糊之上
        drawContent()

        if (enabled) {
            glassHighlights(tokens)
        }
    }

    private fun DrawScope.drawBlurredBackdrop() {
        val src = backdropState.contentLayer ?: return
        val blurPx = blurRadiusDp.toPx()
        val layer = ensureEffectLayer(blurPx)
        val offset = backdropState.sourcePosition - glassPos
        layer.record {
            translate(left = offset.x, top = offset.y) {
                drawLayer(src)
            }
        }
        // 形状裁剪已由 liquidGlass() 的 Modifier.clip(shape) 负责，此处直接绘制即可，
        // 避免依赖 androidx.compose.ui.graphics.drawscope.clip（该包级函数在本项目 Compose
        // 版本里未直接暴露）。最终玻璃内容会被 Modifier 层的 clip 裁到 shape 内。
        drawLayer(layer)
    }

    private fun ensureEffectLayer(blurPx: Float): GraphicsLayer {
        effectLayer?.let { return it }
        val ctx = graphicsContext
            ?: error("GraphicsContext unavailable for liquid glass effect layer")
        return ctx.createGraphicsLayer().also {
            if (GLASS_BLUR_SUPPORTED && blurPx > 0f) {
                it.renderEffect = PlatformRenderEffect
                    .createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
                    .asComposeRenderEffect()
            }
            effectLayer = it
        }
    }

    override fun onDetach() {
        backdropState.removeListener(redrawListener)
        effectLayer?.let { graphicsContext?.releaseGraphicsLayer(it) }
        effectLayer = null
        graphicsContext = null
    }
}

/**
 * 玻璃容器。通过半透明 + 真实背景模糊（API31+）+ 微妙高光还原玻璃质感。
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
