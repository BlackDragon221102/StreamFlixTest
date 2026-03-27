package com.streamflixreborn.streamflix.fragments.genre

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.FragmentGenreMobileBinding
import com.streamflixreborn.streamflix.databinding.HeaderGenreMobileBinding
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.ui.SpacingItemDecoration
import com.streamflixreborn.streamflix.utils.CacheUtils
import com.streamflixreborn.streamflix.utils.HeroColorUtils
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.dp
import com.streamflixreborn.streamflix.utils.viewModelsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GenreMobileFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false

    private var _binding: FragmentGenreMobileBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<GenreMobileFragmentArgs>()
    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory { GenreViewModel(args.id, database) }

    private val appAdapter = AppAdapter()
    private var backgroundColorJob: Job? = null
    private var lastBackgroundPosterUrl: String? = null
    private var headerScrollOffsetPx: Int = 0
    private var genreBaseColor: Int = HeroColorUtils.DEFAULT_HERO_COLOR
    private var currentGenre: Genre? = null
    private var currentHasMore: Boolean = false
    private var sortMode: SortMode = SortMode.DEFAULT

    private enum class SortMode(val label: String) {
        DEFAULT("Ordina"),
        RECENT("Più recenti"),
        RATING("Più votati"),
        TITLE_ASC("A-Z"),
        TITLE_DESC("Z-A")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGenreMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeGenre()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    GenreViewModel.State.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        pbIsLoading.visibility = View.VISIBLE
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    GenreViewModel.State.LoadingMore -> appAdapter.isLoading = true
                    is GenreViewModel.State.SuccessLoading -> {
                        currentGenre = state.genre
                        currentHasMore = state.hasMore
                        displayGenre(state.genre, state.hasMore)
                        appAdapter.isLoading = false
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is GenreViewModel.State.FailedLoading -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            android.widget.Toast.makeText(requireContext(), getString(com.streamflixreborn.streamflix.R.string.clear_cache_done_409), android.widget.Toast.LENGTH_SHORT).show()
                            viewModel.getGenre(args.id)
                            return@collect
                        }
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_SHORT
                        ).show()
                        if (appAdapter.isLoading) {
                            appAdapter.isLoading = false
                        } else {
                            binding.isLoading.apply {
                                pbIsLoading.visibility = View.GONE
                                gIsLoadingRetry.visibility = View.VISIBLE
                                val doRetry = { viewModel.getGenre(args.id) }
                                btnIsLoadingRetry.setOnClickListener { doRetry() }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    android.widget.Toast.makeText(requireContext(), getString(com.streamflixreborn.streamflix.R.string.clear_cache_done), android.widget.Toast.LENGTH_SHORT).show()
                                    doRetry()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        backgroundColorJob?.cancel()
        _binding = null
    }


    private fun initializeGenre() {
        val baseHeaderHeight = binding.headerContainer.layoutParams.height
        val baseRecyclerTopPadding = binding.rvGenre.paddingTop

        ViewCompat.setOnApplyWindowInsetsListener(binding.headerContainer) { _, windowInsets ->
            val topInset = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            binding.headerContainer.setPadding(0, topInset, 0, 0)
            binding.headerContainer.layoutParams = binding.headerContainer.layoutParams.apply {
                height = baseHeaderHeight + topInset
            }
            binding.rvGenre.setPadding(
                binding.rvGenre.paddingLeft,
                baseRecyclerTopPadding + topInset,
                binding.rvGenre.paddingRight,
                binding.rvGenre.paddingBottom
            )
            windowInsets
        }

        binding.rvGenre.apply {
            layoutManager = GridLayoutManager(context, 3).also {
                it.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        val viewType = appAdapter.getItemViewType(position)
                        return when (AppAdapter.Type.entries[viewType]) {
                            AppAdapter.Type.HEADER -> it.spanCount
                            else -> 1
                        }
                    }
                }
            }
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            addItemDecoration(
                SpacingItemDecoration(10.dp(requireContext()))
            )
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    headerScrollOffsetPx = (headerScrollOffsetPx + dy).coerceAtLeast(0)
                    updateHeaderBackgroundByScroll()
                    applyGenreBackgroundByScroll()
                }
            })
        }

        binding.ivProviderLogo.apply {
            Glide.with(context)
                .load(UserPreferences.currentProvider?.logo?.takeIf { it.isNotEmpty() }
                    ?: R.drawable.ic_provider_default_logo)
                .error(R.drawable.ic_provider_default_logo)
                .fitCenter()
                .into(this)
            isClickable = true
            isFocusable = false
            setOnClickListener { }
            setOnTouchListener { _, _ -> true }
        }

        binding.tvHeaderTitle.text = sectionLabel()
        applyGenreBackgroundByScroll()
        binding.headerContainer.setBackgroundColor(Color.TRANSPARENT)
        updateHeaderBackgroundByScroll()
        binding.headerContainer.requestApplyInsets()
    }

    private fun updateHeaderBackgroundByScroll() {
        val scrollOffset = headerScrollOffsetPx
        val fadeStart = 30
        val fadeEnd = 260
        val headerAlpha = when {
            scrollOffset <= fadeStart -> 0
            scrollOffset >= fadeEnd -> 255
            else -> (((scrollOffset - fadeStart).toFloat() / (fadeEnd - fadeStart)) * 255f)
                .toInt()
                .coerceIn(0, 255)
        }
        binding.headerContainer.setBackgroundColor(Color.argb(headerAlpha, 0, 0, 0))
    }

    private fun ColorUtilsBlend(start: Int, end: Int, ratio: Float): Int {
        return androidx.core.graphics.ColorUtils.blendARGB(start, end, ratio.coerceIn(0f, 1f))
    }

    private fun applyGenreBackgroundByScroll() {
        val scrollProgress = headerFadeProgress()

        val topColor = ColorUtilsBlend(genreBaseColor, Color.BLACK, scrollProgress)
        val bottomBase = ColorUtilsBlend(genreBaseColor, Color.BLACK, 0.55f)
        val bottomColor = ColorUtilsBlend(bottomBase, Color.BLACK, scrollProgress)

        binding.ivGenreBackground.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(topColor, bottomColor)
        )
    }

    private fun headerFadeProgress(): Float {
        val scrollOffset = headerScrollOffsetPx.toFloat()
        val fadeStart = 30f
        val fadeEnd = 260f
        return when {
            scrollOffset <= fadeStart -> 0f
            scrollOffset >= fadeEnd -> 1f
            else -> ((scrollOffset - fadeStart) / (fadeEnd - fadeStart)).coerceIn(0f, 1f)
        }
    }

    private fun sectionLabel(): String = when {
        args.id.equals("Tutti i Film", ignoreCase = true) -> "Film"
        args.id.equals("Tutte le Serie TV", ignoreCase = true) -> "Serie TV"
        args.id.startsWith("Film: ", ignoreCase = true) -> "Film"
        args.id.startsWith("Serie TV: ", ignoreCase = true) -> "Serie TV"
        else -> "Genere"
    }

    private fun displayGenre(genre: Genre, hasMore: Boolean) {
        if (!binding.rvGenre.canScrollVertically(-1)) {
            headerScrollOffsetPx = 0
            updateHeaderBackgroundByScroll()
        }
        updateGenreBackgroundFromFirstPoster(genre.shows.firstOrNull())

        appAdapter.setHeader(
            binding = { parent ->
                HeaderGenreMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            },
            bind = { binding ->
                binding.tvGenreName.text = genre.name.takeIf { it.isNotEmpty() } ?: args.name
                binding.tvGenreSort.text = sortMode.label
                binding.tvGenreSort.setOnClickListener { showSortMenu(binding.tvGenreSort) }
            }
        )

        appAdapter.submitList(sortShows(genre.shows).onEach {
            when (it) {
                is Movie -> it.itemType = AppAdapter.Type.MOVIE_GENRE_MOBILE_ITEM
                is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_GENRE_MOBILE_ITEM
            }
        })

        if (hasMore) {
            appAdapter.setOnLoadMoreListener { viewModel.loadMoreGenreShows() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
    }

    private fun showSortMenu(anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, SortMode.DEFAULT.ordinal, 0, SortMode.DEFAULT.label)
            menu.add(0, SortMode.RECENT.ordinal, 1, SortMode.RECENT.label)
            menu.add(0, SortMode.RATING.ordinal, 2, SortMode.RATING.label)
            menu.add(0, SortMode.TITLE_ASC.ordinal, 3, SortMode.TITLE_ASC.label)
            menu.add(0, SortMode.TITLE_DESC.ordinal, 4, SortMode.TITLE_DESC.label)
            setOnMenuItemClickListener(::onSortMenuItemSelected)
            show()
        }
    }

    private fun onSortMenuItemSelected(item: MenuItem): Boolean {
        val selectedMode = SortMode.entries.getOrNull(item.itemId) ?: return false
        if (sortMode == selectedMode) return true
        sortMode = selectedMode
        currentGenre?.let { displayGenre(it, currentHasMore) }
        return true
    }

    private fun sortShows(shows: List<Show>): List<Show> = when (sortMode) {
        SortMode.DEFAULT -> shows
        SortMode.RECENT -> shows.sortedByDescending { showReleasedTime(it) }
        SortMode.RATING -> shows.sortedByDescending { showRating(it) }
        SortMode.TITLE_ASC -> shows.sortedBy { showTitle(it).lowercase() }
        SortMode.TITLE_DESC -> shows.sortedByDescending { showTitle(it).lowercase() }
    }

    private fun showTitle(show: Show): String = when (show) {
        is Movie -> show.title
        is TvShow -> show.title
    }

    private fun showRating(show: Show): Double = when (show) {
        is Movie -> show.rating ?: 0.0
        is TvShow -> show.rating ?: 0.0
    }

    private fun showReleasedTime(show: Show): Long = when (show) {
        is Movie -> show.released?.timeInMillis ?: 0L
        is TvShow -> show.released?.timeInMillis ?: 0L
    }

    private fun updateGenreBackgroundFromFirstPoster(show: Show?) {
        val posterUrl = when (show) {
            is Movie -> show.poster
            is TvShow -> show.poster
            else -> null
        }?.takeIf { it.isNotBlank() }

        if (posterUrl == lastBackgroundPosterUrl) return
        lastBackgroundPosterUrl = posterUrl
        backgroundColorJob?.cancel()

        if (posterUrl == null) {
            genreBaseColor = HeroColorUtils.DEFAULT_HERO_COLOR
            applyGenreBackgroundByScroll()
            return
        }

        backgroundColorJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = runCatching {
                Glide.with(requireContext())
                    .asBitmap()
                    .load(posterUrl)
                    .submit(96, 144)
                    .get()
            }.getOrNull()

            val extractedColor = bitmap?.let { HeroColorUtils.extractNormalizedHeroColor(it) }
                ?: HeroColorUtils.DEFAULT_HERO_COLOR

            withContext(Dispatchers.Main) {
                if (_binding == null || !isAdded || posterUrl != lastBackgroundPosterUrl) return@withContext
                genreBaseColor = extractedColor
                applyGenreBackgroundByScroll()
            }
        }
    }
}
