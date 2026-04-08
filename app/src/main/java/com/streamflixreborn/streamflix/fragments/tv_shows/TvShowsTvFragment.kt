package com.streamflixreborn.streamflix.fragments.tv_shows

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.FragmentTvShowsTvBinding
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.repository.TvShowsCatalogState
import com.streamflixreborn.streamflix.utils.CacheUtils
import com.streamflixreborn.streamflix.utils.viewModelsFactory
import kotlinx.coroutines.launch

class TvShowsTvFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false
    private var _binding: FragmentTvShowsTvBinding? = null
    private val binding get() = _binding!!

    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory { TvShowsViewModel(database) }
    private val appAdapter = AppAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTvShowsTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeTvShows()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    TvShowsCatalogState.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        pbIsLoading.visibility = View.VISIBLE
                        gIsLoadingRetry.visibility = View.GONE
                        binding.vgvTvShows.visibility = View.GONE
                    }
                    is TvShowsCatalogState.Success -> {
                        displayTvShows(state.categories)
                        appAdapter.isLoading = false
                        binding.vgvTvShows.visibility = View.VISIBLE
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is TvShowsCatalogState.Error -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            if (appAdapter.isLoading) appAdapter.isLoading = false
                            viewModel.loadNetflixStyleCategories()
                            return@collect
                        }
                        Toast.makeText(requireContext(), state.error.message ?: "", Toast.LENGTH_SHORT).show()
                        if (appAdapter.isLoading) {
                            appAdapter.isLoading = false
                        } else {
                            binding.isLoading.apply {
                                pbIsLoading.visibility = View.GONE
                                gIsLoadingRetry.visibility = View.VISIBLE
                                btnIsLoadingRetry.setOnClickListener { viewModel.loadNetflixStyleCategories() }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    viewModel.loadNetflixStyleCategories()
                                }
                                binding.vgvTvShows.visibility = View.GONE
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

    private fun initializeTvShows() {
        binding.vgvTvShows.apply {
            adapter = appAdapter.apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }
            setItemSpacing(requireContext().resources.getDimension(R.dimen.movies_spacing).toInt())
        }
        binding.root.requestFocus()
    }

    private fun displayTvShows(categories: List<Category>) {
        appAdapter.submitList(categories.mapIndexed { index, category ->
            if (index == 0 && category.name == Category.FEATURED) {
                category.itemType = AppAdapter.Type.CATEGORY_TV_SWIPER
                category.list.forEach { show -> show.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM }
            } else {
                category.itemType = AppAdapter.Type.CATEGORY_TV_ITEM
                category.list.forEach { show -> show.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM }
            }
            category
        })
        appAdapter.setOnLoadMoreListener(null)
    }
}

