package com.ambient.yeelight.ui

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.ambient.yeelight.AmbientLightApp
import com.ambient.yeelight.BuildConfig
import com.ambient.yeelight.R
import com.ambient.yeelight.common.ILogger
import com.ambient.yeelight.common.PreferenceManager
import com.ambient.yeelight.common.Utils
import com.ambient.yeelight.service.AmbientService
import com.ambient.yeelight.yeelight.YeeLightDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException


class MainActivity : ComponentActivity() {
    private lateinit var mLogger: ILogger
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var startMediaProjection: ActivityResultLauncher<Intent>
    private lateinit var mPrefs: PreferenceManager

    private var job: Job = Job()
    private var scope = CoroutineScope(Dispatchers.IO)

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private lateinit var mAmbientSwitch: Switch
    private lateinit var mBtnSearch: ImageButton
    private lateinit var scanningSpinner: ProgressBar
    private lateinit var mDeviceListView: ListView
    private lateinit var mDeviceListAdapter: DeviceListAdapter
    private lateinit var mBtnAdd: ImageButton

    private val MSG_DISCOVER_START = 1
    private val MSG_DISCOVER_STOP = 2
    private val MSG_DISCOVER_FINISH = 3
    private val MSG_LOAD_FINISH = 4
    private val MSG_DEVICE_ADDED = 5
    private val MSG_FAILED_CONNECT = 6
    private val MSG_INVALID_INPUT = 7

    private var mDevices = ArrayList<YeeLightDevice>()
    private var mSearching = false

    // Multicast udp address
    private val UDP_HOST = "239.255.255.250"
    private val UDP_PORT = 1982

    // Searching message
    private val MESSAGE = "M-SEARCH * HTTP/1.1\r\n" +
            "HOST:239.255.255.250:1982\r\n" +
            "MAN:\"ssdp:discover\"\r\n" +
            "ST:wifi_bulb\r\n"

    private val msg = MESSAGE.toByteArray()

    private val mHandler: Handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            super.handleMessage(msg)
            when (msg.what) {
                MSG_DISCOVER_START -> {
                    scanningSpinner.visibility = View.VISIBLE
                    mBtnSearch.visibility = View.GONE
                    mBtnAdd.visibility = View.GONE
                    mDeviceListAdapter.updateResults(ArrayList())
                }

                MSG_DISCOVER_STOP -> {
                    mSearching = false
                }

                MSG_DISCOVER_FINISH -> {
                    scanningSpinner.visibility = View.GONE
                    mBtnSearch.visibility = View.VISIBLE
                    mBtnAdd.visibility = View.VISIBLE
                    mDeviceListAdapter.updateResults(mDevices)
                    showToast("Found ${mDevices.size} devices")
                }
                MSG_LOAD_FINISH -> {
                    mDeviceListAdapter.updateResults(mDevices)
                    showToast("Load ${mDevices.size} devices")
                }

                MSG_DEVICE_ADDED -> {
                    mDeviceListAdapter.updateResults(mDevices)
                    showToast("Device added")
                }

                MSG_FAILED_CONNECT -> {
                    showToast("Failed to connect to the device")
                }
                MSG_INVALID_INPUT -> {
                    showToast("Invalid input data")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mLogger = AmbientLightApp.getAppLogger()
        mPrefs = PreferenceManager(this)

        // Load prefs
        val version = mPrefs.getVersion()
        if (version.isEmpty()) {
            mPrefs.setVersion(BuildConfig.VERSION_NAME)
        }

        // Get UI controls
        mAmbientSwitch = findViewById(R.id.ambientSwitch)
        scanningSpinner = findViewById(R.id.device_scan_progress)
        mDeviceListView = findViewById(R.id.deviceList)
        mBtnSearch = findViewById(R.id.btn_search)
        mDeviceListAdapter = DeviceListAdapter(this, mDevices)
        mBtnAdd = findViewById(R.id.btn_add)
        mDeviceListView.adapter = mDeviceListAdapter

        // Set click listeners
        mAmbientSwitch.setOnClickListener {
            val isChecked = (it as Switch).isChecked
            if (isChecked) {
                startMediaProjection.launch(mediaProjectionManager.createScreenCaptureIntent())
                return@setOnClickListener
            }

            stopAmbientService()
        }

        mBtnAdd.setOnClickListener {
            showAddDeviceDialog()
        }

        mBtnSearch.setOnClickListener {
            job = scope.launch {
                mLogger.logI(TAG, "Searching devices...")

                mHandler.sendEmptyMessage(MSG_DISCOVER_START)
                mHandler.sendEmptyMessageDelayed(MSG_DISCOVER_STOP, 10000)

                mDevices = searchDevices()

                mHandler.sendEmptyMessage(MSG_DISCOVER_FINISH)

                mLogger.logI(TAG, "Searching finished")
            }
        }

        mDeviceListView.setOnItemClickListener { _, _, position, _ ->
            val device = mDevices[position]
            // TODO startControlActivity(device)
        }

        // Register MediaProjection permission request
        mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
        startMediaProjection = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val res = result.resultCode == RESULT_OK
            mLogger.logI(AmbientLightApp.TAG, "MediaProjectionPermission=$res")

            if (res) {

                startAmbientService(result.data!!)
            }

            mAmbientSwitch.isChecked = (result.resultCode == RESULT_OK)
        }
    }

