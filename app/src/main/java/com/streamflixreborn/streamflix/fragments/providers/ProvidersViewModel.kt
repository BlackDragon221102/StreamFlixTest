package com.streamflixreborn.streamflix.fragments.providers

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.models.Provider as ModelProvider
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.StreamingCommunityProvider
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ProvidersViewModel : ViewModel() {

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: Flow<State> = _state

    sealed class State {
        data object Loading : State()
        data class SuccessLoading(val providers: List<ModelProvider>) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    init {
        getProviders(UserPreferences.currentLanguage)
    }

    fun getProviders(@Suppress("UNUSED_PARAMETER") language: String? = null) = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.Loading)

        try {
            val streamingCommunity = Provider.providers.keys
                .filterIsInstance<StreamingCommunityProvider>()
                .firstOrNull { it.language == "it" }
                ?: StreamingCommunityProvider("it")

            val modelProviders = listOf(streamingCommunity).map {
                ModelProvider(
                    name = it.name,
                    logo = it.logo,
                    language = it.language,
                    provider = it,
                )
            }.sortedBy { it.name }

            _state.emit(State.SuccessLoading(modelProviders))
        } catch (e: Exception) {
            Log.e("ProvidersViewModel", "getProviders: ", e)
            _state.emit(State.FailedLoading(e))
        }
    }
}
