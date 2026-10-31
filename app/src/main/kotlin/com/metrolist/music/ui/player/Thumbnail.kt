/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.metrolist.music.LocalListenTogetherManager
import com.metrolist.music.LocalDeviceConnectManager
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.CropAlbumArtKey
import com.metrolist.music.constants.HidePlayerThumbnailKey
import com.metrolist.music.constants.PlayerBackgroundStyle
import com.metrolist.music.constants.PlayerBackgroundStyleKey
import com.metrolist.music.constants.PlayerHorizontalPadding
import com.metrolist.music.constants.SeekExtraSeconds
import com.metrolist.music.constants.SwipeThumbnailKey
import com.metrolist.music.constants.ThumbnailCornerRadius
import com.metrolist.music.listentogether.RoomRole
import com.metrolist.music.ui.utils.resize
import com.metrolist.music.ui.component.CastButton
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect

/**
 * Pre-calculated thumbnail dimensions to avoid repeated calculations during recomposition.
 * All values are computed once and cached.
 */
@Immutable
data class ThumbnailDimensions(
    val itemWidth: Dp,
    val containerSize: Dp,
    val thumbnailSize: Dp,
    val cornerRadius: Dp
)

/**
 * Cached media items data to prevent recalculation on every recomposition.
 */
@Immutable
data class MediaItemsData(
    val items: List<MediaItem>,
    val currentIndex: Int
)

/**
 * Calculate thumbnail dimensions once based on container size.
 * This function is marked as @Stable to indicate it produces stable results.
 * In landscape mode, uses the smaller dimension (height) to ensure square thumbnail fits.
 */
@Stable
private fun calculateThumbnailDimensions(
    containerWidth: Dp,
    containerHeight: Dp = containerWidth,
    horizontalPadding: Dp = PlayerHorizontalPadding,
    cornerRadius: Dp = ThumbnailCornerRadius,
    isLandscape: Boolean = false
): ThumbnailDimensions {
    // In landscape, use height as the constraining dimension for a square thumbnail
    val effectiveSize = if (isLandscape) {
        minOf(containerWidth, containerHeight) - (horizontalPadding * 2)
    } else {
        containerWidth - (horizontalPadding * 2)
    }
    return ThumbnailDimensions(
        itemWidth = containerWidth,
        containerSize = containerWidth,
        thumbnailSize = effectiveSize,
        cornerRadius = cornerRadius * 2
    )
}

/**
 * Get media items for the thumbnail carousel.
 * Calculates previous, current, and next items based on shuffle mode.
 */
@Stable
private fun getMediaItems(
    player: Player,
    swipeThumbnail: Boolean
): MediaItemsData {
    val timeline = player.currentTimeline
    
    val currentMediaItem = try {
        player.currentMediaItem
    } catch (e: Exception) { null }
    
    val previousMediaItem = if (swipeThumbnail && !timeline.isEmpty) {
        val previousIndex = player.previousMediaItemIndex
        if (previousIndex != C.INDEX_UNSET) {
            try { player.getMediaItemAt(previousIndex) } catch (e: Exception) { null }
        } else null
    } else null

    val nextMediaItem = if (swipeThumbnail && !timeline.isEmpty) {
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex != C.INDEX_UNSET) {
            try { player.getMediaItemAt(nextIndex) } catch (e: Exception) { null }
        } else null
    } else null

    val items = listOfNotNull(previousMediaItem, currentMediaItem, nextMediaItem)
    val currentMediaIndex = items.indexOf(currentMediaItem)
    
    return MediaItemsData(items, currentMediaIndex)
}

/**
 * Get text color based on player background style.
 * Computed once per background style change.
 */
