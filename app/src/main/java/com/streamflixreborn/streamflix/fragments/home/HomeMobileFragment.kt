package com.streamflixreborn.streamflix.fragments.home

import android.animation.ValueAnimator
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.FragmentHomeMobileBinding
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.ui.SpacingItemDecoration
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.CacheUtils
import com.streamflixreborn.streamflix.utils.LoggingUtils
import com.streamflixreborn.streamflix.utils.ProviderChangeNotifier
import com.streamflixreborn.streamflix.utils.TopLevelTabFragment
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.palette.graphics.Palette
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

class HomeMobileFragment : Fragment(), TopLevelTabFragment {

    private var hasAutoCleared409: Boolean = false
    private var hasRenderedHomeContent = false
    private var lastRenderedHomeSignature: String? = null
    private var shouldAnimateNextHomeRender = false

    private var _binding: FragmentHomeMobileBinding? = null
    private val binding get() = _binding!!
    private var heroColorJob: Job? = null
    private var heroColorAnimator: ValueAnimator? = null
    private var lastHeroImageUrl: String? = null
    private var heroBaseColor: Int = DEFAULT_HERO_COLOR
    private val heroColorCache = mutableMapOf<String, Int>()

    private val viewModel: HomeViewModel by lazy {
        val providerKey = UserPreferences.currentProvider?.name ?: "default"
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return HomeViewModel(AppDatabase.getInstance(requireContext())) as T
            }
        }
        ViewModelProvider(this, factory).get(providerKey, HomeViewModel::class.java)
    }

    private val appAdapter = AppAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeHome()
        shouldAnimateNextHomeRender = !viewModel.hasCachedHome()

        // Lightweight refresh when provider changes
        viewLifecycleOwner.lifecycleScope.launch {
            com.streamflixreborn.streamflix.utils.ProviderChangeNotifier.providerChangeFlow
                .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
                .collect { viewModel.getHome() }
        }

        // Initial load: avoid an immediate redundant refresh when a fresh cached Home is ready.
        if (!viewModel.hasCachedHome() || !viewModel.isCachedHomeFresh()) {
            viewModel.getHome()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    HomeViewModel.State.Loading -> {
                        val hasVisibleContent = appAdapter.itemCount > 0 || hasRenderedHomeContent
                        if (!hasVisibleContent) {
                            binding.isLoading.apply {
                                root.visibility = View.VISIBLE
                                pbIsLoading.visibility = View.VISIBLE
                                gIsLoadingRetry.visibility = View.GONE
                            }
                        } else {
                            binding.isLoading.root.visibility = View.GONE
                        }
                    }
                    is HomeViewModel.State.SuccessLoading -> {
                        displayHome(state.categories)
                        if (!hasRenderedHomeContent && shouldAnimateNextHomeRender) {
                            binding.rvHome.scheduleLayoutAnimation()
                        }
                        hasRenderedHomeContent = true
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is HomeViewModel.State.FailedLoading -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            android.widget.Toast.makeText(requireContext(), getString(com.streamflixreborn.streamflix.R.string.clear_cache_done_409), android.widget.Toast.LENGTH_SHORT).show()
                            viewModel.getHome()
                            return@collect
                        }
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_SHORT
                        ).show()
                        binding.isLoading.apply {
                            pbIsLoading.visibility = View.GONE
                            gIsLoadingRetry.visibility = View.VISIBLE
                            val doRetry = { viewModel.getHome() }
                            btnIsLoadingRetry.setOnClickListener { doRetry() }
                            btnIsLoadingClearCache.setOnClickListener {
                                CacheUtils.clearAppCache(requireContext())
                                android.widget.Toast.makeText(requireContext(), getString(com.streamflixreborn.streamflix.R.string.clear_cache_done), android.widget.Toast.LENGTH_SHORT).show()
                                doRetry()
                            }
                            btnIsLoadingErrorDetails.setOnClickListener {
                                LoggingUtils.showErrorDialog(requireContext(), state.error)
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
        appAdapter.onSaveInstanceState(binding.rvHome)
        _binding = null
    }


    private fun initializeHome() {
        val baseHeaderHeight = binding.headerContainer.layoutParams.height
        val baseRecyclerTopPadding = binding.rvHome.paddingTop

        binding.isLoading.root.visibility = if (viewModel.hasCachedHome()) View.GONE else View.VISIBLE

        ViewCompat.setOnApplyWindowInsetsListener(binding.headerContainer) { _, windowInsets ->
            val topInset = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            binding.headerContainer.setPadding(0, topInset, 0, 0)
            binding.headerContainer.layoutParams = binding.headerContainer.layoutParams.apply {
                height = baseHeaderHeight + topInset
            }
            binding.rvHome.setPadding(
                binding.rvHome.paddingLeft,
                baseRecyclerTopPadding + topInset,
                binding.rvHome.paddingRight,
                binding.rvHome.paddingBottom
            )
            windowInsets
        }

        binding.rvHome.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            addItemDecoration(
                SpacingItemDecoration(resources.getDimension(R.dimen.home_mobile_section_spacing).toInt())
            )
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    updateHeaderBackgroundByScroll()
                    applyHomeBackgroundByScroll()
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

        binding.ivHomeBackground.visibility = View.VISIBLE
        binding.ivHomeBackground.setBackgroundColor(DEFAULT_HERO_COLOR)
        binding.headerContainer.setBackgroundColor(Color.TRANSPARENT)
        applyHomeBackgroundByScroll()
        updateHeaderBackgroundByScroll()
        binding.headerContainer.requestApplyInsets()
    }

    private fun updateHeaderBackgroundByScroll() {
        val scrollOffset = binding.rvHome.computeVerticalScrollOffset()
        val fadeStart = 80
        val fadeEnd = 420
        val headerAlpha = when {
            scrollOffset <= fadeStart -> 0
            scrollOffset >= fadeEnd -> 255
            else -> (((scrollOffset - fadeStart).toFloat() / (fadeEnd - fadeStart)) * 255f).toInt().coerceIn(0, 255)
        }
        binding.headerContainer.setBackgroundColor(Color.argb(headerAlpha, 0, 0, 0))
    }

    private fun updateHeroBaseColor(imageUrl: String?) {
        if (_binding == null || !isAdded) return
        val requestedImageUrl = imageUrl?.takeIf { it.isNotBlank() }
        if (requestedImageUrl == lastHeroImageUrl) return
        lastHeroImageUrl = requestedImageUrl
        heroColorJob?.cancel()

        if (requestedImageUrl == null) {
            animateHeroBaseColorTo(DEFAULT_HERO_COLOR)
            return
        }

        heroColorCache[requestedImageUrl]?.let { cachedColor ->
            animateHeroBaseColorTo(cachedColor)
            return
        }

        animateHeroBaseColorTo(DEFAULT_HERO_COLOR, 120L)
        heroColorJob = lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = runCatching {
                Glide.with(requireContext())
                    .asBitmap()
                    .load(requestedImageUrl)
                    .submit(96, 96)
                    .get()
            }.getOrNull()

            val extractedColor = bitmap?.let { extractHeroColor(it) } ?: DEFAULT_HERO_COLOR

            withContext(Dispatchers.Main) {
                if (_binding == null || !isAdded || requestedImageUrl != lastHeroImageUrl) return@withContext
                heroColorCache[requestedImageUrl] = extractedColor
                animateHeroBaseColorTo(extractedColor)
            }
        }
    }

    private fun applyHomeBackgroundByScroll() {
        val layoutManager = binding.rvHome.layoutManager as? LinearLayoutManager ?: return
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
        binding.ivHomeBackground.background = gradient
    }

    private fun extractHeroColor(bitmap: Bitmap): Int {
        val sampleBitmap = bitmap.extractBottomHalf()
        val palette = Palette.from(sampleBitmap)
            .clearFilters()
            .generate()

        val dominantColor = palette.dominantSwatch?.rgb ?: calculateAverageColor(sampleBitmap)
        if (sampleBitmap !== bitmap) {
            sampleBitmap.recycle()
        }

        return normalizeHeroColor(dominantColor)
    }

    private fun animateHeroBaseColorTo(targetColor: Int, durationMs: Long = 220L) {
        if (_binding == null || !isAdded) return
        heroColorAnimator?.cancel()

        if (heroBaseColor == targetColor) {
            applyHomeBackgroundByScroll()
            return
        }

        heroColorAnimator = ValueAnimator.ofArgb(heroBaseColor, targetColor).apply {
            duration = durationMs
            addUpdateListener { animator ->
                heroBaseColor = animator.animatedValue as Int
                applyHomeBackgroundByScroll()
            }
            start()
        }
    }

    private fun Bitmap.extractBottomHalf(): Bitmap {
        if (width <= 0 || height <= 1) return this
        val cropTop = height / 2
        val cropHeight = (height - cropTop).coerceAtLeast(1)
        return Bitmap.createBitmap(this, 0, cropTop, width, cropHeight)
    }

    private fun calculateAverageColor(bitmap: Bitmap): Int {
        if (bitmap.width <= 0 || bitmap.height <= 0) return DEFAULT_HERO_COLOR

        var red = 0L
        var green = 0L
        var blue = 0L
        val totalPixels = bitmap.width * bitmap.height

        for (x in 0 until bitmap.width) {
            for (y in 0 until bitmap.height) {
                val pixel = bitmap.getPixel(x, y)
                red += Color.red(pixel)
                green += Color.green(pixel)
                blue += Color.blue(pixel)
            }
        }

        return Color.rgb(
            (red / totalPixels).toInt().coerceIn(0, 255),
            (green / totalPixels).toInt().coerceIn(0, 255),
            (blue / totalPixels).toInt().coerceIn(0, 255)
        )
    }

    private fun normalizeHeroColor(color: Int): Int {
        val hsl = FloatArray(3)
        androidx.core.graphics.ColorUtils.colorToHSL(color, hsl)

        hsl[1] = hsl[1].coerceIn(MIN_HERO_SATURATION, MAX_HERO_SATURATION)
        hsl[2] = hsl[2].coerceIn(MIN_HERO_LIGHTNESS, MAX_HERO_LIGHTNESS)

        return androidx.core.graphics.ColorUtils.HSLToColor(hsl)
    }

    companion object {
        private const val DEFAULT_HERO_COLOR_HEX = "#141414"
        private val DEFAULT_HERO_COLOR: Int = Color.parseColor(DEFAULT_HERO_COLOR_HEX)
        private const val MIN_HERO_SATURATION = 0.35f
        private const val MAX_HERO_SATURATION = 0.50f
        private const val MIN_HERO_LIGHTNESS = 0.15f
        private const val MAX_HERO_LIGHTNESS = 0.22f
    }

    private fun displayHome(categories: List<Category>) {
        categories
            .find { it.name == Category.FEATURED }
            ?.also {
                it.list.forEach { show ->
                    when (show) {
                        is Movie -> show.itemType = AppAdapter.Type.MOVIE_SWIPER_MOBILE_ITEM
                        is TvShow -> show.itemType = AppAdapter.Type.TV_SHOW_SWIPER_MOBILE_ITEM
                    }
                }
            }

        categories
            .find { it.name == Category.CONTINUE_WATCHING }
            ?.also {
                it.name = getString(R.string.home_continue_watching)
                it.list.forEach { show ->
                    when (show) {
                        is Episode -> show.itemType = AppAdapter.Type.EPISODE_CONTINUE_WATCHING_MOBILE_ITEM
                        is Movie -> show.itemType = AppAdapter.Type.MOVIE_CONTINUE_WATCHING_MOBILE_ITEM
                    }
                }
            }

        categories
            .find { it.name == Category.FAVORITE_MOVIES }
            ?.also { it.name = getString(R.string.home_favorite_movies) }

        categories
            .find { it.name == Category.FAVORITE_TV_SHOWS }
            ?.also { it.name = getString(R.string.home_favorite_tv_shows) }

        val preparedCategories = categories
            .filter { it.list.isNotEmpty() }
            .onEach { category ->
                if (category.name != Category.FEATURED && category.name != getString(R.string.home_continue_watching)) {
                    category.list.onEach { show ->
                        when (show) {
                            is Movie -> show.itemType = AppAdapter.Type.MOVIE_MOBILE_ITEM
                            is TvShow -> show.itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM
                        }
                    }
                }
                category.itemSpacing = resources.getDimension(R.dimen.home_mobile_item_spacing).toInt()
                category.itemType = when (category.name) {
                    Category.FEATURED -> AppAdapter.Type.CATEGORY_MOBILE_SWIPER
                    else -> AppAdapter.Type.CATEGORY_MOBILE_ITEM
                }
            }

        val signature = buildHomeSignature(preparedCategories)
        if (signature == lastRenderedHomeSignature) return
        lastRenderedHomeSignature = signature

        appAdapter.submitList(preparedCategories)
    }

    private fun buildHomeSignature(categories: List<Category>): String =
        buildString {
            categories.forEach { category ->
                append(category.name)
                append('|')
                append(category.itemType.ordinal)
                append('|')
                category.list.forEach { item ->
                    when (item) {
                        is Movie -> {
                            append("m:")
                            append(item.id)
                            append(':')
                            append(item.poster.orEmpty())
                            append(':')
                            append(item.isFavorite)
                            append(':')
                            append(item.watchHistory?.lastPlaybackPositionMillis ?: 0L)
                        }
                        is TvShow -> {
                            append("t:")
                            append(item.id)
                            append(':')
                            append(item.poster.orEmpty())
                            append(':')
                            append(item.isFavorite)
                        }
                        is Episode -> {
                            append("e:")
                            append(item.id)
                            append(':')
                            append(item.watchHistory?.lastPlaybackPositionMillis ?: 0L)
                        }
                        else -> append(item.hashCode())
                    }
                    append(';')
                }
                append('#')
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
        (binding.rvHome.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
        binding.rvHome.scrollToPosition(0)
        binding.rvHome.post {
            if (_binding == null) return@post
            (binding.rvHome.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
            binding.rvHome.scrollToPosition(0)
        }
    }
}
