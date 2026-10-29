package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackLoudnessCacheTest {
    @Test
    fun `records metadata before a song becomes active`() {
        val cache = TrackLoudnessCache()
        cache.record("next-song", loudnessDb = -1.5, perceptualLoudnessDb = -8.5)

        assertEquals(TrackLoudness(-1.5, -8.5), cache.get("next-song"))
    }

    @Test
    fun `missing metadata does not erase an earlier measurement`() {
        val cache = TrackLoudnessCache()
        cache.record("song", loudnessDb = -3.0, perceptualLoudnessDb = -10.0)
        cache.record("song", loudnessDb = null, perceptualLoudnessDb = null)

        assertEquals(TrackLoudness(-3.0, -10.0), cache.get("song"))
    }

    @Test
    fun `fresh loudness metadata replaces an older measurement`() {
        val cache = TrackLoudnessCache()
        cache.record("song", loudnessDb = 2.0, perceptualLoudnessDb = null)
        cache.record("song", loudnessDb = 1.0, perceptualLoudnessDb = -13.0)

        assertEquals(TrackLoudness(1.0, -13.0), cache.get("song"))
    }

    @Test
    fun `evicts the least recently used song`() {
        val cache = TrackLoudnessCache(capacity = 2)
        cache.record("A", -1.0, -13.0)
        cache.record("B", -2.0, -12.0)
        cache.get("A")
        cache.record("C", -3.0, -11.0)

        assertEquals(TrackLoudness(-1.0, -13.0), cache.get("A"))
        assertNull(cache.get("B"))
        assertEquals(TrackLoudness(-3.0, -11.0), cache.get("C"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects zero capacity`() {
        TrackLoudnessCache(capacity = 0)
    }
}
