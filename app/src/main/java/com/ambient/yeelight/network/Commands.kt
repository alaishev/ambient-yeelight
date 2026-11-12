package com.ambient.yeelight.network

import org.json.JSONArray
import org.json.JSONObject

/**
 * The lamp commands we use, from the Yeelight Inter-Operation Specification.
 *
 * A command is one line of JSON with an id we pick:
 * `{"id":1,"method":"set_rgb","params":[16711680,"smooth",300]}`. The lamp answers with a line
 * that has the same id: `{"id":1,"result":["ok"]}`, or `{"id":1,"error":{...}}` if it refuses.
 * A line without an id is the lamp telling us its new state. In music mode the lamp doesn't
 * answer at all.
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
}

/** Makes the line to send: the command with this id, method and params, ended by LINE_END. */
fun createCommand(id: Int, method: String, params: List<Any>): String {
    val json = JSONObject()
        .put("id", id)
        .put("method", method)
        .put("params", JSONArray(params))

    return "$json${Commands.LINE_END}"
}
