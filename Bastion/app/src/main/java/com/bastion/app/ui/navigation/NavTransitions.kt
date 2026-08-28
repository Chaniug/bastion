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
private const val EASY_NOTES_FADE_DURATION = 300
private const val EASY_NOTES_SCALE_DURATION = 400
private const val EASY_NOTES_INITIAL_SCALE = 0.9f

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

/** EasyNotes 风格页面进入：从 0.9 缩放到 1，同时淡入。 */
fun easyNotesScreenEnter(): EnterTransition =
    fadeIn(animationSpec = tween(EASY_NOTES_FADE_DURATION)) +
        scaleIn(
            initialScale = EASY_NOTES_INITIAL_SCALE,
            animationSpec = tween(EASY_NOTES_SCALE_DURATION)
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

/** EasyNotes 风格页面退出：从 1 缩到 0.9，同时淡出。 */
fun easyNotesScreenExit(): ExitTransition =
    fadeOut(animationSpec = tween(EASY_NOTES_FADE_DURATION)) +
        scaleOut(
            targetScale = EASY_NOTES_INITIAL_SCALE,
            animationSpec = tween(EASY_NOTES_SCALE_DURATION)
        )