@Stable
@Composable
private fun getTextColor(playerBackground: PlayerBackgroundStyle): Color {
    return when (playerBackground) {
        PlayerBackgroundStyle.DEFAULT -> MaterialTheme.colorScheme.onBackground
        PlayerBackgroundStyle.BLUR -> Color.White
        PlayerBackgroundStyle.GRADIENT -> Color.White
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Thumbnail(
    sliderPositionProvider: () -> Long?,
    modifier: Modifier = Modifier,
    isPlayerExpanded: () -> Boolean = { true },
    isLandscape: Boolean = false,
    isListenTogetherGuest: Boolean = false,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val context = LocalContext.current
    val layoutDirection = LocalLayoutDirection.current

    // Collect states
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val error by playerConnection.error.collectAsState()
    val queueTitle by playerConnection.queueTitle.collectAsStateWithLifecycle()
    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsStateWithLifecycle()
    val canSkipNext by playerConnection.canSkipNext.collectAsStateWithLifecycle()
    val queueWindows by playerConnection.queueWindows.collectAsState()

    // Preferences - computed once
    // Disable swipe for Listen Together guests
    val swipeThumbnailPref by rememberPreference(SwipeThumbnailKey, true)
    val swipeThumbnail = swipeThumbnailPref && !isListenTogetherGuest
    val hidePlayerThumbnail by rememberPreference(HidePlayerThumbnailKey, false)
    val cropAlbumArt by rememberPreference(CropAlbumArtKey, false)
    val playerBackground by rememberEnumPreference(
        key = PlayerBackgroundStyleKey,
        defaultValue = PlayerBackgroundStyle.DEFAULT
    )
    
    // Pre-calculate text color based on background style
    val textBackgroundColor = getTextColor(playerBackground)
    
    // Grid state
    val thumbnailLazyGridState = rememberLazyGridState()
    
    // Calculate media items data - memoized
    val mediaItemsData by remember(
        playerConnection.player.currentMediaItemIndex,
        playerConnection.player.shuffleModeEnabled,
        queueWindows,
        swipeThumbnail,
        mediaMetadata
    ) {
        derivedStateOf {
            getMediaItems(playerConnection.player, swipeThumbnail)
        }
    }
    
    val mediaItems = mediaItemsData.items
    val currentMediaIndex = mediaItemsData.currentIndex

    val nextArtwork = mediaItems.getOrNull(currentMediaIndex + 1)
        ?.mediaMetadata?.artworkUri?.toString()?.let { playerArtworkUrl(it, false) }
    val isRemoteController = LocalDeviceConnectManager.current?.isRemoteActive == true
    // Warm only the next small cover on the remote controller. No extra image work is
    // needed on the device actually playing audio, and the full-size cover stays on demand.
    LaunchedEffect(nextArtwork, isRemoteController) {
        if (isRemoteController && !nextArtwork.isNullOrBlank()) {
            runCatching {
                context.imageLoader.execute(
                    ImageRequest.Builder(context)
                        .data(nextArtwork)
                        .size(200, 200)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .networkCachePolicy(CachePolicy.ENABLED)
                        .build()
                )
            }
        }
    }

    // Snap behavior - created once per grid state
    val thumbnailSnapLayoutInfoProvider = remember(thumbnailLazyGridState) {
        ThumbnailSnapLayoutInfoProvider(
            lazyGridState = thumbnailLazyGridState,
            positionInLayout = { layoutSize, itemSize ->
                (layoutSize / 2f - itemSize / 2f)
            },
            velocityThreshold = 500f
        )
    }

    // Current item tracking - derived state for efficiency
    val currentItem by remember { derivedStateOf { thumbnailLazyGridState.firstVisibleItemIndex } }
    val isDragged by thumbnailLazyGridState.interactionSource.collectIsDraggedAsState()
    val currentMediaIndexState by rememberUpdatedState(currentMediaIndex)
    val canSkipNextState by rememberUpdatedState(canSkipNext)
    val canSkipPreviousState by rememberUpdatedState(canSkipPrevious)

    // Dispatch only after an actual drag settles. The old offset-based callback often ran
    // while the scroll was still moving and missed the final snap on remote controllers.
    LaunchedEffect(thumbnailLazyGridState, swipeThumbnail) {
        var userSwiped = false
        snapshotFlow {
            Triple(isDragged, thumbnailLazyGridState.isScrollInProgress,
                thumbnailLazyGridState.firstVisibleItemIndex)
        }.collect { (dragging, scrolling, selectedIndex) ->
            if (dragging) userSwiped = true
            if (userSwiped && !scrolling) {
                userSwiped = false
                if (swipeThumbnail && currentMediaIndexState >= 0) {
                    when {
                        selectedIndex > currentMediaIndexState && canSkipNextState -> playerConnection.seekToNext()
                        selectedIndex < currentMediaIndexState && canSkipPreviousState -> playerConnection.seekToPrevious()
                    }
                }
            }
        }
    }

    // Update position when song changes
    LaunchedEffect(mediaMetadata, canSkipPrevious, canSkipNext) {
        val index = maxOf(0, currentMediaIndex)
        if (index >= 0 && index < mediaItems.size) {
            try {
                thumbnailLazyGridState.animateScrollToItem(index)
            } catch (e: Exception) {
                thumbnailLazyGridState.scrollToItem(index)
            }
        }
    }

    LaunchedEffect(playerConnection.player.currentMediaItemIndex) {
        val index = mediaItemsData.currentIndex
        if (index >= 0 && index != currentItem) {
            thumbnailLazyGridState.scrollToItem(index)
        }
    }

    // Seek effect state
    var showSeekEffect by remember { mutableStateOf(false) }
    var seekDirection by remember { mutableStateOf("") }

    Box(
        modifier = modifier
            .graphicsLayer {
                // Use hardware layer for entire Thumbnail to ensure smooth 120Hz animations
                compositingStrategy = CompositingStrategy.Offscreen
            }
    ) {
        // Error view
        AnimatedVisibility(
            visible = error != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .padding(32.dp)
                .align(Alignment.Center),
        ) {
            error?.let { playbackError ->
                PlaybackError(
                    error = playbackError,
                    retry = playerConnection.player::prepare,
                )
            }
        }

        // Main thumbnail view
        AnimatedVisibility(
            visible = error == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .fillMaxSize()
                .then(if (!isLandscape) Modifier.statusBarsPadding() else Modifier),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = if (isLandscape) Arrangement.Center else Arrangement.Top
            ) {
                // Now Playing header - hide in landscape mode
                if (!isLandscape) {
                    ThumbnailHeader(
                        queueTitle = queueTitle,
                        albumTitle = mediaMetadata?.album?.title,
                        textColor = textBackgroundColor
                    )
                }
                
                // Thumbnail content
                BoxWithConstraints(
                    contentAlignment = Alignment.Center,
                    modifier = if (isLandscape) {
                        Modifier.weight(1f, false)
                    } else {
                        Modifier.fillMaxSize()
                    }
                ) {
                    // Calculate dimensions once per size change, considering landscape mode
                    val dimensions = remember(maxWidth, maxHeight, isLandscape) {
                        calculateThumbnailDimensions(
                            containerWidth = maxWidth,
                            containerHeight = maxHeight,
                            isLandscape = isLandscape
                        )
                    }

                    // Remember the onSeek callback to prevent recomposition
                    val onSeekCallback = remember {
                        { direction: String, showEffect: Boolean ->
                            seekDirection = direction
                            showSeekEffect = showEffect
                        }
                    }
                    
                    // Derive scroll enabled state to prevent unnecessary recomposition
                    val isScrollEnabled by remember(swipeThumbnail) {
                        derivedStateOf { swipeThumbnail && isPlayerExpanded() }
                    }
                    
                    LazyHorizontalGrid(
                        state = thumbnailLazyGridState,
                        rows = GridCells.Fixed(1),
                        flingBehavior = rememberSnapFlingBehavior(thumbnailSnapLayoutInfoProvider),
                        userScrollEnabled = isScrollEnabled,
                        modifier = if (isLandscape) {
                            Modifier.size(dimensions.thumbnailSize + (PlayerHorizontalPadding * 2))
                        } else {
                            Modifier.fillMaxSize()
                        }
                    ) {
                        itemsIndexed(
                            items = mediaItems,
                            key = { index, item ->
                                // A track can occur in more than one adjacent queue position.
                                "thumbnail_$index:${item.mediaId}"
                            }
                        ) { index, item ->
                            ThumbnailItem(
                                item = item,
                                dimensions = dimensions,
                                hidePlayerThumbnail = hidePlayerThumbnail,
                                cropAlbumArt = cropAlbumArt,
                                textBackgroundColor = textBackgroundColor,
                                layoutDirection = layoutDirection,
                                onSeek = onSeekCallback,
                                playerConnection = playerConnection,
                                context = context,
                                isLandscape = isLandscape,
                                isListenTogetherGuest = isListenTogetherGuest,
                                isCurrentSong = index == currentMediaIndex,
                                currentMediaThumbnail = mediaMetadata?.thumbnailUrl
                            )
                        }
                    }
                }
            }
        }

        // Seek effect
        LaunchedEffect(showSeekEffect) {
            if (showSeekEffect) {
                delay(1000)
                showSeekEffect = false
            }
        }

        AnimatedVisibility(
            visible = showSeekEffect,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            SeekEffectOverlay(seekDirection = seekDirection)
        }
    }
}

/**
 * Header component showing "Now Playing" and queue/album title.
 */
@Composable
private fun ThumbnailHeader(
    queueTitle: String?,
    albumTitle: String?,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    val connectManager = LocalDeviceConnectManager.current
    val connectState by connectManager?.state?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(com.metrolist.music.connect.DeviceConnectUiState()) }
    val remoteName = if (connectManager?.isRemoteActive == true) {
        connectState.peerName ?: connectState.trustedDeviceName
    } else null
    val listenTogetherManager = LocalListenTogetherManager.current
    val listenTogetherRoleState = listenTogetherManager?.role?.collectAsStateWithLifecycle(initialValue = RoomRole.NONE)
    val isListenTogetherGuest = listenTogetherRoleState?.value == RoomRole.GUEST
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 48.dp)
        ) {
            // Listen Together indicator
            if (listenTogetherRoleState?.value != RoomRole.NONE) {
                Text(
                    text = if (listenTogetherRoleState?.value == RoomRole.HOST) "Hosting Listen Together" else "Listening Together",
                    style = MaterialTheme.typography.titleMedium,
                    color = textColor
                )
            } else {
                Text(
                    text = if (remoteName != null) stringResource(R.string.device_connect_playing_on, remoteName)
                        else stringResource(R.string.now_playing),
                    style = MaterialTheme.typography.titleMedium,
                    color = textColor
                )
            }
            val playingFrom = queueTitle ?: albumTitle
            if (!playingFrom.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = playingFrom,
                    style = MaterialTheme.typography.titleMedium,
                    color = textColor.copy(alpha = 0.8f),
                    maxLines = 1,
                    modifier = Modifier.basicMarquee()
                )
            }
            if (remoteName != null) {
                TextButton(
                    onClick = { connectManager?.requestOutputSwitch() },
                    enabled = connectManager?.canSwitchOutput == true,
                ) {
                    Text(
                        text = stringResource(
                            if (connectManager?.canSwitchOutput == true)
                                R.string.device_connect_switch_output_here
                            else R.string.device_connect_syncing_queue
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = textColor,
                    )
                }
            }
        }
    }
}

