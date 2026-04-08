package com.streamflixreborn.streamflix.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkResolverTest {

    @Test
    fun `italian tmdb artwork wins over english artwork when both exist`() {
        val italian = TmdbUtils.Artwork(
            poster = "https://image.tmdb.org/t/p/original/it-poster.jpg",
            banner = "https://image.tmdb.org/t/p/original/it-banner.jpg",
            heroPoster = "https://image.tmdb.org/t/p/original/it-hero.jpg",
            hasTextlessHero = true,
        )
        val english = TmdbUtils.Artwork(
            poster = "https://image.tmdb.org/t/p/original/en-poster.jpg",
            banner = "https://image.tmdb.org/t/p/original/en-banner.jpg",
            heroPoster = "https://image.tmdb.org/t/p/original/en-hero.jpg",
            hasTextlessHero = true,
        )

        val resolved = ArtworkResolver.mergeLocalizedTmdbArtwork(italian, english)

        assertEquals(italian.poster, resolved?.poster)
        assertEquals(italian.banner, resolved?.banner)
        assertEquals(italian.heroPoster, resolved?.heroPoster)
        assertTrue(resolved?.hasTextlessHero == true)
    }

    @Test
    fun `english tmdb artwork fills missing italian fields before provider fallback`() {
        val italian = TmdbUtils.Artwork(
            poster = null,
            banner = "https://image.tmdb.org/t/p/original/it-banner.jpg",
            heroPoster = null,
            hasTextlessHero = false,
        )
        val english = TmdbUtils.Artwork(
            poster = "https://image.tmdb.org/t/p/original/en-poster.jpg",
            banner = "https://image.tmdb.org/t/p/original/en-banner.jpg",
            heroPoster = "https://image.tmdb.org/t/p/original/en-hero.jpg",
            hasTextlessHero = true,
        )

        val resolved = ArtworkResolver.mergeLocalizedTmdbArtwork(italian, english)

        assertEquals(english.poster, resolved?.poster)
        assertEquals(italian.banner, resolved?.banner)
        assertEquals(english.heroPoster, resolved?.heroPoster)
        assertTrue(resolved?.hasTextlessHero == true)
    }
}
