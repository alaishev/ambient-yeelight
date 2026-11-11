package com.ambient.yeelight.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ambient.yeelight.R
import com.ambient.yeelight.common.Settings
import com.ambient.yeelight.common.Utils
import com.ambient.yeelight.logger
import com.ambient.yeelight.network.Device
import com.ambient.yeelight.network.Discovery
import com.ambient.yeelight.network.openConnection
import com.ambient.yeelight.service.AmbientService
import com.ambient.yeelight.service.AmbientState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.IOException


class MainActivity : ComponentActivity() {
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var startMediaProjection: ActivityResultLauncher<Intent>
    private lateinit var settings: Settings

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
    private val MSG_DISCOVER_FINISH = 3
    private val MSG_LOAD_FINISH = 4
    private val MSG_DEVICE_ADDED = 5
    private val MSG_FAILED_CONNECT = 6
    private val MSG_INVALID_INPUT = 7

    private var mDevices: List<Device> = emptyList()
    private var online: Set<String> = emptySet() // Keys of the lamps that answer

    private val mHandler: Handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            super.handleMessage(msg)
            when (msg.what) {
                MSG_DISCOVER_START -> {
                    scanningSpinner.visibility = View.VISIBLE
                    mBtnSearch.visibility = View.GONE
                    mBtnAdd.visibility = View.GONE
                }

                MSG_DISCOVER_FINISH -> {
                    scanningSpinner.visibility = View.GONE
                    mBtnSearch.visibility = View.VISIBLE
                    mBtnAdd.visibility = View.VISIBLE
                    mDeviceListAdapter.update(mDevices, online)
                    showToast("Found ${msg.arg1} devices")
                }
                MSG_LOAD_FINISH -> {
                    mDeviceListAdapter.update(mDevices, online)
                    showToast("Load ${mDevices.size} devices")
                }

                MSG_DEVICE_ADDED -> {
                    mDeviceListAdapter.update(mDevices, online)
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

        settings = Settings(this)

        // Get UI controls
        mAmbientSwitch = findViewById(R.id.ambientSwitch)
        scanningSpinner = findViewById(R.id.device_scan_progress)
        mDeviceListView = findViewById(R.id.deviceList)
        mBtnSearch = findViewById(R.id.btn_search)
        mDeviceListAdapter = DeviceListAdapter(this, onUsedChange = ::setUsed)
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
                logger.i(TAG, "Searching devices...")
                mHandler.sendEmptyMessage(MSG_DISCOVER_START)

                val found = Discovery().search()
                val foundKeys = found.map { it.key }.toSet()
                val devices = Discovery.merge(mDevices, found)

                // Lamps added by hand may be out of the search's reach but still answer by TCP
                online = foundKeys + probe(devices.filter { it.key !in foundKeys })
                mDevices = devices
                settings.devices = devices
                mHandler.obtainMessage(MSG_DISCOVER_FINISH, found.size, 0).sendToTarget()
                logger.i(TAG, "Searching finished: found ${found.size}, online ${online.size}")
            }
        }

        // Register MediaProjection permission request
        mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
        startMediaProjection = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val res = result.resultCode == RESULT_OK
            logger.i(TAG, "MediaProjectionPermission=$res")

            if (res) {

                startAmbientService(result.data!!)
            }

            mAmbientSwitch.isChecked = (result.resultCode == RESULT_OK)
        }

        // The switch follows the service, also when the system stops the capture
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AmbientService.state.collect { state ->
                    mAmbientSwitch.isChecked = state == AmbientState.RUNNING
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()

        // Load and display saved devices
        job = scope.launch {
            mDevices = loadDevices()
            mHandler.sendEmptyMessage(MSG_LOAD_FINISH)
        }
    }

    override fun onStop() {
        super.onStop()

        settings.devices = mDevices
    }

    /**
     * Start Ambient service
     * @param data Data intent after receive MediaProjection permission
     */
    private fun startAmbientService(data: Intent) {
        logger.i(TAG, "Start Service")
        AmbientService.start(this, data)
        showToast("Service is started")
    }

    /**
     * Just stop Ambient service
     */
    private fun stopAmbientService() {
        logger.i(TAG, "Stop Service")
        AmbientService.stop(this)
        showToast("Service is stopped")
    }

    /** Reads the saved lamps; the ones that answer by TCP go to [online]. */
    private suspend fun loadDevices(): List<Device> {
        val devices = settings.devices

        online = probe(devices)
        return devices
    }

    /** Asks all [devices] at once by TCP; returns the keys of the ones that answer. */
    private suspend fun probe(devices: List<Device>): Set<String> = coroutineScope {
        devices
            .map { device -> async { device.key.takeIf { requestDevice(device) != null } } }
            .awaitAll()
            .filterNotNull()
            .toSet()
    }

    /** Asks [device] about itself over a connection of its own; null if it does not answer. */
    private suspend fun requestDevice(device: Device): Device? {
        try {
            return openConnection(device).use { connection -> connection.requestDevice() }
        }
        catch (e: IOException) {
            logger.w(TAG, "${device.host} does not answer -> $e")
            return null
        }
    }

    /** Turns the ambient light on or off for [device] and saves the lamp list. */
    private fun setUsed(device: Device, used: Boolean) {
        mDevices = mDevices.map { if (it.key == device.key) it.copy(used = used) else it }
        settings.devices = mDevices
        mDeviceListAdapter.update(mDevices, online)
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

            val device = Device(null, model, "", host, used = true)

            // Add the lamp only if it answers by TCP; the direct search request, if the lamp
            // answers it too, gives its id, model and music mode support
            job = scope.launch {
                val request = async { Discovery().request(host) }
                val answered = requestDevice(device)
                val found = request.await()

                if (answered == null) {
                    mHandler.sendEmptyMessage(MSG_FAILED_CONNECT)
                    return@launch
                }

                val added = found?.copy(used = true) ?: answered

                mDevices = Discovery.merge(mDevices, listOf(added))
                settings.devices = mDevices
                online = online + added.key
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
    private fun deviceInList(devices: List<Device>, host: String): Boolean {
        for (device in devices) {
            if (device.host == host) {
                return true
            }
        }

        return false
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}