package com.streamflixreborn.streamflix.utils

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette

object HeroColorUtils {
    const val CACHE_VERSION: Int = 4
    val DEFAULT_HERO_COLOR: Int = Color.parseColor("#141414")

    private const val MIN_HERO_SATURATION = 0.42f
    private const val MAX_HERO_SATURATION = 0.62f
    private const val MIN_HERO_LIGHTNESS = 0.18f
    private const val MAX_HERO_LIGHTNESS = 0.26f
    private const val MAX_SAMPLE_WIDTH = 64
    private const val MAX_SAMPLE_HEIGHT = 40

    fun extractNormalizedHeroColor(bitmap: Bitmap): Int {
        val sampleBitmap = bitmap.extractBackdropArea()
        val palette = Palette.from(sampleBitmap)
            .clearFilters()
            .generate()

        val dominantColor = palette.dominantSwatch?.rgb ?: calculateAverageColor(sampleBitmap)
        val edgeColor = calculateEdgeColor(sampleBitmap)
        val topBandColor = calculateTopBandColor(sampleBitmap)
        val backdropColor = when {
            isUsableBackdropColor(topBandColor) -> ColorUtils.blendARGB(topBandColor, edgeColor, 0.45f)
            else -> edgeColor
        }
        val selectedColor = when {
            isNearNeutral(backdropColor) -> backdropColor
            shouldIgnoreDominant(backdropColor, dominantColor) -> ColorUtils.blendARGB(backdropColor, dominantColor, 0.10f)
            else -> ColorUtils.blendARGB(backdropColor, dominantColor, 0.18f)
        }
        if (sampleBitmap !== bitmap) {
            sampleBitmap.recycle()
        }

        return normalizeHeroColor(selectedColor)
    }

