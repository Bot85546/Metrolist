package com.metrolist.music.ui.theme

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import com.metrolist.music.constants.*

/** Fixed visual profile; unrelated preferences and account data are preserved. */
internal class MetrolittAppearance : DataMigration<Preferences> {
    private val profile = preferencesOf(
        DarkModeKey to "AUTO",
        PureBlackKey to true,
        DynamicThemeKey to false,
        SelectedThemeColorKey to 0xFF3949AB.toInt(),
        PureBlackMiniPlayerKey to true,
        MiniPlayerBackgroundStyleKey to "GRADIENT",
        PlayerBackgroundStyleKey to "GRADIENT",
        GridItemsSizeKey to "SMALL",
        SliderStyleKey to "WAVY",
        SquigglySliderKey to false,
        CropAlbumArtKey to true,
        EnableHighRefreshRateKey to true,
        EnableLandscapeScalingKey to false,
        SwipeToSongKey to true,
        LyricsGlowEffectKey to false,
        LyricsAnimationStyleKey to "APPLE",
        LyricsTextSizeKey to 24f,
        LyricsLineSpacingKey to 1f,
        ExperimentalLyricsKey to false,
        RespectAgentPositioningKey to true,
        ShowCachedPlaylistKey to false,
        ShowUploadedPlaylistKey to false,
        ShowLikedPlaylistKey to true,
        ShowDownloadedPlaylistKey to true,
        ShowTopPlaylistKey to true,
        UseNewPlayerDesignKey to true,
        UseNewMiniPlayerDesignKey to true,
        SwipeSensitivityKey to 0.73f,
    )

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        profile.asMap().any { (key, value) -> currentData.asMap()[key] != value }

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply {
            this[DarkModeKey] = "AUTO"
            this[PureBlackKey] = true
            this[DynamicThemeKey] = false
            this[SelectedThemeColorKey] = 0xFF3949AB.toInt()
            this[PureBlackMiniPlayerKey] = true
            this[MiniPlayerBackgroundStyleKey] = "GRADIENT"
            this[PlayerBackgroundStyleKey] = "GRADIENT"
            this[GridItemsSizeKey] = "SMALL"
            this[SliderStyleKey] = "WAVY"
            this[SquigglySliderKey] = false
            this[CropAlbumArtKey] = true
            this[EnableHighRefreshRateKey] = true
            this[EnableLandscapeScalingKey] = false
            this[SwipeToSongKey] = true
            this[LyricsGlowEffectKey] = false
            this[LyricsAnimationStyleKey] = "APPLE"
            this[LyricsTextSizeKey] = 24f
            this[LyricsLineSpacingKey] = 1f
            this[ExperimentalLyricsKey] = false
            this[RespectAgentPositioningKey] = true
            this[ShowCachedPlaylistKey] = false
            this[ShowUploadedPlaylistKey] = false
            this[ShowLikedPlaylistKey] = true
            this[ShowDownloadedPlaylistKey] = true
            this[ShowTopPlaylistKey] = true
            this[UseNewPlayerDesignKey] = true
            this[UseNewMiniPlayerDesignKey] = true
            this[SwipeSensitivityKey] = 0.73f
        }

    override suspend fun cleanUp() = Unit
}
