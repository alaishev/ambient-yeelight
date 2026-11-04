package com.ambient.yeelight.common

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

class PreferenceManager(context: Context) {

    // preference
    private val APP_PREFERENCES = "settings"
    private val VERSION = "version"
    private val DEVICES = "devices"

    private val sharedPreference: SharedPreferences =
        context.getSharedPreferences(APP_PREFERENCES, Context.MODE_PRIVATE)

    fun getVersion(): String {
        return sharedPreference.getString(VERSION, "") ?: ""
    }

    fun getDevices(): String {
        return sharedPreference.getString(DEVICES, "") ?: ""
    }

    fun setVersion(value: String) {
        Log.d("Prefs", "save = $value")
        sharedPreference.edit().apply {
            putString(VERSION, value)
            apply()
        }
    }

    fun setDevices(value: String) {
        Log.d("Prefs", "save = $value")
        sharedPreference.edit().apply {
            putString(DEVICES, value)
            apply()
        }
    }

}