    private fun Bitmap.extractBackdropArea(): Bitmap {
        if (width <= 0 || height <= 1) return this
        val cropHeight = (height * 0.50f).toInt().coerceIn(1, height)
        val cropTop = 0
        val cropped = Bitmap.createBitmap(this, 0, cropTop, width, cropHeight)
        val targetWidth = cropped.width.coerceAtMost(MAX_SAMPLE_WIDTH)
        val targetHeight = cropped.height.coerceAtMost(MAX_SAMPLE_HEIGHT)
        if (cropped.width == targetWidth && cropped.height == targetHeight) return cropped

        val scaled = Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)
        if (scaled !== cropped) {
            cropped.recycle()
        }
        return scaled
    }

    private fun calculateAverageColor(bitmap: Bitmap): Int {
        if (bitmap.width <= 0 || bitmap.height <= 0) return DEFAULT_HERO_COLOR

        val pixels = bitmap.getPixels()
        var red = 0L
        var green = 0L
        var blue = 0L
        val totalPixels = pixels.size

        for (pixel in pixels) {
            red += Color.red(pixel)
            green += Color.green(pixel)
            blue += Color.blue(pixel)
        }

        return Color.rgb(
            (red / totalPixels).toInt().coerceIn(0, 255),
            (green / totalPixels).toInt().coerceIn(0, 255),
            (blue / totalPixels).toInt().coerceIn(0, 255)
        )
    }

    private fun calculateEdgeColor(bitmap: Bitmap): Int {
        if (bitmap.width <= 0 || bitmap.height <= 0) return DEFAULT_HERO_COLOR

        val width = bitmap.width
        val height = bitmap.height
        val pixels = bitmap.getPixels()
        val stripWidth = (width * 0.16f).toInt().coerceAtLeast(1)
        val stripHeight = (height * 0.28f).toInt().coerceAtLeast(1)
        val sideHeight = (height * 0.52f).toInt().coerceAtLeast(1)

        var red = 0L
        var green = 0L
        var blue = 0L
        var samples = 0L

        fun accumulate(startX: Int, endX: Int, startY: Int, endY: Int) {
            for (x in startX until endX.coerceAtMost(width)) {
                for (y in startY until endY.coerceAtMost(height)) {
                    val pixel = pixels[(y * width) + x]
                    red += Color.red(pixel)
                    green += Color.green(pixel)
                    blue += Color.blue(pixel)
                    samples++
                }
            }
        }

        accumulate(0, width, 0, stripHeight)
        accumulate(0, stripWidth, 0, sideHeight)
        accumulate(width - stripWidth, width, 0, sideHeight)

        if (samples == 0L) return calculateAverageColor(bitmap)

        return Color.rgb(
            (red / samples).toInt().coerceIn(0, 255),
            (green / samples).toInt().coerceIn(0, 255),
            (blue / samples).toInt().coerceIn(0, 255)
        )
    }

    private fun calculateTopBandColor(bitmap: Bitmap): Int {
        if (bitmap.width <= 0 || bitmap.height <= 0) return DEFAULT_HERO_COLOR

        val width = bitmap.width
        val height = bitmap.height
        val pixels = bitmap.getPixels()
        val bandHeight = (height * 0.16f).toInt().coerceAtLeast(1)
        val innerPadding = (width * 0.08f).toInt()
        val startX = innerPadding.coerceAtMost(width - 1)
        val endX = (width - innerPadding).coerceAtLeast(startX + 1)

        var red = 0L
        var green = 0L
        var blue = 0L
        var samples = 0L

        for (x in startX until endX) {
            for (y in 0 until bandHeight.coerceAtMost(height)) {
                val pixel = pixels[(y * width) + x]
                red += Color.red(pixel)
                green += Color.green(pixel)
                blue += Color.blue(pixel)
                samples++
            }
        }

        if (samples == 0L) return DEFAULT_HERO_COLOR

        return Color.rgb(
            (red / samples).toInt().coerceIn(0, 255),
            (green / samples).toInt().coerceIn(0, 255),
            (blue / samples).toInt().coerceIn(0, 255)
        )
    }

    private fun isUsableBackdropColor(color: Int): Boolean {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        val saturation = hsl[1]
        val lightness = hsl[2]
        return saturation >= 0.08f && lightness in 0.08f..0.72f
    }

    private fun isNearNeutral(color: Int): Boolean {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        return hsl[1] < 0.16f
    }

    private fun shouldIgnoreDominant(backdropColor: Int, dominantColor: Int): Boolean {
        val backdropHsl = FloatArray(3)
        val dominantHsl = FloatArray(3)
        ColorUtils.colorToHSL(backdropColor, backdropHsl)
        ColorUtils.colorToHSL(dominantColor, dominantHsl)

        val hueDistance = hueDistance(backdropHsl[0], dominantHsl[0])
        val backdropSaturation = backdropHsl[1]
        val dominantSaturation = dominantHsl[1]

        return hueDistance > 42f && dominantSaturation > backdropSaturation + 0.18f
    }

    private fun hueDistance(firstHue: Float, secondHue: Float): Float {
        val diff = kotlin.math.abs(firstHue - secondHue)
        return kotlin.math.min(diff, 360f - diff)
    }

    private fun normalizeHeroColor(color: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[1] = when {
            hsl[1] < 0.16f -> hsl[1].coerceIn(0.02f, 0.16f)
            hsl[1] < 0.30f -> hsl[1].coerceIn(0.16f, 0.32f)
            else -> hsl[1].coerceIn(MIN_HERO_SATURATION, MAX_HERO_SATURATION)
        }
        hsl[2] = when {
            hsl[1] < 0.16f -> hsl[2].coerceIn(0.18f, 0.24f)
            else -> hsl[2].coerceIn(MIN_HERO_LIGHTNESS, MAX_HERO_LIGHTNESS)
        }
        return ColorUtils.HSLToColor(hsl)
    }

    private fun Bitmap.getPixels(): IntArray {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return pixels
    }
}