    override fun onStart() {
        super.onStart()

        // Update UI
        mAmbientSwitch.isChecked = serviceIsRunning(this, AmbientService::class.java.name)

        // Load and display saved devices
        job = scope.launch {
            mDevices = loadDevices()
            mHandler.sendEmptyMessage(MSG_LOAD_FINISH)
        }
    }

    override fun onStop() {
        super.onStop()

        saveDevices(mDevices)
    }

    private fun startControlActivity(host: String, port: Int) {
        //TODO val intent = Intent(this@MainActivity, ControlActivity::class.java)
        //TODO intent.putExtra("host", host)
        //TODO intent.putExtra("port", port)
        //TODO startActivity(intent)
    }

    /**
     * Start Ambient service
     * @param data Data intent after receive MediaProjection permission
     */
    private fun startAmbientService(data: Intent) {
        mLogger.logI(AmbientLightApp.TAG, "Start Service")
        val intent = Intent(this, AmbientService::class.java)
        intent.action = "start"
        intent.putExtra("pData", data)
        ContextCompat.startForegroundService(this, intent)
        showToast("Service is started")
    }

    /**
     * Just stop Ambient service
     */
    private fun stopAmbientService() {
        mLogger.logI(AmbientLightApp.TAG, "Stop Service")
        val intent = Intent(this, AmbientService::class.java)
        intent.action = "stop"
        ContextCompat.startForegroundService(this, intent)
        showToast("Service is stopped")
    }

