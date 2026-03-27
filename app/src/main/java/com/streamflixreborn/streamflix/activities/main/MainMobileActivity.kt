package com.streamflixreborn.streamflix.activities.main

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.navOptions
import androidx.navigation.fragment.NavHostFragment
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.ActivityMainMobileBinding
import com.streamflixreborn.streamflix.fragments.player.PlayerMobileFragment
import com.streamflixreborn.streamflix.ui.UpdateAppMobileDialog
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.Cine24hProvider
import com.streamflixreborn.streamflix.utils.TopLevelTabFragment
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.getCurrentFragment
import kotlinx.coroutines.launch

class MainMobileActivity : FragmentActivity() {

    private var _binding: ActivityMainMobileBinding? = null
    private val binding get() = _binding!!

    private val viewModel by viewModels<MainViewModel>()
    private var pendingTopLevelScrollDestinationId: Int? = null
    private var pendingTopLevelScrollAnimate: Boolean = false

    private lateinit var updateAppDialog: UpdateAppMobileDialog

    override fun onCreate(savedInstanceState: Bundle?) {
        // Tema impostato in base alle preferenze già caricate in StreamFlixApp
        when (UserPreferences.selectedTheme) {
            "nero_amoled_oled" -> setTheme(R.style.AppTheme_Mobile_NeroAmoledOled)
            else -> setTheme(R.style.AppTheme_Mobile)
        }

        super.onCreate(savedInstanceState)
        
        // Inizializza il provider con il context dell'attività per gestire eventuali bypass visibili
        Cine24hProvider.init(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        _binding = ActivityMainMobileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.mainContent) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
            val currentFragment = navHostFragment?.childFragmentManager?.primaryNavigationFragment

            val isPlayer = currentFragment is PlayerMobileFragment
            val isBottomNavVisible = binding.bnvMain.visibility == View.VISIBLE

            val bottomPadding = if (isPlayer || isBottomNavVisible) 0 else insets.bottom
            val topPadding = 0

            view.setPadding(insets.left, topPadding, insets.right, bottomPadding)
            windowInsets
        }

        updateImmersiveMode()

        val navHostFragment = this.supportFragmentManager
            .findFragmentById(binding.navMainFragment.id) as NavHostFragment
        val navController = navHostFragment.navController

        // Reindirizzamento TV se necessario
        if (BuildConfig.APP_LAYOUT == "tv" || (BuildConfig.APP_LAYOUT != "mobile" && packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))) {
            finish()
            startActivity(Intent(this, MainTvActivity::class.java))
            return
        }

        if (savedInstanceState == null) {
            UserPreferences.currentProvider?.let {
                navController.navigate(R.id.home)
            }
        }

        binding.bnvMain.setOnItemSelectedListener { item ->
            navigateToTopLevelDestination(navController, item.itemId)
        }
        binding.bnvMain.setOnItemReselectedListener { item ->
            navigateToTopLevelDestination(navController, item.itemId)
        }
        
        updateNavigationVisibility()

        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.search, R.id.home, R.id.movies, R.id.tv_shows, R.id.settings -> {
                    binding.bnvMain.visibility = View.VISIBLE
                    binding.bnvMain.menu.findItem(destination.id)?.isChecked = true
                    updateNavigationVisibility()
                    updateImmersiveMode()
                    if (pendingTopLevelScrollDestinationId == destination.id) {
                        val animate = pendingTopLevelScrollAnimate
                        pendingTopLevelScrollDestinationId = null
                        pendingTopLevelScrollAnimate = false
                        binding.mainContent.post {
                            (getCurrentFragment() as? TopLevelTabFragment)?.onTopLevelTabSelected(animate)
                        }
                    }
                }
                else -> binding.bnvMain.visibility = View.GONE
            }
            binding.mainContent.post { binding.mainContent.requestApplyInsets() }
        }

        lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    is MainViewModel.State.SuccessCheckingUpdate -> {
                        updateAppDialog = UpdateAppMobileDialog(this@MainMobileActivity, state.newReleases).also {
                            it.setOnUpdateClickListener { _ ->
                                if (!it.isLoading) viewModel.downloadUpdate(this@MainMobileActivity, state.asset)
                            }
                            it.show()
                        }
                    }
                    MainViewModel.State.DownloadingUpdate -> if (::updateAppDialog.isInitialized) updateAppDialog.isLoading = true
                    is MainViewModel.State.SuccessDownloadingUpdate -> {
                        viewModel.installUpdate(this@MainMobileActivity, state.apk)
                        if (::updateAppDialog.isInitialized) updateAppDialog.hide()
                    }
                    MainViewModel.State.InstallingUpdate -> if (::updateAppDialog.isInitialized) updateAppDialog.isLoading = true
                    is MainViewModel.State.FailedUpdate -> {
                        Toast.makeText(this@MainMobileActivity, state.error.message ?: "Update failed", Toast.LENGTH_SHORT).show()
                    }
                    else -> {}
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val handled = (getCurrentFragment() as? PlayerMobileFragment)?.onBackPressed() ?: false
                if (handled) return

                when (navController.currentDestination?.id) {
                    R.id.home -> finish()
                    R.id.search, R.id.movies, R.id.tv_shows, R.id.settings -> binding.bnvMain.findViewById<View>(R.id.home).performClick()
                    else -> if (!navController.navigateUp()) finish()
                }
            }
        })
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        (getCurrentFragment() as? PlayerMobileFragment)?.onUserLeaveHint()
    }
    
    private fun updateNavigationVisibility() {
        UserPreferences.currentProvider?.let { provider ->
            binding.bnvMain.menu.findItem(R.id.movies)?.isVisible = Provider.supportsMovies(provider)
            val tvShowsItem = binding.bnvMain.menu.findItem(R.id.tv_shows)
            tvShowsItem?.isVisible = Provider.supportsTvShows(provider)
            
            tvShowsItem?.title = if (provider.name == "CableVisionHD" || provider.name == "TvporinternetHD") 
                getString(R.string.main_menu_all_channels) else getString(R.string.main_menu_tv_shows)
        }
    }

    private fun navigateToTopLevelDestination(
        navController: androidx.navigation.NavController,
        destinationId: Int,
    ): Boolean {
        if (navController.currentDestination?.id == destinationId) {
            (getCurrentFragment() as? TopLevelTabFragment)?.onTopLevelTabSelected(true)
            return true
        }

        pendingTopLevelScrollDestinationId = destinationId
        pendingTopLevelScrollAnimate = false
        runCatching {
            navController.navigate(destinationId, null, navOptions {
                anim {
                    enter = R.anim.fade_in
                    exit = R.anim.fade_out
                    popEnter = R.anim.fade_in
                    popExit = R.anim.fade_out
                }
                popUpTo(navController.graph.startDestinationId) {
                    inclusive = false
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            })
        }
        return true
    }

    fun updateImmersiveMode() {
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        if (UserPreferences.immersiveMode) {
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }
    }
}
