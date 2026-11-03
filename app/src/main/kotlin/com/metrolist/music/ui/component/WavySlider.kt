/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WavySlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(),
    isPlaying: Boolean = true,
    enabled: Boolean = true,
    strokeWidth: Dp = 4.dp,
    thumbRadius: Dp = 8.dp,
    wavelength: Dp = WavyProgressIndicatorDefaults.LinearDeterminateWavelength,
    waveSpeed: Dp = wavelength
) {
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { strokeWidth.toPx() }
    val thumbRadiusPx = with(density) { thumbRadius.toPx() }
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
    }
    val wavelengthPx = with(density) { wavelength.toPx() }
    val waveSpeedPx = with(density) { waveSpeed.toPx() }
    val waveHeightPx = with(density) { WavyProgressIndicatorDefaults.LinearContainerHeight.toPx() }
    val waveTransition = remember { Animatable(if (isPlaying) 1f else 0f) }
    var wavePhasePx by remember { mutableFloatStateOf(0f) }
    val wavePath = remember { Path() }

    LaunchedEffect(isPlaying) {
        val transitionJob = launch {
            waveTransition.animateTo(
                targetValue = if (isPlaying) 1f else 0f,
                animationSpec = tween(durationMillis = 450, easing = LinearEasing),
            )
        }

        if (wavelengthPx > 0f && waveSpeedPx > 0f && (isPlaying || transitionJob.isActive)) {
            var previousFrame = withFrameNanos { it }
            while (isActive && (isPlaying || transitionJob.isActive)) {
                val frame = withFrameNanos { it }
                wavePhasePx = (wavePhasePx + (frame - previousFrame) / 1_000_000_000f * waveSpeedPx) % wavelengthPx
                previousFrame = frame
            }
        }
    }

    val hasRange = valueRange.endInclusive > valueRange.start
    val normalizedValue = if (hasRange) {
        ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
    } else {
        0f
    }
    
    var isDragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(normalizedValue) }
    
    val displayValue = if (isDragging) dragValue else normalizedValue
    
    val activeColor = colors.activeTrackColor
    val inactiveColor = colors.inactiveTrackColor
    val thumbColor = colors.thumbColor
    
    // Calculate container height to accommodate thumb
    val containerHeight = maxOf(WavyProgressIndicatorDefaults.LinearContainerHeight, thumbRadius * 2)
    
    val baseModifier = modifier
        .fillMaxWidth()
        .height(maxOf(containerHeight, 48.dp))
        .semantics(mergeDescendants = true) {
            progressBarRangeInfo = ProgressBarRangeInfo(normalizedValue, 0f..1f)
            if (enabled && hasRange) {
                setProgress { progress ->
                    onValueChange(valueRange.start + progress.coerceIn(0f, 1f) * (valueRange.endInclusive - valueRange.start))
                    onValueChangeFinished?.invoke()
                    true
                }
            } else {
                disabled()
            }
        }

    val interactiveModifier = if (enabled && hasRange) {
        baseModifier
            .pointerInput(valueRange) {
                detectTapGestures { offset ->
                    val newValue = (offset.x / size.width).coerceIn(0f, 1f)
                    val mappedValue = valueRange.start + newValue * (valueRange.endInclusive - valueRange.start)
                    onValueChange(mappedValue)
                    onValueChangeFinished?.invoke()
                }
            }
            .pointerInput(valueRange) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        dragValue = (offset.x / size.width).coerceIn(0f, 1f)
                        val mappedValue = valueRange.start + dragValue * (valueRange.endInclusive - valueRange.start)
                        onValueChange(mappedValue)
                    },
                    onDragEnd = {
                        isDragging = false
                        onValueChangeFinished?.invoke()
                    },
                    onDragCancel = {
                        isDragging = false
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        dragValue = (dragValue + dragAmount / size.width).coerceIn(0f, 1f)
                        val mappedValue = valueRange.start + dragValue * (valueRange.endInclusive - valueRange.start)
                        onValueChange(mappedValue)
                    }
                )
            }
    } else {
        baseModifier
    }

    Box(
        modifier = interactiveModifier,
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerY = size.height / 2f
            val progressX = size.width * displayValue
            val halfWavelength = wavelengthPx / 2f
            val fadeWidth = wavelengthPx.coerceAtLeast(strokeWidthPx * 2f)
            val transitionProgress = waveTransition.value
            // Keep the front moving left from the thumb, reversing only its amplitude while pausing.
            val wavefrontProgress = if (isPlaying) transitionProgress else 1f - transitionProgress
            val edgeX = progressX - wavefrontProgress * (progressX + fadeWidth)
            val controlHeight = (minOf(size.height, waveHeightPx) - strokeWidthPx)
                .coerceAtLeast(0f) * 0.7f

            fun amplitudeAt(x: Float): Float {
                val position = ((x - edgeX) / fadeWidth).coerceIn(0f, 1f)
                val easedPosition = position * position * (3f - 2f * position)
                return if (isPlaying) easedPosition else 1f - easedPosition
            }

            fun waveYAt(x: Float): Float {
                val segmentIndex = ((x + wavePhasePx) / halfWavelength).toInt().coerceAtLeast(0)
                val segmentStartX = -wavePhasePx + segmentIndex * halfWavelength
                val controlX = segmentStartX + halfWavelength / 2f
                val position = ((x - segmentStartX) / halfWavelength).coerceIn(0f, 1f)
                val controlSign = if (segmentIndex % 2 == 0) 1f else -1f
                val controlY = centerY + controlSign * controlHeight * amplitudeAt(controlX)
                return centerY + 2f * (1f - position) * position * (controlY - centerY)
            }

            drawLine(
                color = inactiveColor,
                start = Offset((progressX + thumbRadiusPx + strokeWidthPx).coerceAtMost(size.width), centerY),
                end = Offset(size.width, centerY),
                strokeWidth = strokeWidthPx,
                cap = StrokeCap.Round,
            )

            val leftCapX = strokeWidthPx / 2f
            if (halfWavelength > 0f && progressX > leftCapX) {
                wavePath.rewind()
                var anchorX = -wavePhasePx
                var controlX = anchorX + halfWavelength / 2f
                var sign = 1f
                wavePath.moveTo(anchorX, centerY)

                while (anchorX < size.width) {
                    val nextAnchorX = anchorX + halfWavelength
                    wavePath.quadraticTo(
                        controlX,
                        centerY + sign * controlHeight * amplitudeAt(controlX),
                        nextAnchorX,
                        centerY,
                    )
                    anchorX = nextAnchorX
                    controlX += halfWavelength
                    sign = -sign
                }

                drawCircle(
                    color = activeColor,
                    radius = leftCapX,
                    center = Offset(leftCapX, waveYAt(leftCapX)),
                )
                clipRect(left = leftCapX, right = progressX) {
                    drawPath(wavePath, activeColor, style = stroke)
                }
            }

            drawCircle(
                color = thumbColor,
                radius = thumbRadiusPx,
                center = Offset(progressX, centerY),
            )
        }
    }
}
