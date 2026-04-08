package com.streamflixreborn.streamflix.database.dao

import android.util.Log
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.streamflixreborn.streamflix.models.Movie
import kotlinx.coroutines.flow.Flow
import androidx.room.Transaction
import com.streamflixreborn.streamflix.utils.UserPreferences

@Dao
interface MovieDao {

    @Query("SELECT * FROM movies")
    fun getAll(): List<Movie>

    @Query("SELECT * FROM movies WHERE id = :id")
    fun getById(id: String): Movie?

    @Query("SELECT * FROM movies WHERE id = :id")
    fun getByIdAsFlow(id: String): Flow<Movie?>

    @Query("SELECT * FROM movies WHERE id IN (:ids)")
    fun getByIds(ids: List<String>): Flow<List<Movie>>

    @Query("SELECT * FROM movies WHERE isFavorite = 1 ORDER BY IFNULL(favoriteAddedAtUtcMillis, 0) DESC, title COLLATE NOCASE ASC")
    fun getFavorites(): Flow<List<Movie>>

    @Query("SELECT * FROM movies WHERE lastEngagementTimeUtcMillis IS NOT NULL ORDER BY lastEngagementTimeUtcMillis DESC")
    fun getWatchingMovies(): Flow<List<Movie>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(movie: Movie)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(movies: List<Movie>)

    @Update
    fun update(movie: Movie)

    @Query("DELETE FROM movies")
    fun deleteAll()

    @Query(
        """
        DELETE FROM movies
        WHERE isFavorite = 0
          AND isWatched = 0
          AND watchedDate IS NULL
          AND lastEngagementTimeUtcMillis IS NULL
          AND lastPlaybackPositionMillis IS NULL
          AND durationMillis IS NULL
        """
    )
    fun deleteCatalogOnlyEntries()

    @Transaction
    fun upsertCatalog(movie: Movie) {
        val existing = getById(movie.id)
        if (existing != null) {
            existing.mergeCatalogFrom(movie)
            update(existing)
        } else {
            insert(movie.copy().apply {
                isFavorite = false
                isWatched = false
                watchedDate = null
                watchHistory = null
            })
        }
    }

    @Transaction
    fun upsertCatalogAll(movies: List<Movie>) {
        movies.forEach(::upsertCatalog)
    }

    @Transaction
    fun save(movie: Movie) {
        val provider = UserPreferences.currentProvider?.name ?: "Unknown"
        val existing = getById(movie.id)
        if (existing != null) {
            val merged = existing.mergeCatalogFrom(movie).applyUserStateFrom(movie)
            update(merged)
            Log.d("DatabaseVerify", "[$provider] REAL-TIME UPDATE Movie: ${merged.title} (Fav: ${merged.isFavorite}, Watched: ${merged.isWatched})")
        } else {
            insert(movie)
            Log.d("DatabaseVerify", "[$provider] REAL-TIME INSERT Movie: ${movie.title} (Fav: ${movie.isFavorite})")
        }
    }

    @Transaction
    fun setFavoriteWithLog(id: String, favorite: Boolean) {
        val provider = UserPreferences.currentProvider?.name ?: "Unknown"
        setFavorite(id, favorite)
        Log.d("DatabaseVerify", "[$provider] REAL-TIME Favorite Toggled: ID $id -> $favorite")
    }

    @Query("UPDATE movies SET isFavorite = :favorite WHERE id = :id")
    fun setFavorite(id: String, favorite: Boolean)

}
