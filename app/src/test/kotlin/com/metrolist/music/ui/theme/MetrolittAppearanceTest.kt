package com.metrolist.music.ui.theme

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.metrolist.music.constants.PlayerBackgroundStyleKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MetrolittAppearanceTest {
    @Test fun `profile preserves unrelated preferences and settles after migration`() = runBlocking {
        val unrelated = stringPreferencesKey("test_unrelated_preference")
        val input = preferencesOf(unrelated to "preserved", PlayerBackgroundStyleKey to "DEFAULT")
        val migration = MetrolittAppearance()
        assertTrue(migration.shouldMigrate(input))
        val output = migration.migrate(input)
        assertEquals("preserved", output[unrelated])
        assertEquals("GRADIENT", output[PlayerBackgroundStyleKey])
        assertFalse(migration.shouldMigrate(output))
    }
}
