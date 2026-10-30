package com.metrolist.music.viewmodels

import com.metrolist.innertube.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSpeedDialTest {
    @Test
    fun `home recommendations fill sparse speed dial without replacing pinned items`() {
        val pinned = song("pinned")
        val home = song("home")

        val result =
            buildSpeedDialItems(
                pinned = listOf(pinned),
                keepListening = emptyList(),
                quickPicks = emptyList(),
                home = listOf(pinned.copy(title = "duplicate"), home),
            )

        assertEquals(listOf(pinned, home), result)
    }

    @Test
    fun `speed dial stops after 27 unique items and preserves source order`() {
        val pinned = song("pinned")
        val recent = song("recent")
        val quickPicks = (0 until 40).map { song("quick-$it") }

        val result =
            buildSpeedDialItems(
                pinned = listOf(pinned),
                keepListening = listOf(pinned.copy(title = "duplicate"), recent),
                quickPicks = quickPicks,
                home = listOf(song("home")),
            )

        assertEquals(27, result.size)
        assertEquals(
            listOf("pinned", "recent") + (0 until 25).map { "quick-$it" },
            result.map { it.id },
        )
        assertEquals(pinned, result.first())
    }

    @Test
    fun `speed dial returns an empty result for empty sources`() {
        assertEquals(
            emptyList<SongItem>(),
            buildSpeedDialItems(emptyList(), emptyList(), emptyList(), emptyList()),
        )
    }

    @Test
    fun `speed dial stops consuming lazy home recommendations after 27 items`() {
        var visited = 0
        val home = Iterable {
            (0 until 1000).asSequence().map {
                visited++
                song("home-$it")
            }.iterator()
        }

        val result = buildSpeedDialItems(
            pinned = emptyList(),
            keepListening = emptyList(),
            quickPicks = emptyList(),
            home = home,
        )

        assertEquals(27, result.size)
        assertEquals((0 until 27).map { "home-$it" }, result.map { it.id })
        assertEquals(27, visited)
    }

    private fun song(id: String) =
        SongItem(
            id = id,
            title = id,
            artists = emptyList(),
            thumbnail = "",
        )
}
