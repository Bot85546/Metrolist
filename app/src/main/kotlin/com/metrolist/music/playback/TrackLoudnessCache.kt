package com.metrolist.music.playback

/**
 * Loudness metadata can arrive while ExoPlayer is preparing the next track,
 * before that track becomes the player's currentMediaItem.
 */
internal data class TrackLoudness(
    val loudnessDb: Double?,
    val perceptualLoudnessDb: Double?,
    val clientName: String? = null,
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

    // YouTube's relative loudness is referenced to a *client-specific* target.
    // Observed responses can use -7 or -14 LUFS; a hard-coded -7 is wrong for
    // some streams. Learn a reference only from responses containing BOTH values.
    // The list of extraction clients is small, so eight entries is sufficient.
    private val clientReferences = object : LinkedHashMap<String, Double>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Double>?): Boolean =
            size > 8
    }
    private val conflictingClients = mutableSetOf<String>()

    @Synchronized
    fun record(
        mediaId: String,
        loudnessDb: Double?,
        perceptualLoudnessDb: Double?,
        clientName: String? = null,
    ) {
        val relative = loudnessDb?.takeIf { it.isFinite() }
        val absolute = perceptualLoudnessDb?.takeIf { it.isFinite() }
        if (relative == null && absolute == null) return

        if (!clientName.isNullOrBlank() && relative != null && absolute != null &&
            clientName !in conflictingClients
        ) {
            val referenceLufs = absolute - relative
            if (referenceLufs in -30.0..0.0) {
                val previousReference = clientReferences[clientName]
                when {
                    previousReference == null -> clientReferences[clientName] = referenceLufs
                    kotlin.math.abs(previousReference - referenceLufs) > 0.5 -> {
                        // If one client reports multiple reference scales, relative-only
                        // metadata from it cannot be interpreted safely.
                        clientReferences.remove(clientName)
                        conflictingClients.add(clientName)
                    }
                }
            }
        }

        val previous = entries[mediaId]
        // Partial responses must not discard a known absolute measurement.
        entries[mediaId] = TrackLoudness(
            relative ?: previous?.loudnessDb,
            absolute ?: previous?.perceptualLoudnessDb,
            clientName ?: previous?.clientName,
        )
    }

    @Synchronized
    fun get(mediaId: String): TrackLoudness? = entries[mediaId]

    /** A reference is usable only when learned for this exact extraction client. */
    @Synchronized
    fun referenceFor(mediaId: String): Double? {
        val entry = entries[mediaId] ?: return null
        if (entry.perceptualLoudnessDb != null) return null
        return entry.clientName?.let(clientReferences::get)
    }
}
