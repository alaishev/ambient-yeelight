package com.ambient.yeelight.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ambient.yeelight.R
import com.ambient.yeelight.common.Settings
import com.ambient.yeelight.common.Utils
import com.ambient.yeelight.databinding.AddDeviceDialogBinding
import com.ambient.yeelight.databinding.ActivityMainBinding
import com.ambient.yeelight.logger
import com.ambient.yeelight.network.Device
import com.ambient.yeelight.network.Discovery
import com.ambient.yeelight.network.openConnection
import com.ambient.yeelight.service.AmbientService
import com.ambient.yeelight.service.AmbientState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.IOException


class MainActivity : AppCompatActivity() {
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var startMediaProjection: ActivityResultLauncher<Intent>
    private lateinit var requestNotificationPermission: ActivityResultLauncher<String>
    private lateinit var settings: Settings
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: DeviceListAdapter

    // Changed only on the main thread, by the screen's coroutines in lifecycleScope
    private var devices: List<Device> = emptyList()
    private var online: Set<String> = emptySet() // Keys of the lamps that answer
    private var job: Job? = null // The screen's network work; a new one cancels the old one

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        adapter = DeviceListAdapter(onEnabledChange = ::setUsed, onRemoveRequest = ::confirmRemove)
        binding.lampList.adapter = adapter

        binding.ambientSwitch.setOnClickListener {
            if (binding.ambientSwitch.isChecked) {
                if (needsNotificationPermission()) {
                    requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else {
                    requestCapture()
                }
                return@setOnClickListener
            }

            stopAmbientService()
        }

        binding.addButton.setOnClickListener {
            showAddDeviceDialog()
        }

        binding.searchButton.setOnClickListener {
            searchDevices()
        }

        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // The light works without the notification too: the capture is asked for either way
        requestNotificationPermission = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            logger.i(TAG, "Notifications allowed: $granted")
            requestCapture()
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

            binding.ambientSwitch.isChecked = (result.resultCode == RESULT_OK)
        }

        // The switch follows the service, also when the system stops the capture
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AmbientService.state.collect { state ->
                    binding.ambientSwitch.isChecked = state == AmbientState.RUNNING
                }
            }
        }

        devices = settings.devices
        adapter.update(devices, online)
    }

    override fun onStart() {
        super.onStart()

        // The lamps may have come or gone while the screen was not shown
        job?.cancel()
        job = lifecycleScope.launch {
            online = probe(devices)
            adapter.update(devices, online)
        }
    }

    /**
     * Start Ambient service
     * @param data Data intent after receive MediaProjection permission
     */
    private fun startAmbientService(data: Intent) {
        logger.i(TAG, "Start Service")
        AmbientService.start(this, data)
    }

    /**
     * Just stop Ambient service
     */
    private fun stopAmbientService() {
        logger.i(TAG, "Stop Service")
        AmbientService.stop(this)
    }

    /** Searches the lamps, merges them into the list and saves it; the found ones are online. */
    private fun searchDevices() {
        job?.cancel()
        job = lifecycleScope.launch {
            logger.i(TAG, "Searching devices...")
            showSearching(true)
            try {
                val found = Discovery().search()
                val foundKeys = found.map { it.key }.toSet()
                val merged = Discovery.merge(devices, found)

                // Lamps added by hand may be out of the search's reach but still answer by TCP
                online = foundKeys + probe(merged.filter { it.key !in foundKeys })
                devices = merged
                settings.devices = merged
                adapter.update(devices, online)
                showToast(
                    resources.getQuantityString(R.plurals.lamps_found, found.size, found.size)
                )
                logger.i(TAG, "Searching finished: found ${found.size}, online ${online.size}")
            }
            finally {
                showSearching(false) // Also if the search is cancelled
            }
        }
    }

    /** Shows the spinner instead of the search and add buttons while [searching]. */
    private fun showSearching(searching: Boolean) {
        binding.searchProgress.isVisible = searching
        binding.searchButton.isVisible = !searching
        binding.addButton.isVisible = !searching
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
        devices = devices.map { if (it.key == device.key) it.copy(used = used) else it }
        settings.devices = devices
        adapter.update(devices, online)
    }

    /** Asks whether to remove the lamp from the list. */
    private fun confirmRemove(device: Device) {
        val name = device.name.ifEmpty { device.host }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.remove_lamp_title)
            .setMessage(getString(R.string.remove_lamp_message, name))
            .setPositiveButton(R.string.remove) { _, _ -> removeDevice(device) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Removes the lamp from the list and saves the list; the service stops the light on the lamp,
     * if it runs.
     */
    private fun removeDevice(device: Device) {
        devices = devices.filter { it.key != device.key }
        online = online - device.key
        settings.devices = devices
        adapter.update(devices, online)
    }

    /** Android 13 and later need the permission to show the service's notification. */
    private fun needsNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false

        val permission = Manifest.permission.POST_NOTIFICATIONS

        return ContextCompat.checkSelfPermission(this, permission) !=
            PackageManager.PERMISSION_GRANTED
    }

    /** Shows the system dialog that allows the screen capture. */
    private fun requestCapture() {
        startMediaProjection.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    /**
     * Show notification toast
     * @param message to show
     */
    private fun showToast(message: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(this, message, duration).show()
    }

    /**
     * Shows the dialog that adds a lamp by its IP address. Closing the dialog cancels the check
     * of the lamp, if it still runs.
     */
    private fun showAddDeviceDialog() {
        val dialogBinding = AddDeviceDialogBinding.inflate(layoutInflater)
        // The Add button gets its listener below, so a click does not close the dialog
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_lamp_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.add, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnDismissListener {
            job?.cancel()
        }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            addDevice(dialog, dialogBinding)
        }
    }

    /**
     * Adds the lamp at the address in the dialog, only if it answers by TCP, then closes the
     * dialog. A wrong address or a lamp that does not answer is shown in the field.
     */
    private fun addDevice(dialog: AlertDialog, dialogBinding: AddDeviceDialogBinding) {
        val host = dialogBinding.hostInput.text.toString().trim()

        if (!Utils.isIpV4(host)) {
            dialogBinding.hostLayout.error = getString(R.string.invalid_address)
            return
        }
        if (devices.any { it.host == host }) {
            dialogBinding.hostLayout.error = getString(R.string.lamp_in_list)
            return
        }

        val device = Device(id = null, model = "", name = "", host = host, used = true)

        dialogBinding.hostLayout.error = null
        showChecking(dialog, dialogBinding, checking = true)
        job?.cancel()
        job = lifecycleScope.launch {
            // The direct search request, if the lamp answers it too, gives its id, model and
            // music mode support
            val request = async { Discovery().request(host) }
            val answered = requestDevice(device)
            val found = request.await()

            showChecking(dialog, dialogBinding, checking = false)
            if (answered == null) {
                dialogBinding.hostLayout.error = getString(R.string.lamp_not_answering)
                return@launch
            }

            val added = found?.copy(used = true) ?: answered

            devices = Discovery.merge(devices, listOf(added))
            settings.devices = devices
            online = online + added.key
            adapter.update(devices, online)
            showToast(getString(R.string.lamp_added))
            dialog.dismiss()
        }
    }

    /** While the lamp is checked, shows the progress and locks the field and the Add button. */
    private fun showChecking(
        dialog: AlertDialog,
        dialogBinding: AddDeviceDialogBinding,
        checking: Boolean,
    ) {
        dialogBinding.checkProgress.visibility = if (checking) View.VISIBLE else View.INVISIBLE
        dialogBinding.hostInput.isEnabled = !checking
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = !checking
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}