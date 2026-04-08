package com.streamflixreborn.streamflix.ui

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import androidx.navigation.fragment.NavHostFragment
import com.bumptech.glide.Glide
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.DialogShowOptionsTvBinding
import com.streamflixreborn.streamflix.fragments.home.HomeTvFragment
import com.streamflixreborn.streamflix.fragments.home.HomeTvFragmentDirections
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.repository.DefaultUserContentRepository
import com.streamflixreborn.streamflix.utils.format
import com.streamflixreborn.streamflix.utils.getCurrentFragment
import com.streamflixreborn.streamflix.utils.toActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

class ShowOptionsTvDialog(
    context: Context,
    show: AppAdapter.Item,
) : Dialog(context) {

    private val binding = DialogShowOptionsTvBinding.inflate(LayoutInflater.from(context))

    private val database = AppDatabase.getInstance(context)
    private val userContentRepository = DefaultUserContentRepository(database)

    private fun checkProviderAndRun(show: AppAdapter.Item, action: () -> Unit) {
        // StreamingCommunity-only UX: non cambiamo provider in base all'item.
        action()
    }

    init {
        setContentView(binding.root)

        binding.btnOptionCancel.setOnClickListener {
            hide()
        }

        when (show) {
            is Episode -> displayEpisode(show)
            is Movie -> displayMovie(show)
            is TvShow -> displayTvShow(show)
        }


        window?.attributes = window?.attributes?.also { param ->
            param.gravity = Gravity.END
        }
        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.35).toInt(),
            context.resources.displayMetrics.heightPixels
        )
    }


    private fun displayEpisode(episode: Episode) {
        Glide.with(context)
            .load(episode.poster ?: episode.tvShow?.poster)
            .fallback(R.drawable.glide_fallback_cover)
            .fitCenter()
            .into(binding.ivOptionsShowPoster)

        binding.tvOptionsShowTitle.text = episode.tvShow?.title ?: ""

        binding.tvShowSubtitle.text = episode.season?.takeIf { it.number != 0 }?.let { season ->
            context.getString(
                R.string.episode_item_info,
                season.number,
                episode.number,
                episode.title ?: context.getString(
                    R.string.episode_number,
                    episode.number
                )
            )
        } ?: context.getString(
            R.string.episode_item_info_episode_only,
            episode.number,
            episode.title ?: context.getString(
                R.string.episode_number,
                episode.number
            )
        )


        binding.btnOptionEpisodeOpenTvShow.apply {
            setOnClickListener {
                when (val fragment = context.toActivity()?.getCurrentFragment()) {
                    is HomeTvFragment -> episode.tvShow?.let { tvShow ->
                        NavHostFragment.findNavController(fragment).navigate(
                            HomeTvFragmentDirections.actionHomeToTvShow(
                                id = tvShow.id
                            )
                        )
                    }
                }
                hide()
            }

            visibility = when (context.toActivity()?.getCurrentFragment()) {
                is HomeTvFragment -> View.VISIBLE
                else -> View.GONE
            }

            requestFocus()
        }

        binding.btnOptionShowFavorite.visibility = View.GONE

        binding.btnOptionShowWatched.apply {
            setOnClickListener {
                checkProviderAndRun(episode) {
                    CoroutineScope(Dispatchers.IO).launch {
                        val updatedEpisode = userContentRepository.setEpisodeWatched(episode, !episode.isWatched)
                        updatedEpisode.tvShow?.let { tvShow ->
                            val episodeDao = AppDatabase.getInstance(context).episodeDao()
                            if (updatedEpisode.isWatched && !episodeDao.hasAnyWatchHistoryForTvShow(tvShow.id)) {
                                userContentRepository.setTvShowWatching(tvShow, false)
                            }
                        }
                    }
                }

                hide()
            }

            text = when {
                episode.isWatched -> context.getString(R.string.option_show_unwatched)
                else -> context.getString(R.string.option_show_watched)
            }
            visibility = View.VISIBLE
        }
        binding.btnOptionEpisodeMarkAllPreviousWatched.apply {
            setOnClickListener {
                checkProviderAndRun(episode) {
                    CoroutineScope(Dispatchers.IO).launch {
                        val episodeDao = AppDatabase.getInstance(context).episodeDao()
                        val targetState = !episode.isWatched
                        userContentRepository.markEpisodesUpTo(episode, targetState)
                        episode.tvShow?.let { tvShow ->
                            if (targetState) {
                                if (!episodeDao.hasAnyWatchHistoryForTvShow(tvShow.id)) {
                                    userContentRepository.setTvShowWatching(tvShow, false)
                                }
                            } else {
                                userContentRepository.setTvShowWatching(tvShow, true)
                            }
                        }
                    }
                }

                hide()
            }

            text = when {
                episode.isWatched -> context.getString(R.string.option_show_mark_all_previous_unwatched)
                else -> context.getString(R.string.option_show_mark_all_previous_watched)
            }
            visibility = View.VISIBLE
        }

        binding.btnOptionProgramClear.apply {
            setOnClickListener {
                checkProviderAndRun(episode) {
                    CoroutineScope(Dispatchers.IO).launch {
                        userContentRepository.clearEpisodeProgress(episode)
                        episode.tvShow?.let { tvShow ->
                            val episodeDao = AppDatabase.getInstance(context).episodeDao()
                            if (!episodeDao.hasAnyWatchHistoryForTvShow(tvShow.id)) {
                                userContentRepository.setTvShowWatching(tvShow, false)
                            }
                        }
                    }
                }

                hide()
            }

            visibility = when {
                episode.watchHistory != null -> View.VISIBLE
                episode.tvShow?.isWatching ?: false -> View.VISIBLE
                else -> View.GONE
            }
        }
    }

    private fun displayMovie(movie: Movie) {
        Glide.with(context)
            .load(movie.poster)
            .fallback(R.drawable.glide_fallback_cover)
            .fitCenter()
            .into(binding.ivOptionsShowPoster)

        binding.tvOptionsShowTitle.text = movie.title

        binding.tvShowSubtitle.text = movie.released?.format("yyyy")


        binding.btnOptionEpisodeOpenTvShow.visibility = View.GONE

        binding.btnOptionShowFavorite.apply {
            setOnClickListener {
                checkProviderAndRun(movie) {
                    CoroutineScope(Dispatchers.IO).launch {
                        userContentRepository.toggleMovieFavorite(movie)
                    }
                }

                hide()
            }

            text = when {
                movie.isFavorite -> context.getString(R.string.option_show_unfavorite)
                else -> context.getString(R.string.option_show_favorite)
            }
            visibility = View.VISIBLE

            requestFocus()
        }

        binding.btnOptionShowWatched.apply {
            setOnClickListener {
                checkProviderAndRun(movie) {
                    CoroutineScope(Dispatchers.IO).launch {
                        userContentRepository.setMovieWatched(movie, !movie.isWatched)
                    }
                }

                hide()
            }

            text = when {
                movie.isWatched -> context.getString(R.string.option_show_unwatched)
                else -> context.getString(R.string.option_show_watched)
            }
            visibility = View.VISIBLE
        }

        binding.btnOptionProgramClear.apply {
            setOnClickListener {
                checkProviderAndRun(movie) {
                    CoroutineScope(Dispatchers.IO).launch {
                        userContentRepository.clearMovieProgress(movie)
                    }
                }

                hide()
            }

            visibility = when {
                movie.watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }
    }

    private fun displayTvShow(tvShow: TvShow) {
        Glide.with(context)
            .load(tvShow.poster)
            .fallback(R.drawable.glide_fallback_cover)
            .fitCenter()
            .into(binding.ivOptionsShowPoster)

        binding.tvOptionsShowTitle.text = tvShow.title

        binding.tvShowSubtitle.text = tvShow.released?.format("yyyy")


        binding.btnOptionEpisodeOpenTvShow.visibility = View.GONE

        binding.btnOptionShowFavorite.apply {
            setOnClickListener {
                checkProviderAndRun(tvShow) {
                    CoroutineScope(Dispatchers.IO).launch {
                        userContentRepository.toggleTvShowFavorite(tvShow)
                    }
                }

                hide()
            }

            text = when {
                tvShow.isFavorite -> context.getString(R.string.option_show_unfavorite)
                else -> context.getString(R.string.option_show_favorite)
            }
            visibility = View.VISIBLE

            requestFocus()
        }

        binding.btnOptionShowWatched.visibility = View.GONE

        binding.btnOptionProgramClear.visibility = View.GONE
    }
}
