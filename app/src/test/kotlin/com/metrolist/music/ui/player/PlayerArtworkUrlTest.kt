/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerArtworkUrlTest {
    @Test
    fun `playing song requests high resolution even from a cached small thumbnail URL`() {
        val thumbnail = "https://yt3.googleusercontent.com/cover=w60-h60-l90-rj"

        assertEquals(
            "https://yt3.googleusercontent.com/cover=w1080-h1080-p-l90-rj",
            playerArtworkUrl(thumbnail, isCurrentSong = true),
        )
    }

    @Test
    fun `neighboring songs use small lightweight previews`() {
        val thumbnail = "https://yt3.googleusercontent.com/cover=w1080-h1080-p-l90-rj"

        assertEquals(
            "https://yt3.googleusercontent.com/cover=w200-h200-p-l90-rj",
            playerArtworkUrl(thumbnail, isCurrentSong = false),
        )
    }

    @Test
    fun `same high resolution URL is reused on subsequent playback`() {
        val thumbnail = "https://lh3.googleusercontent.com/cover=w1080-h1080-p-l90-rj"

        assertEquals(thumbnail, playerArtworkUrl(thumbnail, isCurrentSong = true))
    }

    @Test
    fun `nonresizable image URLs and missing artwork remain unchanged`() {
        val thumbnail = "https://i.ytimg.com/vi/example/hqdefault.jpg"

        assertEquals(thumbnail, playerArtworkUrl(thumbnail, isCurrentSong = true))
        assertEquals(null, playerArtworkUrl(null, isCurrentSong = true))
    }
}
