/**
 * Metrolitt glass navigation.
 *
 * Visual treatment adapted from the GPL-licensed SimpMusic liquid navigation:
 * https://github.com/maxrave-dev/SimpMusic
 * Glass/refraction is provided by Kyant's Backdrop library (2.0.1).
 */
package com.metrolist.music.ui.component

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.metrolist.music.ui.screens.Screens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * A floating two-surface bar: the tabs share a frosted capsule with a sliding
 * selected lens, while Search floats in its own 56dp circular glass button.
 *
 * This intentionally leaves the Material 3 navigation component behind, but
 * retains Metrolitt's existing navigation routes and special long-press actions.
 * The tablet/landscape navigation rail remains independent.
 */
@Composable
fun GlassNavigationBar(
    navigationItems: List<Screens>,
    currentRoute: String?,
    onItemClick: (Screens, Boolean) -> Unit,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    pureBlack: Boolean = false,
    slimNav: Boolean = false,
    onSearchLongClick: (() -> Unit)? = null,
    onHomeLongHold: (() -> Unit)? = null,
) {
    val tabs = remember(navigationItems) { navigationItems.filterNot { it == Screens.Search } }
    val showSearch = Screens.Search in navigationItems
    val selectedPosition = tabs.indexOfFirst { isRouteSelected(currentRoute, it.route, navigationItems) }
    val selectedSearch = showSearch && isRouteSelected(currentRoute, Screens.Search.route, navigationItems)
    val selectedLensX by animateFloatAsState(
        targetValue = selectedPosition.coerceAtLeast(0).toFloat(),
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 450f),
        label = "glassNavLensPosition",
    )
    val selectedLensAlpha by animateFloatAsState(
        targetValue = if (selectedPosition >= 0) 1f else 0f,
        label = "glassNavLensOpacity",
    )

    val isDark = pureBlack || MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val barHeight = if (slimNav) 56.dp else 64.dp
    val lensHeight = if (slimNav) 48.dp else 56.dp
    val searchDiameter = if (slimNav) 48.dp else 56.dp
    val bottomGap = if (slimNav) 4.dp else 8.dp
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier) {
        // All tabs have the same slot width, even on narrow phones. The selected
        // lens and the tab centres therefore always line up.
        val searchBudget = if (showSearch) searchDiameter + 12.dp else 0.dp
        val availableCapsuleWidth = (maxWidth - 32.dp - searchBudget).coerceAtLeast(0.dp)
        val tabWidth =
            if (tabs.isEmpty()) 0.dp
            else ((availableCapsuleWidth - 12.dp) / tabs.size).coerceIn(48.dp, 96.dp)
        val capsuleWidth = tabWidth * tabs.size + 12.dp

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomInset + bottomGap),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (tabs.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .width(capsuleWidth)
                        .height(barHeight),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    // Glass base: a dark, blurred, refractive capsule, not an
                    // opaque Material 3 surface.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .glassSurface(backdrop, isDark, active = false),
                    )

                    // Frosted blob sits between the capsule and crisp text/icons.
                    // Animate only its graphics layer; the content is not remeasured.
                    if (selectedLensAlpha > 0.001f) {
                        Box(
                            Modifier
                                .graphicsLayer {
                                    translationX = with(density) {
                                        6.dp.toPx() + selectedLensX * tabWidth.toPx()
                                    }
                                    alpha = selectedLensAlpha
                                }
                                .width(tabWidth)
                                .height(lensHeight)
                                .glassSurface(backdrop, isDark, active = true),
                        )
                    }

                    Row(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        tabs.forEachIndexed { index, screen ->
                            GlassNavigationItem(
                                screen = screen,
                                selected = index == selectedPosition,
                                modifier = Modifier.width(tabWidth).fillMaxHeight(),
                                onClick = { selected -> onItemClick(screen, selected) },
                                longAction = if (screen == Screens.Home) onHomeLongHold else null,
                                longHoldMillis = if (screen == Screens.Home) 15_000L else null,
                                showLabel = !slimNav,
                            )
                        }
                    }
                }
            }

            if (showSearch) {
                Box(
                    Modifier
                        .size(searchDiameter)
                        .glassSurface(backdrop, isDark, active = selectedSearch),
                    contentAlignment = Alignment.Center,
                ) {
                    GlassNavigationItem(
                        screen = Screens.Search,
                        selected = selectedSearch,
                        modifier = Modifier.fillMaxSize(),
                        onClick = { selected -> onItemClick(Screens.Search, selected) },
                        longAction = onSearchLongClick,
                        showLabel = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassNavigationItem(
    screen: Screens,
    selected: Boolean,
    modifier: Modifier,
    onClick: (Boolean) -> Unit,
    longAction: (() -> Unit)?,
    longHoldMillis: Long? = null,
    showLabel: Boolean,
) {
    val haptics = LocalHapticFeedback.current
    val longPressTimeout = LocalViewConfiguration.current.longPressTimeoutMillis
    val currentSelected by rememberUpdatedState(selected)
    val currentOnClick by rememberUpdatedState(onClick)
    val currentLongAction by rememberUpdatedState(longAction)
    val interaction = remember { MutableInteractionSource() }

    if (longAction != null) {
        // Preserve Metrolitt's 15-second Home hold and normal Search long press.
        // The release still acts as a regular tap if the hold did not complete.
        LaunchedEffect(interaction, longHoldMillis, longPressTimeout) {
            var didHold = false
            interaction.interactions.collectLatest { event ->
                when (event) {
                    is PressInteraction.Press -> {
                        didHold = false
                        delay(longHoldMillis ?: longPressTimeout)
                        didHold = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentLongAction?.invoke()
                    }
                    is PressInteraction.Release -> if (!didHold) currentOnClick(currentSelected)
                    is PressInteraction.Cancel -> didHold = false
                }
            }
        }
    }

    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Column(
        modifier = modifier
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Tab) {
                if (longAction == null) onClick(currentSelected)
            },
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(if (selected) screen.iconIdActive else screen.iconIdInactive),
            contentDescription = stringResource(screen.titleId),
            tint = tint,
            modifier = Modifier.size(if (showLabel) 25.dp else 27.dp),
        )
        if (showLabel) {
            Text(
                text = stringResource(screen.titleId),
                color = tint,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun Modifier.glassSurface(
    backdrop: LayerBackdrop,
    isDark: Boolean,
    active: Boolean,
    shape: Shape = CircleShape,
): Modifier {
    // Backdrop's shader path is intended for current Android GPUs. Older OS
    // versions keep a legible, translucent capsule without a shader dependency.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return this.background(
            if (isDark) Color(0xE01A272D) else Color(0xDEE4EDF1),
            shape,
        )
    }
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        highlight = { Highlight(width = 1.dp) },
        effects = {
            vibrancy()
            colorControls(brightness = 0.05f, contrast = 1f, saturation = 1.5f)
            blur((if (active) 26.dp else 12.dp).toPx())
            lens(
                if (active) 5.dp.toPx() else size.minDimension / 4f,
                size.minDimension / 2f,
                false,
            )
        },
        shadow = { Shadow(radius = if (active) 4.dp else 0.dp, alpha = if (active) 0.28f else 0f) },
        onDrawSurface = {
            val scrim =
                if (isDark) {
                    if (active) Color.Black.copy(alpha = 0.24f)
                    else Color(0xFF10191D).copy(alpha = 0.42f)
                } else {
                    if (active) Color.White.copy(alpha = 0.24f)
                    else Color.White.copy(alpha = 0.32f)
                }
            drawRect(scrim)
        },
    )
}

