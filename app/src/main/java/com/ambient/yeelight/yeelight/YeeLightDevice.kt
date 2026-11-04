package com.ambient.yeelight.yeelight

import com.ambient.yeelight.AmbientLightApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * @author alaishev
 *
 * This class contains information about founded Yeelight Device in LAN
 * after parsing response from device
 *
 * Here is example of response:
 *   -------------------------------------------------------------
 *   HTTP/1.1 200 OK
 *   Cache-Control: max-age=3600
 *   Date:
 *   Ext:
 *   Location: yeelight://192.168.1.239:55443
 *   Server: POSIX UPnP/1.0 YGLC/1
 *   id: 0x000000000015243f
 *   model: color
 *   fw_ver: 18
 *   support: get_prop set_default set_power toggle set_bright start_cf stop_cf set_scene
 *   cron_add cron_get cron_del set_ct_abx set_rgb
 *   power: on
 *   bright: 100
 *   color_mode: 2
 *   ct: 4000
 *   rgb: 16711680
 *   hue: 100
 *   sat: 35
 *   name: my_bulb
 *   -------------------------------------------------------------
 */
class YeeLightDevice(val id: String,
                     val model: String,
                     var name: String,
                     private val location: String,
                     var used: Boolean = false,
                     var online:Boolean = false) {

    private val mLogger = AmbientLightApp.getAppLogger()

    // Device information
    val host: String
    private val port: Int

    // Device connection
    private lateinit var mSocket: Socket
    private lateinit var mWriter: OutputStream
    private lateinit var mReader: InputStream

    private var mCmdId = 10

    // Device state
    private lateinit var mState: DeviceState

    init {
        val locationOffset = "yeelight://".length
        val index = location.indexOf(":", locationOffset)
        host = location.substring(locationOffset, index)
        port = location.substring(index + 1).toInt()
    }

    fun state(): DeviceState {
        return mState
    }

    /**
     * Initialize device state and update it`s state
     * @return status of initializing
     */
    fun initialize(): Boolean {
        val cmd = CMD_GET_PROP.replace("%id", (++mCmdId).toString())
        if (!writeCmd(cmd)) {
            return false
        }

        val rsp = readRsp().ifEmpty { return false }
        val resultParams = getParams(rsp)

        name = resultParams.optString(6, "YeeLight")
        online = true
        // TODO mState = DeviceState(resultParams)

        return true
    }

    fun setColor(color: Int) {
        val cmd = CMD_COLOR.replace("%id", (++mCmdId).toString())
            .replace("%value", color.toString())
            .replace("%smooth", "300")
        writeCmd(cmd)
    }

    private fun writeCmd(cmd: String): Boolean {
        try {
            // Open connection
            mSocket = Socket(host, port)
            mSocket.soTimeout = 300
            mWriter = mSocket.getOutputStream()
            mReader = mSocket.getInputStream()

            // Write command
            mWriter.write(cmd.toByteArray())

            mLogger.logD(TAG, "Host = ${host}\nCommand sent: $cmd")
            return  true
        }
        catch (e: Exception) {
            e.printStackTrace()
            mLogger.logE(TAG, "Host = ${host}\nSend command error: ${e.message}")
            return false
        }
    }

    private fun readRsp(): String {
        try {
            val buf = ByteArray(512)
            val len = mReader.read(buf)
            val rsp = String(buf, 0, len, Charsets.US_ASCII)
            mLogger.logD(TAG, "Host = ${host}\nReceived response: $rsp")
            return rsp
        }
        catch (e: Exception) {
            e.printStackTrace()
            mLogger.logE(TAG, "Host = ${host}\nReceive response error: ${e.message}")
            return ""
        }
    }

    private fun getParams(rsp: String): JSONArray {

        val resultJson = JSONObject(rsp)

        return try {
            resultJson.getJSONArray("result")
        } catch (e: Exception) {
            resultJson.getJSONArray("params")
        }
    }

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("model", model)
        json.put("name", name)
        json.put("location", location)
        json.put("used", used)

        return json
    }

    companion object {
        private const val TAG = "YeeLightDevice"

        private val CMD_TOGGLE = "{\"id\":%id,\"method\":\"toggle\",\"params\":[]}\r\n"
        private val CMD_GET_PROP = "{\"id\":%id,\"method\":\"get_prop\",\"params\":[\"power\",\"bright\",\"ct\",\"rgb\",\"hue\",\"sat\",\"name\"]}\r\n"
        private val CMD_ON = "{\"id\":%id,\"method\":\"set_power\",\"params\":[\"on\",\"smooth\",%smooth]}\r\n"
        private val CMD_OFF = "{\"id\":%id,\"method\":\"set_power\",\"params\":[\"off\",\"smooth\",%smooth]}\r\n"
        private val CMD_CT = "{\"id\":%id,\"method\":\"set_ct_abx\",\"params\":[%value, \"smooth\", %smooth]}\r\n"
        private val CMD_HSV = "{\"id\":%id,\"method\":\"set_hsv\",\"params\":[%hue, %saturation, \"smooth\", %smooth]}\r\n"
        private val CMD_COLOR = "{\"id\":%id,\"method\":\"set_rgb\",\"params\":[%value, \"smooth\", %smooth]}\r\n"
        private val CMD_BRIGHTNESS = "{\"id\":%id,\"method\":\"set_bright\",\"params\":[%value, \"smooth\", %smooth]}\r\n"
        private val CMD_BRIGHTNESS_SCENE = "{\"id\":%id,\"method\":\"set_bright\",\"params\":[%value, \"smooth\", %smooth]}\r\n"
        private val CMD_COLOR_SCENE = "{\"id\":%id,\"method\":\"set_scene\",\"params\":[\"cf\",1,0,\"100,1,%color,1\"]}\r\n"
    }
}