    /**
     * Check that Ambient Service is already running
     * @param context application context
     * @param serviceName name of service
     * @return result of checking
     */
    private fun serviceIsRunning(context: Context, serviceName: String): Boolean {
        val manager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE))
            if (serviceName == service.service.className) return true
        return false
    }

    /**
     * Load already stored devices in app preferences
     * @return list saved YeeLight Devices
     */
    private fun loadDevices(): ArrayList<YeeLightDevice> {
        val devices = ArrayList<YeeLightDevice>()

        val str = mPrefs.getDevices()
        if (str.isEmpty()) {
            mLogger.logD(TAG, "No devices stored")
            return devices
        }

        val deviceArray = JSONArray(str)
        for (i in 0 until deviceArray.length()) {
            val deviceObject = deviceArray.getJSONObject(i)

            val id = deviceObject.optString("id")
            val model = deviceObject.optString("model")
            val name = deviceObject.optString("name")
            val location = deviceObject.optString("location")
            val used = deviceObject.optBoolean("used")

            val device = YeeLightDevice(id, model, name, location, used)
            device.initialize() // We want to check if loaded device is still online and get it state
            devices.add(device)
        }

        return devices
    }

    /**
     * Save devices to app preferences
     * @param devices List of Devices to save
     */
    private fun saveDevices(devices: ArrayList<YeeLightDevice>) {
        val jsonArray = JSONArray()
        for (deviceInfo in devices) {
            jsonArray.put(deviceInfo.toJson())
        }
        mPrefs.setDevices(jsonArray.toString())
    }

    /**
     *  Search for available devices in LAN
     *  @return list with information about founded devices
     */
    private fun searchDevices(): ArrayList<YeeLightDevice> {
        val devices = ArrayList<YeeLightDevice>()
        val socket = DatagramSocket()

        try {
            val packet = DatagramPacket(msg, msg.size, InetAddress.getByName(UDP_HOST), UDP_PORT)
            socket.setSoTimeout(300)
            socket.send(packet)
        }
        catch (e: Exception) {
            e.printStackTrace()
            mLogger.logE(TAG, "Sent M-SEARCH packet failed: ${e.message}")
            return devices
        }

        mSearching = true
        while (mSearching) {
            val buf = ByteArray(1024)
            val packet = DatagramPacket(buf, buf.size)

            try {
                socket.receive(packet)
            }
            catch (e: SocketTimeoutException) {
                continue
            }
            catch (e: Exception) {
                e.printStackTrace()
                mLogger.logE(TAG, "Receive packet failed: ${e.message}")
                return devices
            }

            // Bulb respond as HTTP, so convert it to String
            val rsp = String(packet.data, 0, packet.length, Charsets.US_ASCII)
            if (!rsp.contains("yeelight")) {
                continue
            }

            try {
                val device = createYeeLightDevice(rsp)

                // Prevent duplication devices
                if (!deviceInList(devices, device.host)) {
                    devices.add(device)
                    mLogger.logD(TAG, "Found device:\n$rsp")
                }
            }
            catch (e: Exception) {
                e.printStackTrace()
                mLogger.logW(TAG, "Device creation error: ${e.message}")
            }
        }

        return devices
    }

    /**
     * Parse device response and create device object
     * @return device object
     */
    private fun createYeeLightDevice(rsp: String): YeeLightDevice {
        // Split to Strings
        val splitRsp =
            rsp.split("\r\n".toRegex()).dropLastWhile { it.isEmpty() }

        // Create Dictionary of field-value pairs
        val bulbInfo = HashMap<String, String>()
        for (str in splitRsp) {
            val index = str.indexOf(":")

            if (index != -1) {
                val title = str.substring(0, index)
                val value = str.substring(index + 2)
                bulbInfo[title] = value
            }
        }

        val id = get(bulbInfo, "id")
        val model = get(bulbInfo, "model")
        val name = get(bulbInfo, "name")
        val location = get(bulbInfo, "Location")
        val used = false // TODO get current state
        val online = true

        return YeeLightDevice(id, model, name, location, used, online)
    }

    /**
     * Show notification toast
     * @param message to show
     */
    private fun showToast(message: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(this, message, duration).show()
    }

    /**
     * Create and show dialog with adding device manually
     */
    private fun showAddDeviceDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.add_device_dialog, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setTitle("Adding Device")
            .setPositiveButton("Add", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        positiveButton.setOnClickListener {
            val textModel = dialogView.findViewById<View>(R.id.device_model) as TextView
            val textLocation = dialogView.findViewById<View>(R.id.device_location) as TextView

            val model = textModel.text.toString()
            val host = textLocation.text.toString()

            if (model.isEmpty() || !Utils.isIpV4(host)) {
                showToast("Model is empty or IP is Invalid", Toast.LENGTH_LONG)
                return@setOnClickListener
            }

            if (deviceInList(mDevices, host)) {
                showToast("Device is already in list", Toast.LENGTH_LONG)
                return@setOnClickListener
            }

            val id = "0x0000000000000000"
            val name = ""
            val location = "yeelight://$host:55443"

            // Try to initialize device and add it to list
            job = scope.launch {
                val device = YeeLightDevice(id, model, name, location)
                val res = device.initialize()
                if (!res) {
                    mHandler.sendEmptyMessage(MSG_FAILED_CONNECT)
                    return@launch
                }

                device.used = true
                mDevices.add(device)
                mHandler.sendEmptyMessage(MSG_DEVICE_ADDED)
                dialog.dismiss()
            }
        }
    }

    /**
     * Check that device with same host is in found list
     * @param devices list of devices
     * @param host host to search
     * @return true if device already found, false otherwise
     */
    private fun deviceInList(devices: ArrayList<YeeLightDevice>, host: String): Boolean {
        for (device in devices) {
            if (device.host == host) {
                return true
            }
        }

        return false
    }

    private fun get(bulbInfo: HashMap<String, String>, key: String): String {
        return bulbInfo[key] ?: throw NoSuchElementException(key)
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}