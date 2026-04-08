package com.streamflixreborn.streamflix.fragments.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.repository.CatalogRepository
import com.streamflixreborn.streamflix.repository.DefaultCatalogRepository
import com.streamflixreborn.streamflix.repository.HomeCatalogState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class HomeViewModel(database: AppDatabase) : ViewModel() {
    private val repository: CatalogRepository = DefaultCatalogRepository(database)

    val state: Flow<HomeCatalogState> = repository.homeState

    fun hasCachedHome(): Boolean = repository.hasCachedHome()

    fun isCachedHomeFresh(): Boolean = repository.isCachedHomeFresh()

    fun getHome(
        forceRefresh: Boolean = false,
        silentRefresh: Boolean = false,
    ) = viewModelScope.launch {
        runCatching {
            repository.refreshHome(forceRefresh = forceRefresh, silentRefresh = silentRefresh)
        }.onFailure { error ->
            Log.e("HomeViewModel", "getHome: ", error)
        }
    }
}
