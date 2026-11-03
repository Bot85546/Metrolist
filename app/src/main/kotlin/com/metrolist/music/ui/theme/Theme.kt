/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.remember
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme
import com.materialkolor.score.Score

private val lightGrayscale = lightColorScheme(
    primary = Color(0xFF202020),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2E2E2),
    onPrimaryContainer = Color(0xFF202020),
    inversePrimary = Color(0xFFCCCCCC),
    secondary = Color(0xFF454545),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7E7E7),
    onSecondaryContainer = Color(0xFF202020),
    tertiary = Color(0xFF555555),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEAEAEA),
    onTertiaryContainer = Color(0xFF202020),
    background = Color(0xFFF8F8F8),
    onBackground = Color(0xFF1A1A1A),
    surface = Color.White,
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFE8E8E8),
    onSurfaceVariant = Color(0xFF4A4A4A),
    surfaceTint = Color(0xFF202020),
    inverseSurface = Color(0xFF2A2A2A),
    inverseOnSurface = Color(0xFFF5F5F5),
    error = Color(0xFF555555),
    onError = Color.White,
    errorContainer = Color(0xFFE6E6E6),
    onErrorContainer = Color(0xFF202020),
    outline = Color(0xFF777777),
    outlineVariant = Color(0xFFD0D0D0),
    scrim = Color.Black,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFD6D6D6),
    surfaceContainer = Color(0xFFF3F3F3),
    surfaceContainerHigh = Color(0xFFEDEDED),
    surfaceContainerHighest = Color(0xFFE6E6E6),
    surfaceContainerLow = Color(0xFFF6F6F6),
    surfaceContainerLowest = Color.White,
)

private val darkGrayscale = darkColorScheme(
    primary = Color(0xFFF2F2F2),
    onPrimary = Color(0xFF202020),
    primaryContainer = Color(0xFF3A3A3A),
    onPrimaryContainer = Color(0xFFF2F2F2),
    inversePrimary = Color(0xFF555555),
    secondary = Color(0xFFD0D0D0),
    onSecondary = Color(0xFF202020),
    secondaryContainer = Color(0xFF353535),
    onSecondaryContainer = Color(0xFFEAEAEA),
    tertiary = Color(0xFFBDBDBD),
    onTertiary = Color(0xFF202020),
    tertiaryContainer = Color(0xFF3D3D3D),
    onTertiaryContainer = Color(0xFFEAEAEA),
    background = Color(0xFF101010),
    onBackground = Color(0xFFF0F0F0),
    surface = Color(0xFF141414),
    onSurface = Color(0xFFF0F0F0),
    surfaceVariant = Color(0xFF303030),
    onSurfaceVariant = Color(0xFFD0D0D0),
    surfaceTint = Color(0xFFF2F2F2),
    inverseSurface = Color(0xFFEAEAEA),
    inverseOnSurface = Color(0xFF262626),
    error = Color(0xFFD0D0D0),
    onError = Color(0xFF202020),
    errorContainer = Color(0xFF454545),
    onErrorContainer = Color(0xFFEAEAEA),
    outline = Color(0xFF8A8A8A),
    outlineVariant = Color(0xFF4A4A4A),
    scrim = Color.Black,
    surfaceBright = Color(0xFF3A3A3A),
    surfaceDim = Color(0xFF101010),
    surfaceContainer = Color(0xFF1D1D1D),
    surfaceContainerHigh = Color(0xFF282828),
    surfaceContainerHighest = Color(0xFF333333),
    surfaceContainerLow = Color(0xFF191919),
    surfaceContainerLowest = Color(0xFF0D0D0D),
)

val DefaultThemeColor = Color(0xFF808080)

fun Bitmap.extractThemeColor(): Color = Color(
    Palette.from(this)
        .maximumColorCount(8)
        .generate()
        .rankedColors(1, DefaultThemeColor.toArgb())
        .first()
)

internal fun Palette.rankedColors(
    desiredColorCount: Int,
    fallbackColor: Int,
): List<Int> = Score.score(
    swatches.associate { it.rgb to it.population },
    desiredColorCount,
    fallbackColor,
    true,
)

val ColorSaver = object : Saver<Color, Int> {
    override fun restore(value: Int): Color = Color(value)
    override fun SaverScope.save(value: Color): Int = value.toArgb()
}

@Composable
fun MetrolistTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    pureBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val baseColorScheme = if (darkTheme) darkGrayscale else lightGrayscale
    val colorScheme = remember(baseColorScheme, pureBlack, darkTheme) {
        if (darkTheme && pureBlack) baseColorScheme.pureBlack(true) else baseColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

@Composable
fun PlayerTheme(
    themeColor: Color,
    darkTheme: Boolean,
    pureBlack: Boolean,
    content: @Composable () -> Unit,
) {
    val playerColorScheme = rememberDynamicColorScheme(
        seedColor = themeColor,
        isDark = darkTheme,
        style = PaletteStyle.TonalSpot,
    )
    val colorScheme = remember(playerColorScheme, pureBlack, darkTheme) {
        if (darkTheme && pureBlack) playerColorScheme.pureBlack(true) else playerColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content,
    )
}

fun ColorScheme.pureBlack(apply: Boolean) =
    if (apply) copy(
        surface = Color.Black,
        background = Color.Black,
    ) else this
