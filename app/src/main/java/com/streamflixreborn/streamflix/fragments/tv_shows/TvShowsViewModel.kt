package com.streamflixreborn.streamflix.fragments.tv_shows

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.repository.CatalogRepository
import com.streamflixreborn.streamflix.repository.DefaultCatalogRepository
import com.streamflixreborn.streamflix.repository.TvShowsCatalogState
import com.streamflixreborn.streamflix.utils.ProviderChangeNotifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class TvShowsViewModel(database: AppDatabase) : ViewModel() {
    private val repository: CatalogRepository = DefaultCatalogRepository(database)

    val state: Flow<TvShowsCatalogState> = repository.tvShowsState

    init {
        viewModelScope.launch {
            ProviderChangeNotifier.providerChangeFlow.collect {
                loadNetflixStyleCategories(forceRefresh = true)
            }
        }
        loadNetflixStyleCategories(silentRefresh = true)
    }

    fun loadNetflixStyleCategories(
        forceRefresh: Boolean = false,
        silentRefresh: Boolean = false,
    ) = viewModelScope.launch {
        runCatching {
            repository.refreshTvShows(forceRefresh = forceRefresh, silentRefresh = silentRefresh)
        }.onFailure { error ->
            Log.e("TvShowsViewModel", "loadNetflixStyleCategories: ", error)
        }
    }
}
