/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.metrolist.music.R
import com.metrolist.music.constants.ThumbnailCornerRadius

enum class LibraryCategory(
    val icon: Int,
    val gradientStart: Color,
    val gradientEnd: Color,
) {
    LIKED(R.drawable.favorite, Color(0xFFFF598A), Color(0xFFB538C8)),
    DOWNLOADED(R.drawable.download, Color(0xFF40C4F7), Color(0xFF2861DD)),
    TOP(R.drawable.crown, Color(0xFFFFC350), Color(0xFFF06B39)),
    CACHED(R.drawable.cached, Color(0xFF22D7B5), Color(0xFF087F96)),
    UPLOADED(R.drawable.backup, Color(0xFFB083F5), Color(0xFF6156DD));

    companion object {
        fun fromVirtualPlaylistId(id: String): LibraryCategory? =
            when (id) {
                "__metrolitt_virtual_liked__" -> LIKED
                "__metrolitt_virtual_downloaded__" -> DOWNLOADED
                "__metrolitt_virtual_top__" -> TOP
                "__metrolitt_virtual_cached__" -> CACHED
                "__metrolitt_virtual_uploaded__" -> UPLOADED
                else -> null
            }
    }
}

@Composable
fun LibraryCategoryThumbnail(
    category: LibraryCategory,
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(ThumbnailCornerRadius),
) {
    val gradient = remember(category) {
        Brush.linearGradient(listOf(category.gradientStart, category.gradientEnd))
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(gradient),
        contentAlignment = Alignment.Center,
    ) {
        // Soft highlights give the five flat vector symbols a shared album-art style.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = size * 0.22f, y = -(size * 0.24f))
                .size(size * 0.83f)
                .background(Color.White.copy(alpha = 0.13f), CircleShape),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = -(size * 0.33f), y = size * 0.28f)
                .size(size * 0.82f)
                .background(Color.White.copy(alpha = 0.08f), CircleShape),
        )
        Box(
            modifier = Modifier
                .size(size * 0.65f)
                .background(Color.White.copy(alpha = 0.14f), CircleShape),
        )
        Icon(
            painter = painterResource(category.icon),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size * 0.46f),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = size * 0.13f, y = size * 0.15f)
                .size(size * 0.055f)
                .background(Color.White.copy(alpha = 0.64f), CircleShape),
        )
    }
}
