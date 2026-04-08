package com.streamflixreborn.streamflix.fragments.genre

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import androidx.leanback.widget.OnChildViewHolderSelectedListener
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.FragmentGenreTvBinding
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.repository.GenreCatalogState
import com.streamflixreborn.streamflix.utils.CacheUtils
import kotlinx.coroutines.launch

class GenreTvFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false
    private var shouldPostponeGenreEnterTransition: Boolean = false

    private var _binding: FragmentGenreTvBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<GenreTvFragmentArgs>()
    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModels<GenreViewModel> {
        object : AbstractSavedStateViewModelFactory(this, null) {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                key: String,
                modelClass: Class<T>,
                savedStateHandle: androidx.lifecycle.SavedStateHandle,
            ): T {
                return GenreViewModel(args.id, database, savedStateHandle) as T
            }
        }
    }

    private val appAdapter = AppAdapter()
    private var isBackToTopVisible = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGenreTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        shouldPostponeGenreEnterTransition = viewModel.hasPendingRestore(args.id)

        initializeGenre()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    GenreCatalogState.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        pbIsLoading.visibility = View.VISIBLE
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    is GenreCatalogState.Success -> {
                        displayGenre(state.genre, state.hasMore)
                        appAdapter.isLoading = state.isLoadingMore
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is GenreCatalogState.Error -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            android.widget.Toast.makeText(requireContext(), getString(com.streamflixreborn.streamflix.R.string.clear_cache_done_409), android.widget.Toast.LENGTH_SHORT).show()
                            if (appAdapter.isLoading) appAdapter.isLoading = false
                            viewModel.getGenre(args.id)
                            return@collect
                        }
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_SHORT
                        ).show()
                        state.cachedGenre?.let { cachedGenre ->
                            displayGenre(cachedGenre, state.hasMore)
                            appAdapter.isLoading = false
                            binding.isLoading.root.visibility = View.GONE
                            return@collect
                        }
                        if (appAdapter.isLoading) {
                            appAdapter.isLoading = false
                        } else {
                            binding.isLoading.apply {
                                pbIsLoading.visibility = View.GONE
                                gIsLoadingRetry.visibility = View.VISIBLE
                                btnIsLoadingRetry.setOnClickListener { viewModel.getGenre(args.id) }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    android.widget.Toast.makeText(requireContext(), getString(com.streamflixreborn.streamflix.R.string.clear_cache_done), android.widget.Toast.LENGTH_SHORT).show()
                                    viewModel.getGenre(args.id)
                                }
                                btnIsLoadingRetry.requestFocus()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onPause() {
        viewModel.saveTvScroll(binding.vgvGenre.selectedPosition.coerceAtLeast(0))
        viewModel.prepareForNextRestore(args.id)
        super.onPause()
    }


    private fun initializeGenre() {
        binding.vgvGenre.visibility = if (shouldPostponeGenreEnterTransition) View.INVISIBLE else View.VISIBLE
        binding.vgvGenre.alpha = 1f
        binding.vgvGenre.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            setItemSpacing(requireContext().resources.getDimension(R.dimen.genre_spacing).toInt())
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    updateBackToTopVisibility(binding.vgvGenre.selectedPosition.coerceAtLeast(0))
                }
            })
            addOnChildViewHolderSelectedListener(object : OnChildViewHolderSelectedListener() {
                override fun onChildViewHolderSelected(
                    parent: RecyclerView,
                    child: RecyclerView.ViewHolder?,
                    position: Int,
                    subposition: Int,
                ) {
                    updateBackToTopVisibility(position.coerceAtLeast(0))
                }
            })
        }

        binding.btnBackToTop.setOnClickListener {
            viewModel.clearSavedScroll()
            binding.vgvGenre.setSelectedPosition(0)
            updateBackToTopVisibility(0)
            binding.vgvGenre.requestFocus()
        }
    }

    private fun displayGenre(genre: Genre, hasMore: Boolean) {
        binding.tvGenreName.text = getString(
            R.string.genre_header_name,
            genre.name.takeIf { it.isNotEmpty() } ?: args.name
        )

        appAdapter.submitList(genre.shows.onEach {
            when (it) {
                is Movie -> it.itemType = AppAdapter.Type.MOVIE_GRID_TV_ITEM
                is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_GRID_TV_ITEM
            }
        }) {
            restoreScrollIfNeeded()
        }

        if (hasMore) {
            appAdapter.setOnLoadMoreListener { viewModel.loadMoreGenreShows() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
    }

    private fun restoreScrollIfNeeded() {
        if (!viewModel.shouldAttemptRestore(args.id)) {
            Log.d("GenreScroll", "TV restore failed: flag already true for genreId=${args.id}")
            revealGenreGrid(immediate = !shouldPostponeGenreEnterTransition)
            return
        }
        if (appAdapter.items.isEmpty()) {
            Log.d("GenreScroll", "TV restore skipped: list is empty")
            revealGenreGrid(immediate = !shouldPostponeGenreEnterTransition)
            return
        }
        val position = viewModel.savedTvScrollPosition
        binding.vgvGenre.doOnPreDraw {
            Log.d("GenreScroll", "Attempting TV restore position=$position for genreId=${args.id}")
            binding.vgvGenre.setSelectedPosition(position.coerceAtLeast(0))
            viewModel.markRestoreCompleted(args.id)
            Log.d("GenreScroll", "TV restore successful position=$position for genreId=${args.id}")
            binding.vgvGenre.doOnPreDraw {
                updateBackToTopVisibility(position.coerceAtLeast(0))
                revealGenreGrid()
            }
        }
    }

    private fun revealGenreGrid(immediate: Boolean = false) {
        binding.vgvGenre.animate().cancel()
        binding.vgvGenre.visibility = View.VISIBLE
        if (immediate) {
            binding.vgvGenre.alpha = 1f
            return
        }
        if (binding.vgvGenre.alpha >= 1f) return
        binding.vgvGenre.alpha = 0f
        binding.vgvGenre.animate()
            .alpha(1f)
            .setDuration(150L)
            .start()
    }

    private fun updateBackToTopVisibility(position: Int) {
        val shouldShow = position > 5
        if (shouldShow == isBackToTopVisible) return
        isBackToTopVisible = shouldShow
        binding.btnBackToTop.animate().cancel()
        if (shouldShow) {
            binding.btnBackToTop.visibility = View.VISIBLE
            binding.btnBackToTop.animate().alpha(1f).setDuration(180L).start()
        } else {
            binding.btnBackToTop.animate()
                .alpha(0f)
                .setDuration(150L)
                .withEndAction {
                    if (!isBackToTopVisible) {
                        binding.btnBackToTop.visibility = View.GONE
                    }
                }
                .start()
        }
    }
}
