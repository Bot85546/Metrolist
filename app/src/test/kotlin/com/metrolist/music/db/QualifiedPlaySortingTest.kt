/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.constants.AlbumSortType
import com.metrolist.music.constants.ArtistSortType
import com.metrolist.music.constants.SongSortType
import com.metrolist.music.db.entities.AlbumEntity
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.Event
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.db.entities.SongArtistMap
import com.metrolist.music.db.entities.SongEntity
import java.time.LocalDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QualifiedPlaySortingTest {
    private lateinit var database: InternalDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        listOf(
            SongEntity(id = "short", title = "Short", duration = 120, totalPlayTime = 175_000,
                liked = true, inLibrary = TODAY, isDownloaded = true, isUploaded = true, albumId = "short"),
            SongEntity(id = "long", title = "Long", duration = 240, totalPlayTime = 950_000,
                liked = true, inLibrary = TODAY, isDownloaded = true, isUploaded = true, albumId = "long"),
            SongEntity(id = "unknown", title = "Unknown", duration = -1, totalPlayTime = 30_000,
                liked = true, inLibrary = TODAY, isDownloaded = true, isUploaded = true, albumId = "unknown"),
            SongEntity(id = "zero", title = "Zero", duration = 300, totalPlayTime = 2_000_000,
                liked = true, inLibrary = TODAY, isDownloaded = true, isUploaded = true, albumId = "zero"),
        ).forEach { database.dao.insert(it) }

        for (id in SONG_IDS) {
            database.dao.insert(ArtistEntity(id = id, name = id, isLocal = true, bookmarkedAt = TODAY))
            database.dao.insert(AlbumEntity(id = id, title = id, songCount = 1, duration = 120,
                bookmarkedAt = TODAY, isUploaded = true))
            database.dao.insert(SongArtistMap(songId = id, artistId = id, position = 0))
        }
        database.dao.insert(PlaylistEntity(id = PLAYLIST_ID, name = "Sorted songs"))
        SONG_IDS.forEachIndexed { index, id ->
            database.dao.insert(PlaylistSongMap(playlistId = PLAYLIST_ID, songId = id, position = index))
        }

        insertPlay("short", 84_000)  // Exactly 70% is not a qualifying play.
        insertPlay("short", 84_001)
        insertPlay("short", 90_000)
        insertPlay("long", 190_000)
        insertPlay("unknown", 29_999)
        insertPlay("unknown", 30_000) // Unknown duration uses the Top # 30s fallback.
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `song sorts count qualifying sessions rather than total listening time`() = runBlocking {
        assertEquals(ASCENDING, database.dao.songs(SongSortType.PLAY_TIME, false).first().map { it.song.id })
        assertEquals(ASCENDING, database.dao.likedSongs(SongSortType.PLAY_TIME, false).first().map { it.song.id })
        assertEquals(ASCENDING, database.dao.downloadedSongs(SongSortType.PLAY_TIME, false).first().map { it.song.id })
        assertEquals(ASCENDING, database.dao.uploadedSongs(SongSortType.PLAY_TIME, false).first().map { it.song.id })

        assertEquals(ASCENDING.reversed(), database.dao.songs(SongSortType.PLAY_TIME, true).first().map { it.song.id })
        assertEquals(ASCENDING.reversed(), database.dao.likedSongs(SongSortType.PLAY_TIME, true).first().map { it.song.id })
    }

    @Test
    fun `playlist sort uses qualifying plays and stable tie breakers`() = runBlocking {
        assertEquals(
            ASCENDING,
            database.dao.playlistSongsByPlayCountAsc(PLAYLIST_ID).first().map { it.song.song.id },
        )
    }

    @Test
    fun `artist and album sorts aggregate qualifying plays of their songs`() = runBlocking {
        assertEquals(ASCENDING, database.dao.artists(ArtistSortType.PLAY_TIME, false).first().map { it.artist.id })
        assertEquals(ASCENDING, database.dao.artistsBookmarked(ArtistSortType.PLAY_TIME, false).first().map { it.artist.id })
        assertEquals(ASCENDING, database.dao.albums(AlbumSortType.PLAY_TIME, false).first().map { it.album.id })
        assertEquals(ASCENDING, database.dao.albumsLiked(AlbumSortType.PLAY_TIME, false).first().map { it.album.id })
        assertEquals(ASCENDING, database.dao.albumsUploaded(AlbumSortType.PLAY_TIME, false).first().map { it.album.id })
    }

    private fun insertPlay(songId: String, playTime: Long) {
        database.dao.insert(Event(songId = songId, timestamp = TODAY, playTime = playTime))
    }

    private companion object {
        val TODAY: LocalDateTime = LocalDateTime.of(2026, 10, 29, 12, 0)
        const val PLAYLIST_ID = "qualified-play-sort-test"
        val SONG_IDS = listOf("short", "long", "unknown", "zero")
        val ASCENDING = listOf("zero", "unknown", "long", "short")
    }
}
