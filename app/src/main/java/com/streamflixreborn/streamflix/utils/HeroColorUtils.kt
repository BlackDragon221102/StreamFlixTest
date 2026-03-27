package com.streamflixreborn.streamflix.utils

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette

object HeroColorUtils {
    val DEFAULT_HERO_COLOR: Int = Color.parseColor("#141414")

    private const val MIN_HERO_SATURATION = 0.35f
    private const val MAX_HERO_SATURATION = 0.50f
    private const val MIN_HERO_LIGHTNESS = 0.15f
    private const val MAX_HERO_LIGHTNESS = 0.22f

    fun extractNormalizedHeroColor(bitmap: Bitmap): Int {
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
        ColorUtils.colorToHSL(color, hsl)
        hsl[1] = hsl[1].coerceIn(MIN_HERO_SATURATION, MAX_HERO_SATURATION)
        hsl[2] = hsl[2].coerceIn(MIN_HERO_LIGHTNESS, MAX_HERO_LIGHTNESS)
        return ColorUtils.HSLToColor(hsl)
    }
}
