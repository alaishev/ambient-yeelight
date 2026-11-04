package com.ambient.yeelight.yeelight

import org.json.JSONArray
import java.io.Serializable

class DeviceState(bulbInfo: HashMap<String, String>) : Serializable {

    // Device state
    private var mPower: Boolean
    private var mBright: Int
    private var mCt: Int
    private var mRgb: Int
    private var mHue: Int
    private var mSat: Int

    private var mColorMode: Int

    private val supportedMethods: Array<String>

    init {
        // Initial State
        mPower = get(bulbInfo, "power") == "on"
        mBright = get(bulbInfo, "bright").toInt()
        mCt = get(bulbInfo, "ct").toInt()
        mRgb = get(bulbInfo, "ct").toInt()
        mHue = get(bulbInfo, "hue").toInt()
        mSat = get(bulbInfo, "sat").toInt()

        mColorMode = get(bulbInfo, "color_mode").toInt()

        // Supported Methods
        val support = get(bulbInfo, "support")
        supportedMethods =
            support.split(" ".toRegex()).dropLastWhile { it.isEmpty() }
                .toTypedArray()
    }

    fun updateState(state: JSONArray) {
        mPower = state.optString(0).equals("on")
        mBright = state.optInt(1)
        mCt = state.optInt(2)
        mRgb = state.optInt(3)
        mHue = state.optInt(4)
        mSat = state.optInt(5)
    }

    private fun get(bulbInfo: HashMap<String, String>, key: String): String {
        return bulbInfo[key] ?: throw NoSuchElementException(key)
    }
}