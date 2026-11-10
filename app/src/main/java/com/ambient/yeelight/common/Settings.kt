package com.ambient.yeelight.common

import android.content.Context
import androidx.core.content.edit
import com.ambient.yeelight.logger
import com.ambient.yeelight.network.Device
import org.json.JSONArray
import org.json.JSONException

/** The app's stored settings: the lamp list, later also the ambient light parameters. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * The saved lamps, in the order the user sees them. Empty if nothing is saved yet or the
     * saved list cannot be read.
     */
    var devices: List<Device>
        get() = readDevices(prefs.getString(KEY_DEVICES, null))
        set(value) {
            val json = JSONArray(value.map { it.toJson() })

            prefs.edit { putString(KEY_DEVICES, json.toString()) }
        }

    private fun readDevices(text: String?): List<Device> {
        if (text == null) return emptyList()

        return try {
            val json = JSONArray(text)

            List(json.length()) { Device.fromJson(json.getJSONObject(it)) }
        }
        catch (e: JSONException) {
            logger.w(TAG, "Cannot read the saved lamps", e)
            emptyList()
        }
    }

    private companion object {
        const val TAG = "Settings"
        const val FILE = "settings"
        const val KEY_DEVICES = "devices"
    }
}
