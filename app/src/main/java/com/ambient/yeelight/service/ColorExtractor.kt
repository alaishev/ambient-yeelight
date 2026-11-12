package com.ambient.yeelight.service

import android.graphics.Bitmap
import androidx.palette.graphics.Palette
import com.ambient.yeelight.logger

/**
 * Finds the dominant color of screen frames and passes it on only when it changes noticeably, so
 * the lamps get no commands for changes the eye does not see: by more than minDeltaE, CIE76.
 */
class ColorExtractor(private val minDeltaE: Float) {
    private var lastColor = BLACK // The last color returned by nextColor()

    /**
     * The dominant color of [frame], `0xRRGGBB`, if it differs from the last one returned by more
     * than minDeltaE; null if it does not, or if the frame is black.
     */
    fun nextColor(frame: Bitmap): Int? {
        val color = dominantColor(frame)

        if (color == BLACK || color == lastColor) return null

        if (lastColor != BLACK) {
            val deltaE = CIE76ColorDistanceCalculator.getColorDistance(color, lastColor)

            logger.d(TAG, "Color ${"%06X".format(color)}, deltaE $deltaE")
            if (deltaE <= minDeltaE) return null
        }

        lastColor = color
        return color
    }

    /** The color most pixels of [frame] are close to, `0xRRGGBB`; black if there is none. */
    private fun dominantColor(frame: Bitmap): Int {
        // The frame is small already: Palette must not scale it down further
        val palette = Palette.from(frame)
            .resizeBitmapArea(frame.width * frame.height)
            .generate()

        return palette.getDominantColor(BLACK) and RGB_MASK
    }

    private companion object {
        const val TAG = "ColorExtractor"
        const val BLACK = 0x000000
        const val RGB_MASK = 0xFFFFFF // Drops the alpha
    }
}