/**
 * Individual thumbnail item in the carousel.
 */
@Composable
private fun ThumbnailItem(
    item: MediaItem,
    dimensions: ThumbnailDimensions,
    hidePlayerThumbnail: Boolean,
    cropAlbumArt: Boolean,
    textBackgroundColor: Color,
    layoutDirection: LayoutDirection,
    onSeek: (String, Boolean) -> Unit,
    playerConnection: com.metrolist.music.playback.PlayerConnection,
    context: android.content.Context,
    isLandscape: Boolean = false,
    isListenTogetherGuest: Boolean = false,
    isCurrentSong: Boolean = false,
    currentMediaThumbnail: String? = null,
    modifier: Modifier = Modifier,
) {
    val incrementalSeekSkipEnabled by rememberPreference(SeekExtraSeconds, defaultValue = false)
    var skipMultiplier by remember { mutableIntStateOf(1) }
    var lastTapTime by remember { mutableLongStateOf(0L) }

    Box(
        modifier = modifier
            .then(
                if (isLandscape) {
                    Modifier.size(dimensions.thumbnailSize + (PlayerHorizontalPadding * 2))
                } else {
                    Modifier
                        .width(dimensions.itemWidth)
                        .fillMaxSize()
                }
            )
            .padding(horizontal = PlayerHorizontalPadding)
            .graphicsLayer {
                // Render entire thumbnail item on separate hardware layer for smooth animations
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        if (isListenTogetherGuest) return@detectTapGestures

                        val currentPosition = playerConnection.player.currentPosition
                        val duration = playerConnection.player.duration

                        val now = System.currentTimeMillis()
                        if (incrementalSeekSkipEnabled && now - lastTapTime < 1000) {
                            skipMultiplier++
                        } else {
                            skipMultiplier = 1
                        }
                        lastTapTime = now

                        val skipAmount = 5000 * skipMultiplier

                        val isLeftSide = (layoutDirection == LayoutDirection.Ltr && offset.x < size.width / 2) ||
                                (layoutDirection == LayoutDirection.Rtl && offset.x > size.width / 2)

                        if (isLeftSide) {
                            playerConnection.player.seekTo((currentPosition - skipAmount).coerceAtLeast(0))
                            onSeek(context.getString(R.string.seek_backward_dynamic, skipAmount / 1000), true)
                        } else {
                            playerConnection.player.seekTo((currentPosition + skipAmount).coerceAtMost(duration))
                            onSeek(context.getString(R.string.seek_forward_dynamic, skipAmount / 1000), true)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(dimensions.thumbnailSize)
                .clip(RoundedCornerShape(dimensions.cornerRadius))
        ) {
            if (hidePlayerThumbnail) {
                HiddenThumbnailPlaceholder(textBackgroundColor = textBackgroundColor)
            } else {
                val artworkUri = if (isCurrentSong && !currentMediaThumbnail.isNullOrBlank()) {
                    currentMediaThumbnail
                } else {
                    item.mediaMetadata.artworkUri?.toString()
                }
                val artworkUriToUse = playerArtworkUrl(artworkUri, isCurrentSong)

                key(artworkUriToUse) {
                    ThumbnailImage(
                        artworkUri = artworkUriToUse,
                        cropArtwork = cropAlbumArt
                    )
                }
            }
            
            // Cast button at top-right corner of thumbnail
            CastButton(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                tintColor = textBackgroundColor
            )
        }
    }
}

/**
 * Placeholder shown when thumbnail is hidden.
 */
@Composable
private fun HiddenThumbnailPlaceholder(
    textBackgroundColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.small_icon),
            contentDescription = stringResource(R.string.hide_player_thumbnail),
            tint = textBackgroundColor.copy(alpha = 0.7f),
            modifier = Modifier.size(120.dp)
        )
    }
}

internal fun playerArtworkUrl(artworkUri: String?, isCurrentSong: Boolean): String? {
    val targetSize = if (isCurrentSong) 1080 else 200
    return artworkUri?.resize(targetSize, targetSize)
}

/**
 * Actual thumbnail image with caching and hardware layer rendering.
 */
@Composable
private fun ThumbnailImage(
    artworkUri: String?,
    cropArtwork: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val imageRequest = remember(context, artworkUri) {
        ImageRequest.Builder(context)
            .data(artworkUri)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .build()
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                // Use offscreen compositing for hardware acceleration during animations
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        AsyncImage(
            model = imageRequest,
            contentDescription = null,
            contentScale = if (cropArtwork) ContentScale.Crop else ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * Seek effect overlay showing seek direction.
 */
@Composable
private fun SeekEffectOverlay(
    seekDirection: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = seekDirection,
        color = Color.White,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            .padding(8.dp)
    )
}
