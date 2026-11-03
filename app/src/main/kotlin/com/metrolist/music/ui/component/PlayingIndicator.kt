/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.metrolist.music.R

@Composable
fun PlaybackStateIndicator(
    color: Color,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val playbackDescription = stringResource(
        if (isPlaying) R.string.mini_player_playing else R.string.mini_player_paused,
    )

    val morph = animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "Mini player bar morph",
    )
    val phase = remember { Animatable(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(isPlaying, lifecycle) {
        if (isPlaying) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                phase.animateTo(
                    targetValue = phase.value + (2 * PI).toFloat(),
                    animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
                )
            }
        }
    }

    Box(
        modifier = modifier
            .size(40.dp)
            .semantics { stateDescription = playbackDescription }
            .drawBehind {
                // Read animation state only while drawing, keeping text and layout out of each frame.
                val progress = morph.value
                val wave = phase.value
                val unit = minOf(size.width, size.height) / 40f
                for (bar in 0..2) {
                    val middle = bar == 1
                    val amplitude = (sin(wave + bar * 2.1f) + 1f) * 0.5f
                    val playingHeight = 8f + 16f * amplitude
                    val height = if (middle) {
                        playingHeight * progress * progress
                    } else {
                        20f + (playingHeight - 20f) * progress
                    }
                    val width = if (middle) 4f * progress else 5.5f - 1.5f * progress
                    val x = if (middle) 0f else (bar - 1) * (6f + 4f * progress)
                    val bottom = center.y + (10f + 2f * progress) * unit
                    if (width > 0f && height > 0f) {
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(center.x + (x - width / 2f) * unit, bottom - height * unit),
                            size = Size(width * unit, height * unit),
                            cornerRadius = CornerRadius(width * unit / 2f),
                        )
                    }
                }
            },
    )
}

@Composable
fun PlayingIndicatorBox(
    modifier: Modifier = Modifier,
    isActive: Boolean,
    playWhenReady: Boolean,
    color: Color = Color.White,
) {
    AnimatedVisibility(
        visible = isActive,
        enter = fadeIn(tween(500)),
        exit = fadeOut(tween(500)),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier,
        ) {
            PlaybackStateIndicator(color = color, isPlaying = playWhenReady)
        }
    }
}
