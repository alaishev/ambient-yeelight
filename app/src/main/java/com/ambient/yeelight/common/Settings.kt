package com.ambient.yeelight.common

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.ambient.yeelight.logger
import com.ambient.yeelight.network.Device
import org.json.JSONArray
import org.json.JSONException

/** The app's stored settings: the lamp list, later also the ambient light parameters. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // Kept here: SharedPreferences holds its listeners by weak references only
    private var devicesListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

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

    /**
     * Calls [onChange] on the main thread each time a changed lamp list is saved, by this or
     * another [Settings], until [stopListening]. A new call replaces the listener.
     */
    fun listenToDevices(onChange: () -> Unit) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_DEVICES) {
                onChange()
            }
        }

        stopListening()
        devicesListener = listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    /** Stops the calls set by [listenToDevices]. */
    fun stopListening() {
        val listener = devicesListener ?: return

        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        devicesListener = null
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
