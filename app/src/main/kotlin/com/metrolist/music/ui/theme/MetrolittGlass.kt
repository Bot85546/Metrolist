package com.metrolist.music.ui.theme

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens

val LocalMetrolittBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

@Composable
fun Modifier.metrolittGlass(shape: Shape, tint: Color): Modifier {
    val backdrop = LocalMetrolittBackdrop.current
    if (backdrop == null || Build.VERSION.SDK_INT < 31) return this
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            blur(12.dp.toPx())
            if (Build.VERSION.SDK_INT >= 33) lens(size.minDimension / 4f, size.minDimension / 2f, false)
        },
        onDrawSurface = { drawRect(tint.copy(alpha = 0.78f)) },
    )
}
