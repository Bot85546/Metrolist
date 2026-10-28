package com.metrolist.music.playback

/**
 * Loudness metadata can arrive while ExoPlayer is preparing the next track,
 * before that track becomes the player's currentMediaItem.
 */
internal data class TrackLoudness(
    val loudnessDb: Double?,
    val perceptualLoudnessDb: Double?,
)

/**
 * A small, thread-safe LRU for loudness data obtained by the stream resolver.
 * ResolvingDataSource can run on a loader thread while media transitions run
 * on the playback thread. Store metadata early; apply it only to the active
 * track so prebuffering never changes the currently playing song's volume.
 */
internal class TrackLoudnessCache(private val capacity: Int = 64) {
    init {
        require(capacity > 0)
    }

    private val entries = object : LinkedHashMap<String, TrackLoudness>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackLoudness>?): Boolean =
            size > capacity
    }

    @Synchronized
    fun record(mediaId: String, loudnessDb: Double?, perceptualLoudnessDb: Double?) {
        // No loudness measurements must never overwrite valid ones. This
        // occurs when the cache is read before fresh metadata is fetched.
        if (loudnessDb == null && perceptualLoudnessDb == null) return
        entries[mediaId] = TrackLoudness(loudnessDb, perceptualLoudnessDb)
    }

    @Synchronized
    fun get(mediaId: String): TrackLoudness? = entries[mediaId]
}
