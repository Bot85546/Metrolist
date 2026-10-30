/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryCategoryArtworkTest {
    @Test
    fun `each virtual collection has its own artwork`() {
        val ids = mapOf(
            "__metrolitt_virtual_liked__" to LibraryCategory.LIKED,
            "__metrolitt_virtual_downloaded__" to LibraryCategory.DOWNLOADED,
            "__metrolitt_virtual_top__" to LibraryCategory.TOP,
            "__metrolitt_virtual_cached__" to LibraryCategory.CACHED,
            "__metrolitt_virtual_uploaded__" to LibraryCategory.UPLOADED,
        )

        ids.forEach { (id, expected) ->
            assertEquals(expected, LibraryCategory.fromVirtualPlaylistId(id))
        }
    }

    @Test
    fun `normal playlists retain their own artwork`() {
        assertNull(LibraryCategory.fromVirtualPlaylistId("PL_youtube_playlist"))
        assertNull(LibraryCategory.fromVirtualPlaylistId("LM_local_playlist"))
        assertNull(LibraryCategory.fromVirtualPlaylistId("Liked"))
    }
}
