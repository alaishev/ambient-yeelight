package com.ambient.yeelight.service

import android.graphics.Color
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * @author Michael Simons
 * Computes the CIE94 distance between two COLORS.
 * See https://github.com/michael-simons/dfx-mosaic/blob/public/src/main/java/de/dailyfratze/mosaic/images/CIE94ColorDistance.java)
 */
object CIE94ColorDistanceCalculator {
    fun getColorDistance(color1: Int, color2: Int): Double {
        val lab1 = toLab(color1)
        val lab2 = toLab(color2)

        // Make it more readable
        val L1 = lab1[0].toDouble()
        val a1 = lab1[1].toDouble()
        val b1 = lab1[2].toDouble()

        val L2 = lab2[0].toDouble()
        val a2 = lab2[1].toDouble()
        val b2 = lab2[2].toDouble()

        // CIE94 coefficients for graphic arts
        val kL = 1.0
        val K1 = 0.045
        val K2 = 0.015

        // Weighting factors
        val sl = 1.0
        val kc = 1.0
        val kh = 1.0

        // See http://en.wikipedia.org/wiki/Color_difference#CIE94
        val c1 = sqrt(a1 * a1 + b1 * b1)
        val deltaC = c1 - sqrt(a2 * a2 + b2 * b2)
        val deltaA = a1 - a2
        val deltaB = b1 - b2
        val deltaH = sqrt(max(0.0, deltaA * deltaA + deltaB * deltaB - deltaC * deltaC))
        return sqrt(max(0.0, ((L1 - L2) / (kL * sl)).pow(2.0) + (deltaC / (kc * (1 + K1 * c1))).pow(2.0) + (deltaH / (kh * (1 + K2 * c1))).pow(2.0)))
    }

    private fun toLab(color: Int): FloatArray {

        // RGB to XYZ
        var r = Color.red(color) / 255f //R 0..1
        var g = Color.green(color) / 255f //G 0..1
        var b = Color.blue(color) / 255f //B 0..1

        // assuming sRGB (D65)
        r = if (r <= 0.04045) r / 12 else ((r + 0.055) / 1.055).pow(2.4).toFloat()
        g = if (g <= 0.04045) g / 12 else ((g + 0.055) / 1.055).pow(2.4).toFloat()
        b = if (b <= 0.04045) b / 12 else ((b + 0.055) / 1.055).pow(2.4).toFloat()

        val x = 0.436052025f * r + 0.385081593f * g + 0.143087414f * b
        val y = 0.222491598f * r + 0.71688606f * g + 0.060621486f * b
        val z = 0.013929122f * r + 0.097097002f * g + 0.71418547f * b


        val xyz = FloatArray(3)
        xyz[0] = x
        xyz[1] = y
        xyz[2] = z
        return xyzToLab(xyz)
    }

    private fun xyzToLab(colorValue: FloatArray): FloatArray {
        val l = f(colorValue[1])
        val L = 116.0 * l - 16.0
        val a = 500.0 * (f(colorValue[0]) - l)
        val b = 200.0 * (l - f(colorValue[2]))
        return floatArrayOf(L.toFloat(), a.toFloat(), b.toFloat())
    }

    private fun f(t: Float): Double {
        return if (t > 216.0 / 24389.0) {
            Math.cbrt(t.toDouble())
        }
        else {
            1.0 / 3.0 * (29.0 / 6.0).pow(2.0) * t + 4.0 / 29.0
        }
    }
}