package com.bastion.app.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally

/**
 * Bastion Android 导航过渡动画，对齐 Keyguard 的 NavigationAnimation 实现。
 *
 * 进入（GoForward）：新页面从右侧 1/8 屏宽滑入 + fadeIn，
 *                   旧页面向左 1/12 屏宽滑出 + fadeOut。
 * 返回（GoBack）：  旧页面从左侧 -1/12 屏宽滑回 + fadeIn，
 *                   新页面向右 1/8 屏宽滑出 + fadeOut。
 *
 * easing 使用 CubicBezierEasing(0.6, 0.0, 0.4, 1.0)，与 Keyguard 一致。
 */

private const val DURATION_FORWARD = 300
private const val DURATION_BACK = 280

/**
 * EasyNotes 风格缩放转场的统一时长。
 *
 * 之前 fade 用 300ms、scale 用 400ms，**两者不同步**，造成两个可见问题：
 * - 进入：alpha 在 300ms 就到 1，剩下 100ms 是「已经完全不透明却还在缩放」
 * - 退出：alpha 在 300ms 就到 0，剩下 100ms 的缩放根本看不见，纯属空转
 *
 * 现统一为同一时长，让 alpha 与 scale 同步到位。
 */
private const val EASY_NOTES_DURATION = 260

/**
 * 子页面进入/退出的缩放幅度。
 *
 * 原先 0.9f 幅度偏大：进入方与退出方**同时做反向缩放且都带 alpha**，
 * 中间帧（约 t=0.5）两层内容缩放比接近、又都是半透明，高度重合 → 明显重影。
 * 收到 0.94f 后，重叠偏差降到肉眼不易察觉。
 */
private const val EASY_NOTES_INITIAL_SCALE = 0.94f

/**
 * 父页面（Main / 设置页）让位给子页面时的缩放幅度。
 * 比子页面更小，形成景深层次，同时把两层内容的重叠偏差压到最低。
 */
private const val PARENT_PAGE_SCALE = 0.97f

private val navEasing = CubicBezierEasing(0.6f, 0.0f, 0.4f, 1.0f)

private fun <T> tweenForward() = tween<T>(durationMillis = DURATION_FORWARD, easing = navEasing)
private fun <T> tweenBack() = tween<T>(durationMillis = DURATION_BACK, easing = navEasing)
private fun <T> tweenFadeOut() = tween<T>(durationMillis = DURATION_FORWARD / 2, easing = navEasing)

/** 子页面进入：从右侧 1/8 屏宽滑入 + fadeIn。 */
fun slideInFromRight(): EnterTransition =
    slideInHorizontally(
        animationSpec = tweenForward(),
        initialOffsetX = { fullWidth -> fullWidth / 8 },
    ) + fadeIn(animationSpec = tweenBack())

/** 子页面返回时退出：向右 1/8 屏宽滑出 + fadeOut。 */
fun slideOutToRight(): ExitTransition =
    slideOutHorizontally(
        animationSpec = tweenBack(),
        targetOffsetX = { fullWidth -> fullWidth / 8 },
    ) + fadeOut(animationSpec = tweenFadeOut())

/** 列表页退出：向左 1/12 屏宽滑出 + fadeOut（配合子页面进入）。 */
fun parallaxExitToLeft(): ExitTransition =
    slideOutHorizontally(
        animationSpec = tweenForward(),
        targetOffsetX = { fullWidth -> -fullWidth / 12 },
    ) + fadeOut(animationSpec = tweenFadeOut())

/** 列表页返回时进入：从左侧 -1/12 屏宽滑回 + fadeIn。 */
fun parallaxEnterFromLeft(): EnterTransition =
    slideInHorizontally(
        animationSpec = tweenBack(),
        initialOffsetX = { fullWidth -> -fullWidth / 12 },
    ) + fadeIn(animationSpec = tweenBack())

/**
 * EasyNotes 风格页面进入：从 [EASY_NOTES_INITIAL_SCALE] 缩放到 1，同时淡入。
 *
 * fade 与 scale 现在共用 [EASY_NOTES_DURATION]，alpha 与缩放同步到位，
 * 不会再出现「已经不透明但还在缩放」的末段突起。
 */
fun easyNotesScreenEnter(): EnterTransition =
    fadeIn(animationSpec = tween(EASY_NOTES_DURATION, easing = navEasing)) +
        scaleIn(
            initialScale = EASY_NOTES_INITIAL_SCALE,
            animationSpec = tween(EASY_NOTES_DURATION, easing = navEasing)
        )

/**
 * 底部导航 tab 之间的通用切换过渡（淡入 + 轻微上移）。
 *
 * 背景：tab 切换此前对大多数组合使用 EnterTransition.None，页面是硬切的；
 * 原先观感"流畅"实际来自 Material3 Expressive 组件内部的 spring 动画。
 * 主题改用 Standard motion scheme 后组件内部动画收敛，硬切被暴露出来，
 * 因此这里显式补上页面级过渡，不依赖组件内部动画。
 *
 * 说明：这里只用 fade + slideVertically（Compose 稳定 API），
 * 不依赖 MotionScheme，因此在 Standard / Expressive 下表现一致。
 */
private const val DURATION_TAB_SWITCH = 220
private const val DURATION_TAB_FADE_OUT = 120
private const val TAB_SWITCH_OFFSET_RATIO = 16

fun tabSwitchEnter(): EnterTransition =
    fadeIn(animationSpec = tween(durationMillis = DURATION_TAB_SWITCH, easing = navEasing)) +
        slideInVertically(
            animationSpec = tween(durationMillis = DURATION_TAB_SWITCH, easing = navEasing),
            initialOffsetY = { fullHeight -> fullHeight / TAB_SWITCH_OFFSET_RATIO }
        )

fun tabSwitchExit(): ExitTransition =
    fadeOut(animationSpec = tween(durationMillis = DURATION_TAB_FADE_OUT, easing = navEasing))

/** EasyNotes 风格页面退出：从 1 缩到 [EASY_NOTES_INITIAL_SCALE]，同时淡出。 */
fun easyNotesScreenExit(): ExitTransition =
    fadeOut(animationSpec = tween(EASY_NOTES_DURATION, easing = navEasing)) +
        scaleOut(
            targetScale = EASY_NOTES_INITIAL_SCALE,
            animationSpec = tween(EASY_NOTES_DURATION, easing = navEasing)
        )

/**
 * 父页面（Main / 设置页）让位给子页面：只做极轻微缩小，**不淡出**。
 *
 * 为什么不淡出：子页面是在父页面上方淡入的。若父页面同时淡出，
 * 中间帧会出现两层半透明内容叠在一起（重影）。
 * 父页面保持不透明，就会被上层自然遮住，不需要靠 alpha 退场。
 */
fun parentPageExit(): ExitTransition =
    scaleOut(
        targetScale = PARENT_PAGE_SCALE,
        animationSpec = tween(EASY_NOTES_DURATION, easing = navEasing)
    )

/**
 * 父页面（Main / 设置页）在子页面返回时恢复：从轻微缩小还原，**不淡入**。
 *
 * 与 [parentPageExit] 对称。返回时子页面在上方做 easyNotesScreenExit
 * （缩放 + 淡出），父页面只做还原、不叠加 alpha 变化，
 * 因此不会出现「两层半透明内容同时缩放」的重影。
 */
fun parentPageEnter(): EnterTransition =
    scaleIn(
        initialScale = PARENT_PAGE_SCALE,
        animationSpec = tween(EASY_NOTES_DURATION, easing = navEasing)
    )
