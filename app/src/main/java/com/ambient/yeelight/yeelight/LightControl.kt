package com.ambient.yeelight.yeelight

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.widget.Toast
import java.io.BufferedOutputStream
import java.net.Socket

class LightControl(private val context: Context, private val mBulbIP: String, private val mBulbPort: Int) {
    private val TAG = "LightControl"
    private var mCmdId = 1
    private var mSocket: Socket? = null
    private var mBos: BufferedOutputStream? = null
    private val mHandler: Handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            super.handleMessage(msg)
            when (msg.what) {
                MSG_CONNECT_FAILURE -> Toast.makeText(context, "Wifi Not Connected!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun turnOn() {
        connect(ACTION_TURN_ON, 0)
    }

    fun turnOff() {
        connect(ACTION_TURN_OFF, 0)
    }

    fun toggle() {
        connect(ACTION_TOGGLE, 0)
    }

    fun bright() {
        connect(ACTION_BRIGHT, 0)
    }

    fun medium() {
        connect(ACTION_MEDIUM, 0)
    }

    fun dim() {
        connect(ACTION_DIM, 0)
    }

    fun rgb(color: Int) {
        connect(ACTION_RGB, color)
    }

    private fun connect(action: Int, color: Int) {
        Thread(Runnable {
            try {
                //Log.d(TAG, "run: Connecting")
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                if (connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI)!!.state == NetworkInfo.State.CONNECTED) {
                    mSocket = Socket(mBulbIP, mBulbPort)
                    mSocket!!.keepAlive = true
                    mBos = BufferedOutputStream(mSocket!!.getOutputStream(), 512)
                    //Log.d(TAG, "run: Connection Success")
                    handleAction(action, color)
                }
                else {
                    mHandler.sendEmptyMessage(MSG_CONNECT_FAILURE)
                   // Log.d(TAG, "run: Connection Failed, Wifi Not Connected")
                }
            }
            catch (e: Exception) {
                e.printStackTrace()
                Log.d(TAG, "run: Connection Failed")
            }
        }).start()
    }

    private fun handleAction(action: Int, color: Int) {
        when (action) {
            ACTION_TURN_OFF -> {
                Log.d(TAG, "handleAction: Sending Turn Off Command")
                sendCommand(parseCmd(CMD_OFF, 0))
            }

            ACTION_TURN_ON  -> {
                Log.d(TAG, "handleAction: Sending Turn On Command")
                sendCommand(parseCmd(CMD_ON, 0))
            }

            ACTION_TOGGLE   -> {
                Log.d(TAG, "handleAction: Sending Toggle Command")
                sendCommand(parseCmd(CMD_TOGGLE, 0))
            }

            ACTION_BRIGHT   -> {
                Log.d(TAG, "handleAction: Sending Bright Command")
                sendCommand(parseCmd(CMD_BRIGHT, 0))
            }

            ACTION_MEDIUM   -> {
                Log.d(TAG, "handleAction: Sending Medium Command")
                sendCommand(parseCmd(CMD_MEDIUM, 0))
            }

            ACTION_DIM      -> {
                Log.d(TAG, "handleAction: Sending Dim Command")
                sendCommand(parseCmd(CMD_DIM, 0))
            }

            ACTION_RGB      -> {
                //Log.d(TAG, "handleAction: Sending RGB Command")
                sendCommand(parseCmd(CMD_RGB, color))
            }
        }
    }

    private fun sendCommand(cmd: String) {
        if (mBos != null && mSocket!!.isConnected) {
            try {
                mBos!!.write(cmd.toByteArray())
                mBos!!.flush()
                //Log.d(TAG, "sendCommand: Command Sent")
            }
            catch (e: Exception) {
                e.printStackTrace()
            }
            closeConn()
        }
        else {
            Log.e(TAG, "mBos = null or mSocket is closed")
        }
    }

    private fun parseCmd(cmd: String, color: Int): String {

        var tmp = cmd
        if (cmd == CMD_RGB) {
            tmp = cmd.replace("%value", color.toString())
        }

        return tmp.replace("%id", (++mCmdId).toString())
    }

    private fun closeConn() {
        try {
            if (mSocket != null) {
                mSocket!!.close()
                //Log.d(TAG, "closeConn: Connection Closed")
            }
        }
        catch (e: Exception) {
            Log.d(TAG, "closeConn: Exception")
        }
    }

    companion object {
        private const val ACTION_TURN_OFF = 0
        private const val ACTION_TURN_ON = 1
        private const val ACTION_TOGGLE = 2
        private const val ACTION_BRIGHT = 3
        private const val ACTION_DIM = 4
        private const val ACTION_MEDIUM = 5
        private const val ACTION_RGB = 6
        private const val MSG_CONNECT_SUCCESS = 700
        private const val MSG_CONNECT_FAILURE = 701
        private const val CMD_ON = "{\"id\":%id,\"method\":\"set_power\",\"params\":[\"on\",\"smooth\",500]}\r\n"
        private const val CMD_OFF = "{\"id\":%id,\"method\":\"set_power\",\"params\":[\"off\",\"smooth\",500]}\r\n"
        private const val CMD_TOGGLE = "{\"id\":%id,\"method\":\"toggle\",\"params\":[]}\r\n"
        private const val CMD_BRIGHT = "{\"id\":%id,\"method\":\"set_bright\",\"params\":[100, \"smooth\", 500]}\r\n"
        private const val CMD_MEDIUM = "{\"id\":%id,\"method\":\"set_bright\",\"params\":[50, \"smooth\", 500]}\r\n"
        private const val CMD_DIM = "{\"id\":%id,\"method\":\"set_bright\",\"params\":[1, \"smooth\", 500]}\r\n"
        private const val CMD_RGB = "{\"id\":%id,\"method\":\"set_rgb\",\"params\":[%value, \"smooth\", 300]}\r\n"
    }
}