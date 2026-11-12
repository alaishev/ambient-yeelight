package com.ambient.yeelight.service

import android.graphics.Color
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * @author Jaime on 4/12/2015
 * Based on the work of: Daniel Strebel,University of Zurich
 * Explained by Ivo Stoyanov in http://stackoverflow.com/questions/9018016/how-to-compare-two-colors
 */
object CIE76ColorDistanceCalculator {
    fun getColorDistance(color1: Int, color2: Int): Double {
        val r1 = Color.red(color1)
        val g1 = Color.green(color1)
        val b1 = Color.blue(color1)

        val r2 = Color.red(color2)
        val g2 = Color.green(color2)
        val b2 = Color.blue(color2)

        val lab1 = rgb2lab(r1, g1, b1)
        val lab2 = rgb2lab(r2, g2, b2)

        return sqrt(
            (lab2[0] - lab1[0]).toDouble().pow(2.0)
                    + (lab2[1] - lab1[1]).toDouble().pow(2.0)
                    + (lab2[2] - lab1[2]).toDouble().pow(2.0))
    }

    private fun rgb2lab(R: Int, G: Int, B: Int): IntArray {
        // http://www.brucelindbloom.com
        val eps = 216f / 24389f
        val k = 24389f / 27f

        // RGB to XYZ
        var r = R / 255f //R 0..1
        var g = G / 255f //G 0..1
        var b = B / 255f //B 0..1

        // assuming sRGB (D65)
        r = if (r <= 0.04045) r / 12 else ((r + 0.055) / 1.055).pow(2.4).toFloat()
        g = if (g <= 0.04045) g / 12 else ((g + 0.055) / 1.055).pow(2.4).toFloat()
        b = if (b <= 0.04045) b / 12 else ((b + 0.055) / 1.055).pow(2.4).toFloat()

        val x = 0.436052025f * r + 0.385081593f * g + 0.143087414f * b
        val y = 0.222491598f * r + 0.71688606f * g + 0.060621486f * b
        val z = 0.013929122f * r + 0.097097002f * g + 0.71418547f * b

        // XYZ to Lab
        val xr = x / 0.964221f
        val yr = y / 1.0f
        val zr = z / 0.825211f

        val fx = if (xr > eps) xr.toDouble().pow(1 / 3.0).toFloat() else ((k * xr + 16.0) / 116.0).toFloat()
        val fy = if (yr > eps) yr.toDouble().pow(1 / 3.0).toFloat() else ((k * yr + 16.0) / 116.0).toFloat()
        val fz = if (zr > eps) zr.toDouble().pow(1 / 3.0).toFloat() else ((k * zr + 16.0) / 116).toFloat()

        val lS: Float = 116 * fy - 16
        val aS: Float = 500 * (fx - fy)
        val bS: Float = 200 * (fy - fz)

        val lab = IntArray(3)

        lab[0] = (2.55 * lS + .5).toInt() // TODO remove scales to prevent flicking dark colors
        lab[1] = (aS + .5).toInt()
        lab[2] = (bS + .5).toInt()

        return lab
    }
}