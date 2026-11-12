package com.ambient.yeelight.network

import org.json.JSONArray

/**
 * What a lamp shows, as get_prop returns it for PROPS. Saved before the ambient light starts and
 * brought back after it stops.
 */
data class LampState(
    val power: Boolean,
    val bright: Int, // 1..100
    val colorMode: Int, // MODE_RGB, MODE_CT or MODE_HSV
    val rgb: Int, // 0xRRGGBB
    val ct: Int, // Color temperature, K
    val hue: Int, // 0..359
    val sat: Int, // 0..100
) {
    companion object {
        const val MODE_RGB = 1
        const val MODE_CT = 2
        const val MODE_HSV = 3

        /** The properties to ask get_prop for, in the order from() reads them. */
        val PROPS = listOf("power", "bright", "color_mode", "rgb", "ct", "hue", "sat")

        /** Reads the values of PROPS. The lamp sends them all as strings. */
        fun from(props: JSONArray): LampState = LampState(
            power = props.optString(0) == "on",
            bright = props.optInt(1),
            colorMode = props.optInt(2),
            rgb = props.optInt(3),
            ct = props.optInt(4),
            hue = props.optInt(5),
            sat = props.optInt(6),
        )
    }
}
