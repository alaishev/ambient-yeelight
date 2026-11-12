package com.ambient.yeelight.common

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.ambient.yeelight.logger
import com.ambient.yeelight.network.Device
import org.json.JSONArray
import org.json.JSONException

/** The app's stored settings: the lamp list and the ambient light parameters. */
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

    /** How often a frame is captured and a color goes to the lamps, ms. */
    var colorIntervalMs: Long
        get() = prefs.getLong(KEY_COLOR_INTERVAL, DEFAULT_COLOR_INTERVAL_MS)
            .coerceIn(COLOR_INTERVAL_RANGE_MS)
        set(value) {
            prefs.edit { putLong(KEY_COLOR_INTERVAL, value) }
        }

    /** How much a color must change, CIE76 ΔE, before the lamps get it. */
    var minDeltaE: Float
        get() = prefs.getFloat(KEY_MIN_DELTA_E, DEFAULT_MIN_DELTA_E).coerceIn(MIN_DELTA_E_RANGE)
        set(value) {
            prefs.edit { putFloat(KEY_MIN_DELTA_E, value) }
        }

    /** How long a lamp fades to a new color, ms. */
    var transitionMs: Int
        get() = prefs.getInt(KEY_TRANSITION, DEFAULT_TRANSITION_MS).coerceIn(TRANSITION_RANGE_MS)
        set(value) {
            prefs.edit { putInt(KEY_TRANSITION, value) }
        }

    /** How bright the lamps shine in the ambient light, percent. */
    var brightness: Int
        get() = prefs.getInt(KEY_BRIGHTNESS, DEFAULT_BRIGHTNESS).coerceIn(BRIGHTNESS_RANGE)
        set(value) {
            prefs.edit { putInt(KEY_BRIGHTNESS, value) }
        }

    /** Brings the ambient light parameters back to their defaults; the lamp list stays. */
    fun resetParameters() {
        prefs.edit {
            remove(KEY_COLOR_INTERVAL)
            remove(KEY_MIN_DELTA_E)
            remove(KEY_TRANSITION)
            remove(KEY_BRIGHTNESS)
        }
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

    companion object {
        // Up to 3 colors a second: more makes no visible difference. On a usual connection a
        // lamp takes 144 commands per minute (Yeelight Inter-Operation Specification), one in
        // 417 ms, so a long run of color changes may lose a few there; music mode has no limit
        const val DEFAULT_COLOR_INTERVAL_MS = 300L
        const val DEFAULT_MIN_DELTA_E = 2.5f // About the smallest difference the eye sees
        const val DEFAULT_TRANSITION_MS = 300
        const val DEFAULT_BRIGHTNESS = 70 // Percent: bright enough, does not dazzle
        val COLOR_INTERVAL_RANGE_MS = 200L..1_000L
        val MIN_DELTA_E_RANGE = 1f..5f
        val TRANSITION_RANGE_MS = 50..500 // A lamp takes at least 30 ms
        val BRIGHTNESS_RANGE = 5..100 // Percent; a lamp takes 1..100, below 5 it hardly shows

        private const val TAG = "Settings"
        private const val FILE = "settings"
        private const val KEY_DEVICES = "devices"
        private const val KEY_COLOR_INTERVAL = "color_interval_ms"
        private const val KEY_MIN_DELTA_E = "min_delta_e"
        private const val KEY_TRANSITION = "transition_ms"
        private const val KEY_BRIGHTNESS = "brightness"
    }
}
