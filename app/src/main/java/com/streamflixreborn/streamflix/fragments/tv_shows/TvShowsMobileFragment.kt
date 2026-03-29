package com.streamflixreborn.streamflix.fragments.tv_shows

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.FragmentTvShowsMobileBinding
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.ui.SpacingItemDecoration
import com.streamflixreborn.streamflix.utils.CacheUtils
import com.streamflixreborn.streamflix.utils.HeroColorUtils
import com.streamflixreborn.streamflix.utils.TopLevelTabFragment
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.viewModelsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TvShowsMobileFragment : Fragment(), TopLevelTabFragment {

    private var hasAutoCleared409: Boolean = false
    private var _binding: FragmentTvShowsMobileBinding? = null
    private val binding get() = _binding!!
    private var heroColorJob: Job? = null
    private var heroColorAnimator: ValueAnimator? = null
    private var lastHeroImageUrl: String? = null
    private var heroBaseColor: Int = HeroColorUtils.DEFAULT_HERO_COLOR
    private val heroColorCache = mutableMapOf<String, Int>()

    private fun heroCacheKey(imageUrl: String): String = "${HeroColorUtils.CACHE_VERSION}|$imageUrl"

    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory { TvShowsViewModel(database) }
    private val appAdapter = AppAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTvShowsMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeTvShows()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    TvShowsViewModel.State.Loading -> {
                        if (appAdapter.itemCount == 0) {
                            binding.isLoading.apply {
                                root.visibility = View.VISIBLE
                                pbIsLoading.visibility = View.VISIBLE
                                gIsLoadingRetry.visibility = View.GONE
                            }
                        } else {
                            binding.isLoading.root.visibility = View.GONE
                        }
                    }
                    is TvShowsViewModel.State.SuccessLoading -> {
                        displayTvShows(state.categories)
                        appAdapter.isLoading = false
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is TvShowsViewModel.State.FailedLoading -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            Toast.makeText(requireContext(), getString(com.streamflixreborn.streamflix.R.string.clear_cache_done_409), Toast.LENGTH_SHORT).show()
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
                                val doRetry = { viewModel.loadNetflixStyleCategories() }
                                btnIsLoadingRetry.setOnClickListener { doRetry() }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
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
        heroColorJob?.cancel()
        heroColorAnimator?.cancel()
        appAdapter.onHeroImageChangeListener = null
        appAdapter.onSaveInstanceState(binding.rvTvShows)
        _binding = null
    }

    private fun initializeTvShows() {
        val baseHeaderHeight = binding.headerContainer.layoutParams.height
        val baseRecyclerTopPadding = binding.rvTvShows.paddingTop

        ViewCompat.setOnApplyWindowInsetsListener(binding.headerContainer) { _, windowInsets ->
            val topInset = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            binding.headerContainer.setPadding(0, topInset, 0, 0)
            binding.headerContainer.layoutParams = binding.headerContainer.layoutParams.apply {
                height = baseHeaderHeight + topInset
            }
            binding.rvTvShows.setPadding(
                binding.rvTvShows.paddingLeft,
                baseRecyclerTopPadding + topInset,
                binding.rvTvShows.paddingRight,
                binding.rvTvShows.paddingBottom
            )
            windowInsets
        }

        binding.rvTvShows.apply {
            adapter = appAdapter.apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }
            if (itemDecorationCount == 0) {
                addItemDecoration(
                    SpacingItemDecoration(resources.getDimension(R.dimen.home_mobile_section_spacing).toInt())
                )
            }
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    updateHeaderBackgroundByScroll()
                    applyTvShowsBackgroundByScroll()
                }
            })
        }

        appAdapter.onHeroImageChangeListener = { imageUrl ->
            updateHeroBaseColor(imageUrl)
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

        binding.headerContainer.apply {
            isClickable = true
            isFocusable = false
            setOnClickListener { }
            setOnTouchListener { _, _ -> true }
        }

        binding.ivTvShowsBackground.visibility = View.VISIBLE
        binding.ivTvShowsBackground.setBackgroundColor(HeroColorUtils.DEFAULT_HERO_COLOR)
        binding.headerContainer.setBackgroundColor(Color.TRANSPARENT)
        applyTvShowsBackgroundByScroll()
        updateHeaderBackgroundByScroll()
        binding.headerContainer.requestApplyInsets()
    }

    private fun displayTvShows(categories: List<Category>) {
        val defaultItemSpacing = resources.getDimension(R.dimen.home_mobile_item_spacing).toInt()
        appAdapter.submitList(categories.mapIndexedNotNull { index, category ->
            if (category.name == "Generi") {
                val genres = category.list.mapNotNull { item -> (item as? Genre)?.copy() }
                if (genres.isEmpty()) {
                    return@mapIndexedNotNull null
                }

                genres.forEach { genre ->
                    genre.itemType = AppAdapter.Type.GENRE_MOBILE_ITEM
                }

                return@mapIndexedNotNull Category(
                    name = category.name,
                    list = genres
                ).also { categoryCopy ->
                    categoryCopy.itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM
                    categoryCopy.itemSpacing = if (category.itemSpacing > 0) category.itemSpacing else defaultItemSpacing
                }
            }

            val tvShows = category.list.mapNotNull { item -> (item as? TvShow)?.copy() }
            if (tvShows.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val categoryCopy = Category(
                name = category.name,
                list = tvShows
            )

            if (index == 0 && category.name == Category.FEATURED) {
                categoryCopy.itemType = AppAdapter.Type.CATEGORY_MOBILE_SWIPER
                tvShows.forEach { show -> show.itemType = AppAdapter.Type.TV_SHOW_SWIPER_MOBILE_ITEM }
            } else {
                categoryCopy.itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM
                tvShows.forEach { show -> show.itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM }
            }
            categoryCopy.itemSpacing = defaultItemSpacing
            categoryCopy
        })
        applyTvShowsBackgroundByScroll()
        appAdapter.setOnLoadMoreListener(null)
    }

    private fun updateHeroBaseColor(imageUrl: String?) {
        if (_binding == null || !isAdded) return
        val requestedImageUrl = imageUrl?.takeIf { it.isNotBlank() }
        if (requestedImageUrl == lastHeroImageUrl) return
        lastHeroImageUrl = requestedImageUrl
        heroColorJob?.cancel()

        if (requestedImageUrl == null) {
            animateHeroBaseColorTo(HeroColorUtils.DEFAULT_HERO_COLOR)
            return
        }

        val cacheKey = heroCacheKey(requestedImageUrl)
        heroColorCache[cacheKey]?.let { cachedColor ->
            animateHeroBaseColorTo(cachedColor)
            return
        }

        heroColorJob = lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = runCatching {
                Glide.with(requireContext())
                    .asBitmap()
                    .load(requestedImageUrl)
                    .submit(160, 240)
                    .get()
            }.getOrNull()

            val extractedColor = bitmap?.let { HeroColorUtils.extractNormalizedHeroColor(it) }
                ?: HeroColorUtils.DEFAULT_HERO_COLOR

            withContext(Dispatchers.Main) {
                if (_binding == null || !isAdded || requestedImageUrl != lastHeroImageUrl) return@withContext
                heroColorCache[cacheKey] = extractedColor
                animateHeroBaseColorTo(extractedColor)
            }
        }
    }

    private fun updateHeaderBackgroundByScroll() {
        val scrollOffset = binding.rvTvShows.computeVerticalScrollOffset()
        val fadeStart = 80
        val fadeEnd = 420
        val headerAlpha = when {
            scrollOffset <= fadeStart -> 0
            scrollOffset >= fadeEnd -> 255
            else -> (((scrollOffset - fadeStart).toFloat() / (fadeEnd - fadeStart)) * 255f).toInt().coerceIn(0, 255)
        }
        binding.headerContainer.setBackgroundColor(Color.argb(headerAlpha, 0, 0, 0))
    }

    private fun applyTvShowsBackgroundByScroll() {
        val layoutManager = binding.rvTvShows.layoutManager as? LinearLayoutManager ?: return
        val firstVisiblePosition = layoutManager.findFirstVisibleItemPosition()
        val scrollProgress = when {
            firstVisiblePosition > 0 -> 1f
            else -> {
                val heroView = layoutManager.findViewByPosition(0) ?: return
                val heroHeight = heroView.height.toFloat().coerceAtLeast(1f)
                val heroVisibleFraction = (heroView.bottom / heroHeight).coerceIn(0f, 1f)
                (1f - heroVisibleFraction).coerceIn(0f, 1f)
            }
        }

        val topColor = androidx.core.graphics.ColorUtils.blendARGB(heroBaseColor, Color.BLACK, scrollProgress)
        val bottomBase = androidx.core.graphics.ColorUtils.blendARGB(heroBaseColor, Color.BLACK, 0.55f)
        val bottomColor = androidx.core.graphics.ColorUtils.blendARGB(bottomBase, Color.BLACK, scrollProgress)
        val gradient = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(topColor, bottomColor)
        )
        binding.ivTvShowsBackground.background = gradient
    }

    private fun animateHeroBaseColorTo(targetColor: Int, durationMs: Long = 220L) {
        if (_binding == null || !isAdded) return
        heroColorAnimator?.cancel()

        if (heroBaseColor == targetColor) {
            applyTvShowsBackgroundByScroll()
            return
        }

        heroColorAnimator = ValueAnimator.ofArgb(heroBaseColor, targetColor).apply {
            duration = durationMs
            addUpdateListener { animator ->
                heroBaseColor = animator.animatedValue as Int
                applyTvShowsBackgroundByScroll()
            }
            start()
        }
    }

    override fun onTopLevelTabSelected(animate: Boolean) {
        if (!animate) {
            scrollToTop()
            return
        }

        binding.root.animate().cancel()
        binding.root.animate()
            .alpha(0f)
            .setDuration(200L)
            .withEndAction {
                scrollToTop()
                binding.root.animate()
                    .alpha(1f)
                    .setDuration(200L)
                    .start()
            }
            .start()
    }

    private fun scrollToTop() {
        if (_binding == null) return
        (binding.rvTvShows.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
        binding.rvTvShows.scrollToPosition(0)
        binding.rvTvShows.post {
            if (_binding == null) return@post
            (binding.rvTvShows.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
            binding.rvTvShows.scrollToPosition(0)
        }
    }
}
