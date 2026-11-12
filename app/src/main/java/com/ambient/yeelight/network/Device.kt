package com.ambient.yeelight.network

import org.json.JSONObject

/**
 * A Yeelight lamp the app knows: found by a search or added by hand.
 *
 * @param id the lamp's own id from the search answer, like "0x000000000015243f". Null for a lamp
 *   added by hand, until a search finds it
 * @param model the lamp model, like "color" or "stripe"
 * @param name the name given in the Yeelight app; may be empty
 * @param host the lamp's IPv4 address
 * @param port the lamp's control port
 * @param enabled the lamp is part of the ambient light
 * @param supportsMusicMode the lamp can work in music mode: its search answer lists set_music
 */
data class Device(
    val id: String?,
    val model: String,
    val name: String,
    val host: String,
    val port: Int = PORT,
    val enabled: Boolean = false,
    val supportsMusicMode: Boolean = false,
) {
    /** Tells lamps apart: the id if we know it, otherwise the address. */
    val key: String
        get() = id ?: host

    /** The lamp as JSON, for saving; fromJson() reads it back. */
    fun toJson(): JSONObject = JSONObject()
        .putOpt("id", id)
        .put("model", model)
        .put("name", name)
        .put("host", host)
        .put("port", port)
        .put("enabled", enabled)
        .put("supportsMusicMode", supportsMusicMode)

    companion object {
        /** The control port of every Yeelight lamp. */
        const val PORT = 55443

        /**
         * Reads a lamp saved by toJson(). Throws JSONException if a field is missing; only the id
         * and supportsMusicMode may be absent.
         */
        fun fromJson(json: JSONObject): Device = Device(
            id = if (json.has("id")) json.getString("id") else null,
            model = json.getString("model"),
            name = json.getString("name"),
            host = json.getString("host"),
            port = json.getInt("port"),
            enabled = json.getBoolean("enabled"),
            supportsMusicMode = json.optBoolean("supportsMusicMode"),
        )
    }
}
