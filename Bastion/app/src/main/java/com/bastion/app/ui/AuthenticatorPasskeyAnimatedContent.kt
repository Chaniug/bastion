package com.bastion.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bastion.app.ui.main.navigation.BottomNavItem
import com.bastion.app.ui.navigation.parallaxEnterFromLeft
import com.bastion.app.ui.navigation.parallaxExitToLeft
import com.bastion.app.ui.navigation.slideInFromRight
import com.bastion.app.ui.navigation.slideOutToRight

@Composable
internal fun AuthenticatorPasskeyAnimatedContent(
    currentTab: BottomNavItem,
    modifier: Modifier = Modifier,
    content: @Composable (BottomNavItem) -> Unit
) {
    AnimatedContent(
        targetState = currentTab,
        modifier = modifier,
        transitionSpec = {
            val transform = when {
                initialState == BottomNavItem.Authenticator && targetState == BottomNavItem.Passkey ->
                    slideInFromRight() togetherWith parallaxExitToLeft()

                initialState == BottomNavItem.Passkey && targetState == BottomNavItem.Authenticator ->
                    parallaxEnterFromLeft() togetherWith slideOutToRight()

                else -> EnterTransition.None togetherWith ExitTransition.None
            }
            transform.using(SizeTransform(clip = false))
        },
        contentKey = BottomNavItem::key,
        label = "authenticator_passkey_switch",
        content = { targetTab -> content(targetTab) }
    )
}
