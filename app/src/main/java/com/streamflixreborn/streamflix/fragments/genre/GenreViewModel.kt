package com.streamflixreborn.streamflix.fragments.genre

import android.os.Parcelable
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.repository.CatalogRepository
import com.streamflixreborn.streamflix.repository.DefaultCatalogRepository
import com.streamflixreborn.streamflix.repository.GenreCatalogState
import com.streamflixreborn.streamflix.repository.GenreSortMode
import com.streamflixreborn.streamflix.utils.ProviderChangeNotifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class GenreViewModel(
    private val id: String,
    database: AppDatabase,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val repository: CatalogRepository = DefaultCatalogRepository(database)

    val state: Flow<GenreCatalogState> = repository.genreState
    val sortMode: StateFlow<GenreSortMode> = repository.genreSortMode

    companion object {
        private const val KEY_MOBILE_SCROLL_POSITION = "genre_mobile_scroll_position"
        private const val KEY_MOBILE_SCROLL_OFFSET = "genre_mobile_scroll_offset"
        private const val KEY_TV_SCROLL_POSITION = "genre_tv_scroll_position"
        private const val KEY_MOBILE_LAYOUT_STATE = "genre_mobile_layout_state"
        private const val KEY_RESTORED_GENRE_ID = "genre_restored_genre_id"
    }

    var savedMobileScrollPosition: Int
        get() = savedStateHandle[KEY_MOBILE_SCROLL_POSITION] ?: 0
        private set(value) { savedStateHandle[KEY_MOBILE_SCROLL_POSITION] = value }

    var savedMobileScrollOffset: Int
        get() = savedStateHandle[KEY_MOBILE_SCROLL_OFFSET] ?: 0
        private set(value) { savedStateHandle[KEY_MOBILE_SCROLL_OFFSET] = value }

    var savedTvScrollPosition: Int
        get() = savedStateHandle[KEY_TV_SCROLL_POSITION] ?: 0
        private set(value) { savedStateHandle[KEY_TV_SCROLL_POSITION] = value }

    var savedMobileLayoutState: Parcelable?
        get() = savedStateHandle[KEY_MOBILE_LAYOUT_STATE]
        private set(value) { savedStateHandle[KEY_MOBILE_LAYOUT_STATE] = value }

    private var restoredGenreId: String?
        get() = savedStateHandle[KEY_RESTORED_GENRE_ID]
        set(value) { savedStateHandle[KEY_RESTORED_GENRE_ID] = value }

    init {
        viewModelScope.launch {
            ProviderChangeNotifier.providerChangeFlow.collect {
                getGenre(id, forceRefresh = true)
            }
        }
        if (!repository.showCachedGenre(id)) {
            getGenre(id)
        }
    }

    fun setSortMode(mode: GenreSortMode) {
        if (sortMode.value == mode) return
        repository.setGenreSortMode(mode)
        getGenre(id, forceRefresh = true)
    }

    fun getGenre(
        id: String,
        forceRefresh: Boolean = false,
    ) = viewModelScope.launch {
        runCatching {
            repository.refreshGenre(id, forceRefresh = forceRefresh)
        }.onFailure { error ->
            Log.e("GenreViewModel", "getGenre: ", error)
        }
    }

    fun loadMoreGenreShows() = viewModelScope.launch {
        runCatching {
            repository.loadMoreGenre(id)
        }.onFailure { error ->
            Log.e("GenreViewModel", "loadMoreGenreShows: ", error)
        }
    }

    fun saveMobileScroll(position: Int, offset: Int) {
        savedMobileScrollPosition = position.coerceAtLeast(0)
        savedMobileScrollOffset = offset
        Log.d("GenreScroll", "Saved mobile scroll position=$savedMobileScrollPosition offset=$savedMobileScrollOffset")
    }

    fun saveMobileLayoutState(state: Parcelable?) {
        savedMobileLayoutState = state
        Log.d("GenreScroll", "Saved mobile layout state=${state != null}")
    }

    fun saveTvScroll(position: Int) {
        savedTvScrollPosition = position.coerceAtLeast(0)
        Log.d("GenreScroll", "Saved TV scroll position=$savedTvScrollPosition")
    }

    fun shouldAttemptRestore(genreId: String): Boolean = restoredGenreId != genreId

    fun hasPendingRestore(genreId: String): Boolean {
        if (!shouldAttemptRestore(genreId)) return false
        return savedMobileLayoutState != null ||
            savedMobileScrollPosition > 0 ||
            savedMobileScrollOffset != 0 ||
            savedTvScrollPosition > 0
    }

    fun markRestoreCompleted(genreId: String) {
        restoredGenreId = genreId
        Log.d("GenreScroll", "Marked restore completed for genreId=$genreId")
    }

    fun resetRestoreStateForGenre(genreId: String) {
        if (restoredGenreId == genreId) {
            restoredGenreId = null
            Log.d("GenreScroll", "Reset restore state for genreId=$genreId")
        }
    }

    fun prepareForNextRestore(genreId: String) {
        restoredGenreId = null
        Log.d("GenreScroll", "Restore armed for next visit to genreId=$genreId")
    }

    fun clearSavedScroll() {
        savedMobileScrollPosition = 0
        savedMobileScrollOffset = 0
        savedTvScrollPosition = 0
        savedMobileLayoutState = null
        restoredGenreId = null
        Log.d("GenreScroll", "Cleared saved scroll and restore state")
    }
}
