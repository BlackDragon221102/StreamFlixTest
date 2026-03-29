package com.streamflixreborn.streamflix.fragments.genre

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextPaint
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import android.view.animation.DecelerateInterpolator
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnNextLayout
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
import kotlin.math.ceil
import kotlin.math.max

class GenreMobileFragment : Fragment() {

    private class GenreGridLayoutManager(
        context: android.content.Context,
        spanCount: Int,
    ) : GridLayoutManager(context, spanCount) {
        override fun supportsPredictiveItemAnimations(): Boolean = false
    }

    companion object {
        private const val KEY_SORT_MODE = "genre_sort_mode"
    }

    private var hasAutoCleared409: Boolean = false

    private var _binding: FragmentGenreMobileBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<GenreMobileFragmentArgs>()
    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory { GenreViewModel(args.id, database) }

    private var appAdapter = AppAdapter()
    private var backgroundColorJob: Job? = null
    private var lastBackgroundPosterUrl: String? = null
    private var headerScrollOffsetPx: Int = 0
    private var genreBaseColor: Int = HeroColorUtils.DEFAULT_HERO_COLOR
    private var currentGenre: Genre? = null
    private var currentHasMore: Boolean = false
    private var sortPopupWindow: PopupWindow? = null
    private var pendingScrollToTopAfterReload: Boolean = false
    private var pendingInvisibleSortReload: Boolean = false
    private var genreScrollListener: RecyclerView.OnScrollListener? = null

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
        savedInstanceState?.getString(KEY_SORT_MODE)
            ?.let { savedMode -> GenreViewModel.SortMode.entries.find { it.name == savedMode } }
            ?.let(viewModel::setSortMode)

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
                        if (pendingInvisibleSortReload) {
                            pendingInvisibleSortReload = false
                            pendingScrollToTopAfterReload = false
                            binding.rvGenre.visibility = View.VISIBLE
                            binding.rvGenre.alpha = 1f
                        }
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
        sortPopupWindow?.dismiss()
        sortPopupWindow = null
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
            appAdapter = createGenreAdapter()
            layoutManager = createGenreLayoutManager()
            itemAnimator = null
            preserveFocusAfterLayout = false
            isSaveEnabled = false
            adapter = appAdapter
            addItemDecoration(
                SpacingItemDecoration(10.dp(requireContext()))
            )
            genreScrollListener = object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    headerScrollOffsetPx = (headerScrollOffsetPx + dy).coerceAtLeast(0)
                    updateHeaderBackgroundByScroll()
                    applyGenreBackgroundByScroll()
                }
            }
            addOnScrollListener(genreScrollListener!!)
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SORT_MODE, viewModel.sortMode.value.name)
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
                binding.tvGenreSort.text = viewModel.sortMode.value.label
                binding.tvGenreSort.background = createSortButtonBackground()
                binding.tvGenreSort.setOnClickListener {
                    animateTapFeedback(binding.tvGenreSort) {
                        showSortMenu(binding.tvGenreSort)
                    }
                }
            }
        )

        val forceReplace = pendingScrollToTopAfterReload
        if (forceReplace) {
            hardResetGenreRecyclerForSort()
        }

        appAdapter.submitList(
            genre.shows.onEach {
                when (it) {
                    is Movie -> it.itemType = AppAdapter.Type.MOVIE_GENRE_MOBILE_ITEM
                    is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_GENRE_MOBILE_ITEM
                }
            },
            forceReplace = forceReplace
        ) {
            if (pendingScrollToTopAfterReload) {
                pendingScrollToTopAfterReload = false
                finalizeSortReloadAtTop()
            }
        }

        if (hasMore) {
            appAdapter.setOnLoadMoreListener { viewModel.loadMoreGenreShows() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
    }

    private fun showSortMenu(anchor: View) {
        sortPopupWindow?.dismiss()

        val popupContent = layoutInflater.inflate(R.layout.popup_genre_sort_mobile, null)
        popupContent.background = createSortPopupBackground()
        val optionsContainer = popupContent.findViewById<LinearLayout>(R.id.layout_sort_popup)
        val selectionHighlight = popupContent.findViewById<View>(R.id.view_sort_selection_highlight)
        selectionHighlight.background = createSortSelectionBackground()
        val currentSortMode = viewModel.sortMode.value
        val popupWidth = measureSortPopupWidth()
        var selectedOptionView: View? = null

        GenreViewModel.SortMode.entries.forEach { mode ->
            val optionView = layoutInflater.inflate(
                R.layout.item_genre_sort_option_mobile,
                optionsContainer,
                false
            )
            val labelView = optionView.findViewById<TextView>(R.id.tv_sort_option_label)
            val isSelected = currentSortMode == mode

            labelView.text = mode.label
            labelView.alpha = if (isSelected) 1f else 0.82f
            labelView.setTextColor(
                if (isSelected) Color.WHITE else Color.parseColor("#E3E3E5")
            )
            optionView.background =
                requireContext().getDrawable(R.drawable.bg_genre_sort_option_default_mobile)
            optionView.isClickable = true
            optionView.isFocusable = true
            labelView.isClickable = false
            if (isSelected) {
                selectedOptionView = optionView
            }
            optionView.setOnClickListener {
                animateSortOptionSelection(selectionHighlight, optionView, labelView) {
                    onSortModeSelected(mode)
                    sortPopupWindow?.dismiss()
                }
            }
            optionsContainer.addView(optionView)
        }

        sortPopupWindow = PopupWindow(
            popupContent,
            popupWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 18f
            isOutsideTouchable = true
            isTouchable = true
        }

        sortPopupWindow?.showAsDropDown(anchor, 0, 10.dp(requireContext()))
        popupContent.post {
            selectedOptionView?.let { optionView ->
                placeSelectionHighlight(selectionHighlight, optionView, animate = false)
            }
        }
        popupContent.alpha = 0f
        popupContent.translationY = (-8).dp(requireContext()).toFloat()
        popupContent.scaleX = 0.985f
        popupContent.scaleY = 0.985f
        popupContent.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun animateTapFeedback(view: View, onEnd: () -> Unit) {
        view.animate().cancel()
        view.animate()
            .scaleX(0.97f)
            .scaleY(0.97f)
            .alpha(0.92f)
            .setDuration(70L)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(150L)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction(onEnd)
                    .start()
            }
            .start()
    }

    private fun animateSortOptionSelection(
        selectionHighlight: View,
        optionView: View,
        labelView: TextView,
        onEnd: () -> Unit,
    ) {
        placeSelectionHighlight(selectionHighlight, optionView, animate = false)
        selectionHighlight.alpha = 0f
        selectionHighlight.animate().cancel()
        selectionHighlight.animate()
            .alpha(1f)
            .setDuration(170L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        labelView.animate().cancel()
        labelView.animate()
            .alpha(1f)
            .setDuration(180L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        labelView.setTextColor(Color.WHITE)

        optionView.postDelayed(onEnd, 190L)
    }

    private fun placeSelectionHighlight(
        highlightView: View,
        targetView: View,
        animate: Boolean,
    ) {
        val targetTop = targetView.top.toFloat()
        val targetHeight = targetView.height.takeIf { it > 0 } ?: return

        highlightView.visibility = View.VISIBLE
        highlightView.pivotY = 0f
        highlightView.pivotX = (highlightView.width / 2f).takeIf { it > 0f } ?: 0f

        if (!animate || highlightView.alpha == 0f) {
            highlightView.animate().cancel()
            highlightView.translationY = targetTop
            highlightView.layoutParams = highlightView.layoutParams.apply {
                height = targetHeight
            }
            highlightView.alpha = 1f
            highlightView.scaleY = 1f
            highlightView.requestLayout()
            return
        }

        val startHeight = highlightView.height.takeIf { it > 0 } ?: targetHeight
        val endScaleY = targetHeight.toFloat() / startHeight.toFloat()
        highlightView.animate().cancel()
        highlightView.alpha = 0.94f
        highlightView.animate()
            .translationY(targetTop)
            .scaleY(endScaleY)
            .alpha(1f)
            .setDuration(185L)
            .setInterpolator(DecelerateInterpolator(1.15f))
            .withEndAction {
                highlightView.scaleY = 1f
                highlightView.layoutParams = highlightView.layoutParams.apply {
                    height = targetHeight
                }
                highlightView.requestLayout()
            }
            .start()
    }

    private fun measureSortPopupWidth(): Int {
        val textPaint = TextPaint().apply {
            isAntiAlias = true
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                15f,
                resources.displayMetrics
            )
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val widestLabel = GenreViewModel.SortMode.entries.maxOf { mode ->
            ceil(textPaint.measureText(mode.label).toDouble()).toInt()
        }

        val horizontalPadding =
            10.dp(requireContext()) * 2 + 16.dp(requireContext()) * 2
        val breathingRoom = 8.dp(requireContext())
        val minWidth = 196.dp(requireContext())

        return max(minWidth, widestLabel + horizontalPadding + breathingRoom)
    }

    private fun createSortButtonBackground(): GradientDrawable {
        val baseSurface = androidx.core.graphics.ColorUtils.blendARGB(genreBaseColor, Color.BLACK, 0.76f)
        val buttonColor = androidx.core.graphics.ColorUtils.blendARGB(baseSurface, Color.WHITE, 0.05f)
        val strokeColor = androidx.core.graphics.ColorUtils.setAlphaComponent(Color.WHITE, 0x22)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16.dp(requireContext()).toFloat()
            setColor(buttonColor)
            setStroke(1.dp(requireContext()), strokeColor)
        }
    }

    private fun createSortPopupBackground(): GradientDrawable {
        val baseSurface = androidx.core.graphics.ColorUtils.blendARGB(genreBaseColor, Color.BLACK, 0.80f)
        val popupColor = androidx.core.graphics.ColorUtils.blendARGB(baseSurface, Color.WHITE, 0.025f)
        val strokeColor = androidx.core.graphics.ColorUtils.setAlphaComponent(Color.WHITE, 0x18)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18.dp(requireContext()).toFloat()
            setColor(popupColor)
            setStroke(1.dp(requireContext()), strokeColor)
        }
    }

    private fun createSortSelectionBackground(): GradientDrawable {
        val baseSurface = androidx.core.graphics.ColorUtils.blendARGB(genreBaseColor, Color.BLACK, 0.70f)
        val selectionColor = androidx.core.graphics.ColorUtils.blendARGB(baseSurface, Color.WHITE, 0.08f)
        val strokeColor = androidx.core.graphics.ColorUtils.setAlphaComponent(Color.WHITE, 0x20)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12.dp(requireContext()).toFloat()
            setColor(selectionColor)
            setStroke(1.dp(requireContext()), strokeColor)
        }
    }

    private fun refreshSortUiSurfaces() {
        binding.rvGenre.findViewById<TextView?>(R.id.tv_genre_sort)?.background = createSortButtonBackground()
        sortPopupWindow?.contentView?.background = createSortPopupBackground()
        sortPopupWindow?.contentView
            ?.findViewById<View?>(R.id.view_sort_selection_highlight)
            ?.background = createSortSelectionBackground()
    }

    private fun onSortModeSelected(selectedMode: GenreViewModel.SortMode) {
        if (viewModel.sortMode.value == selectedMode) return
        pendingScrollToTopAfterReload = true
        pendingInvisibleSortReload = true
        currentGenre = null
        currentHasMore = false
        binding.isLoading.root.visibility = View.VISIBLE
        binding.isLoading.pbIsLoading.visibility = View.VISIBLE
        binding.isLoading.gIsLoadingRetry.visibility = View.GONE
        binding.rvGenre.stopScroll()
        binding.rvGenre.alpha = 1f
        binding.rvGenre.visibility = View.INVISIBLE
        hardResetGenreRecyclerForSort()
        appAdapter.submitList(emptyList(), forceReplace = true)
        viewModel.setSortMode(selectedMode)
    }

    private fun createGenreLayoutManager(): GridLayoutManager =
        GenreGridLayoutManager(requireContext(), 3).also { layoutManager ->
            layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    val viewType = appAdapter.getItemViewType(position)
                    return when (AppAdapter.Type.entries[viewType]) {
                        AppAdapter.Type.HEADER -> layoutManager.spanCount
                        else -> 1
                    }
                }
            }
        }

    private fun createGenreAdapter(): AppAdapter =
        AppAdapter().apply {
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }

    private fun hardResetGenreRecyclerForSort() {
        binding.rvGenre.stopScroll()
        headerScrollOffsetPx = 0
        updateHeaderBackgroundByScroll()
        applyGenreBackgroundByScroll()

        genreScrollListener?.let { binding.rvGenre.removeOnScrollListener(it) }
        genreScrollListener = null
        binding.rvGenre.adapter = null
        binding.rvGenre.layoutManager = null
        binding.rvGenre.recycledViewPool.clear()
        binding.rvGenre.clearOnScrollListeners()
        binding.rvGenre.scrollTo(0, 0)

        appAdapter = createGenreAdapter()
        binding.rvGenre.adapter = appAdapter
        binding.rvGenre.layoutManager = createGenreLayoutManager()
        genreScrollListener = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                headerScrollOffsetPx = (headerScrollOffsetPx + dy).coerceAtLeast(0)
                updateHeaderBackgroundByScroll()
                applyGenreBackgroundByScroll()
            }
        }
        binding.rvGenre.addOnScrollListener(genreScrollListener!!)
        (binding.rvGenre.layoutManager as? GridLayoutManager)?.scrollToPositionWithOffset(0, 0)
        binding.rvGenre.scrollToPosition(0)
    }

    private fun finalizeSortReloadAtTop() {
        scrollGenreToTop()
        binding.rvGenre.doOnNextLayout {
            scrollGenreToTop()
            if (pendingInvisibleSortReload) {
                pendingInvisibleSortReload = false
                binding.rvGenre.visibility = View.VISIBLE
                binding.rvGenre.alpha = 1f
            }
        }
    }

    private fun scrollGenreToTop() {
        binding.rvGenre.stopScroll()
        headerScrollOffsetPx = 0
        updateHeaderBackgroundByScroll()
        applyGenreBackgroundByScroll()
        (binding.rvGenre.layoutManager as? GridLayoutManager)?.scrollToPositionWithOffset(0, 0)
        binding.rvGenre.post {
            binding.rvGenre.stopScroll()
            headerScrollOffsetPx = 0
            updateHeaderBackgroundByScroll()
            applyGenreBackgroundByScroll()
            (binding.rvGenre.layoutManager as? GridLayoutManager)?.scrollToPositionWithOffset(0, 0)
            binding.rvGenre.scrollToPosition(0)
        }
        binding.rvGenre.doOnNextLayout {
            binding.rvGenre.stopScroll()
            headerScrollOffsetPx = 0
            updateHeaderBackgroundByScroll()
            applyGenreBackgroundByScroll()
            (binding.rvGenre.layoutManager as? GridLayoutManager)?.scrollToPositionWithOffset(0, 0)
            binding.rvGenre.scrollToPosition(0)
        }
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
                refreshSortUiSurfaces()
            }
        }
    }
}
