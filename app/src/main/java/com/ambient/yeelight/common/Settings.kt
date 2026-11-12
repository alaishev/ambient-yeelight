package com.ambient.yeelight.common

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.ambient.yeelight.logger
import com.ambient.yeelight.network.Device
import org.json.JSONArray
import org.json.JSONException

/** What the app keeps between launches: the lamp list and the ambient light settings. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // SharedPreferences keeps only a weak reference to a listener. We hold it here, or the
    // garbage collector could remove it and the calls would silently stop
    private var devicesListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /**
     * The saved lamps, in the order they're shown. Empty if nothing is saved yet or the saved
     * list can't be read.
     */
    var devices: List<Device>
        get() = readDevices(prefs.getString(KEY_DEVICES, null))
        set(value) {
            val json = JSONArray(value.map { it.toJson() })

            prefs.edit { putString(KEY_DEVICES, json.toString()) }
        }

    /** How often a frame is captured and a color is sent to the lamps, ms. */
    var colorIntervalMs: Long
        get() = prefs.getLong(KEY_COLOR_INTERVAL, DEFAULT_COLOR_INTERVAL_MS)
            .coerceIn(COLOR_INTERVAL_RANGE_MS)
        set(value) {
            prefs.edit { putLong(KEY_COLOR_INTERVAL, value) }
        }

    /** How much a color must change (CIE76 ΔE) before it's sent to the lamps. */
    var minDeltaE: Float
        get() = prefs.getFloat(KEY_MIN_DELTA_E, DEFAULT_MIN_DELTA_E).coerceIn(MIN_DELTA_E_RANGE)
        set(value) {
            prefs.edit { putFloat(KEY_MIN_DELTA_E, value) }
        }

    /** How long a lamp takes to fade to a new color, ms. */
    var transitionMs: Int
        get() = prefs.getInt(KEY_TRANSITION, DEFAULT_TRANSITION_MS).coerceIn(TRANSITION_RANGE_MS)
        set(value) {
            prefs.edit { putInt(KEY_TRANSITION, value) }
        }

    /** How bright the lamps are while the ambient light is on, percent. */
    var brightness: Int
        get() = prefs.getInt(KEY_BRIGHTNESS, DEFAULT_BRIGHTNESS).coerceIn(BRIGHTNESS_RANGE)
        set(value) {
            prefs.edit { putInt(KEY_BRIGHTNESS, value) }
        }

    /** Sets the ambient light settings back to the defaults. The lamp list stays as it is. */
    fun resetParameters() {
        prefs.edit {
            remove(KEY_COLOR_INTERVAL)
            remove(KEY_MIN_DELTA_E)
            remove(KEY_TRANSITION)
            remove(KEY_BRIGHTNESS)
        }
    }

    /**
     * Calls onChange on the main thread every time the lamp list is saved, by this or any other
     * Settings object, until stopListening(). Calling it again replaces the old listener.
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

    /** Stops the calls started by listenToDevices(). */
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
        // Up to 3 colors a second: faster makes no visible difference. On a usual connection a
        // lamp takes only 144 commands a minute, one per 417 ms (Yeelight spec), so in a long
        // busy scene it may skip a few colors. Music mode has no such limit
        const val DEFAULT_COLOR_INTERVAL_MS = 300L
        const val DEFAULT_MIN_DELTA_E = 2.5f // About the smallest change the eye can see
        const val DEFAULT_TRANSITION_MS = 300
        const val DEFAULT_BRIGHTNESS = 70 // Percent: bright enough, but doesn't dazzle
        val COLOR_INTERVAL_RANGE_MS = 200L..1_000L
        val MIN_DELTA_E_RANGE = 1f..5f
        val TRANSITION_RANGE_MS = 50..500 // A lamp needs at least 30 ms
        val BRIGHTNESS_RANGE = 5..100 // Percent. A lamp takes 1..100, but below 5 it's hardly seen

        private const val TAG = "Settings"
        private const val FILE = "settings"
        private const val KEY_DEVICES = "devices"
        private const val KEY_COLOR_INTERVAL = "color_interval_ms"
        private const val KEY_MIN_DELTA_E = "min_delta_e"
        private const val KEY_TRANSITION = "transition_ms"
        private const val KEY_BRIGHTNESS = "brightness"
    }
}
