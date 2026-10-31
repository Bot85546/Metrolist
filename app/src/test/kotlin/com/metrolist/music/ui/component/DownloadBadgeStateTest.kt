package com.metrolist.music.ui.component

import androidx.media3.exoplayer.offline.Download
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadBadgeStateTest {
    @Test
    fun `empty collection remains stopped`() {
        assertEquals(Download.STATE_STOPPED, aggregateDownloadBadgeState(emptyList<Int>()) { it })
    }

    @Test
    fun `entirely completed collection displays completed`() {
        assertEquals(
            Download.STATE_COMPLETED,
            aggregateDownloadBadgeState(listOf(Download.STATE_COMPLETED, Download.STATE_COMPLETED)) { it },
        )
    }

    @Test
    fun `mixed completed and missing items remain stopped`() {
        assertEquals(
            Download.STATE_STOPPED,
            aggregateDownloadBadgeState(listOf<Int?>(Download.STATE_COMPLETED, null)) { it },
        )
    }

    @Test
    fun `queued and active downloads take precedence over missing items`() {
        for (pending in listOf(Download.STATE_QUEUED, Download.STATE_DOWNLOADING)) {
            assertEquals(
                Download.STATE_DOWNLOADING,
                aggregateDownloadBadgeState(listOf<Int?>(null, pending, Download.STATE_COMPLETED)) { it },
            )
        }
    }

    @Test
    fun `pending download ends the scan immediately`() {
        var inspected = 0
        val state = aggregateDownloadBadgeState(listOf(Download.STATE_QUEUED, 100, 101)) {
            inspected++
            it
        }
        assertEquals(Download.STATE_DOWNLOADING, state)
        assertEquals(1, inspected)
    }
}
