package com.ambient.yeelight.network

import org.json.JSONObject

/**
 * A Yeelight lamp the app knows about: found in the LAN or added by hand.
 *
 * @param id the lamp's id from the discovery response, like "0x000000000015243f"; null for a
 *   lamp added by hand until a search finds it
 * @param model the lamp model, like "color" or "stripe"
 * @param name the name set in the Yeelight app; may be empty
 * @param host the lamp's IPv4 address
 * @param port the lamp's control port
 * @param used the lamp takes part in the ambient light
 * @param musicMode the lamp supports music mode: `set_music` is in its discovery answer
 */
data class Device(
    val id: String?,
    val model: String,
    val name: String,
    val host: String,
    val port: Int = PORT,
    val used: Boolean = false,
    val musicMode: Boolean = false,
) {
    /** Tells lamps apart: the id if it is known, else the address. */
    val key: String
        get() = id ?: host

    /** The lamp as JSON, the way [fromJson] reads it. */
    fun toJson(): JSONObject = JSONObject()
        .putOpt("id", id)
        .put("model", model)
        .put("name", name)
        .put("host", host)
        .put("port", port)
        .put("used", used)
        .put("musicMode", musicMode)

    companion object {
        /** The control port of Yeelight lamps. */
        const val PORT = 55443

        /**
         * Reads a lamp written by [toJson]; throws JSONException if a field other than the id or
         * musicMode is missing.
         */
        fun fromJson(json: JSONObject): Device = Device(
            id = if (json.has("id")) json.getString("id") else null,
            model = json.getString("model"),
            name = json.getString("name"),
            host = json.getString("host"),
            port = json.getInt("port"),
            used = json.getBoolean("used"),
            musicMode = json.optBoolean("musicMode"),
        )
    }
}
