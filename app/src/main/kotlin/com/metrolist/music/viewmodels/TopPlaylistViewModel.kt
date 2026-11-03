/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.MyTopFilter
import com.metrolist.music.constants.MyTopFilterKey
import com.metrolist.music.extensions.toEnum
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import java.time.LocalDateTime
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TopPlaylistViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val top = savedStateHandle.get<String>("top")!!

    val topPeriod = context.dataStore.data
        .map { it[MyTopFilterKey].toEnum(MyTopFilter.ALL_TIME) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, MyTopFilter.ALL_TIME)

    fun setTopPeriod(period: MyTopFilter) {
        viewModelScope.launch {
            context.safeDataStoreEdit { it[MyTopFilterKey] = period.name }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val topSongs =
        context.dataStore.data
            .map { preferences ->
                preferences[MyTopFilterKey].toEnum(MyTopFilter.ALL_TIME) to
                    (preferences[HideVideoSongsKey] ?: false)
            }
            .distinctUntilChanged()
            .flatMapLatest { (period, hideVideoSongs) ->
                val now = LocalDateTime.now()
                database.mostPlayedSongs(
                    fromTimeStamp = period.toLocalDateTime(),
                    limit = top.toInt(),
                    offset = 0,
                    toTimeStamp = now
                ).map { songs ->
                    if (hideVideoSongs) songs.filter { !it.song.isVideo } else songs
                }
            }.stateIn(viewModelScope, SharingStarted.Lazily, null)
}
