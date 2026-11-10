package com.ambient.yeelight.network

import org.json.JSONArray
import org.json.JSONObject

/**
 * The lamp's commands, by the Yeelight Inter-Operation Specification.
 *
 * A command is one line of JSON ended by [LINE_END], with an id the caller picks:
 * `{"id":1,"method":"set_rgb","params":[16711680,"smooth",300]}`. The lamp replies with a line
 * with the same id, `{"id":1,"result":["ok"]}` or `{"id":1,"error":{"code":-1,"message":"..."}}`;
 * a line without an id is a notification of the lamp's new state. In music mode the lamp does not
 * reply at all.
 */
object Commands {
    const val GET_PROP = "get_prop" // Params: names of the properties to read
    const val SET_POWER = "set_power" // Params: "on" or "off", effect, duration
    const val SET_RGB = "set_rgb" // Params: 0xRRGGBB, effect, duration
    const val SET_CT = "set_ct_abx" // Params: color temperature in K, effect, duration
    const val SET_HSV = "set_hsv" // Params: hue 0..359, saturation 0..100, effect, duration
    const val SET_BRIGHT = "set_bright" // Params: brightness 1..100, effect, duration
    const val SET_MUSIC = "set_music" // Params: 1, our host, our port; or 0 to turn it off

    const val SMOOTH = "smooth" // The effect: change gradually over the duration, in ms
    const val LINE_END = "\r\n" // Ends every command and reply

    /** Creates the line to send for command [id]: [method] with [params], ended by [LINE_END]. */
    fun create(id: Int, method: String, params: List<Any>): String {
        val json = JSONObject()
            .put("id", id)
            .put("method", method)
            .put("params", JSONArray(params))

        return "$json$LINE_END"
    }
}
