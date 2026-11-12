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

/**
 * The main screen: the ambient light switch and the lamp list. A lamp can be found by a search,
 * added by its address, turned on or off for the light, and removed by a long click.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var requestCapturePermission: ActivityResultLauncher<Intent>
    private lateinit var requestNotificationPermission: ActivityResultLauncher<String>
    private lateinit var settings: Settings
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: DeviceListAdapter

    // Changed only on the main thread: by clicks and by the screen's coroutines in lifecycleScope
    private var devices: List<Device> = emptyList()
    private var online: Set<String> = emptySet() // Keys of the lamps that answered last time
    // The screen's network work: the lamp check, a search or adding a lamp. Only one runs at a
    // time, so a new one cancels the old one; closing the add dialog cancels it too
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        adapter = DeviceListAdapter(
            onEnabledChange = ::setEnabled,
            onRemoveRequest = ::confirmRemove,
        )
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

            AmbientService.stop(this)
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

        // The capture dialog: the light starts only if the user allows the capture
        mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
        requestCapturePermission = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val permission = result.data

            if (result.resultCode == RESULT_OK && permission != null) {
                logger.i(TAG, "Screen capture allowed")
                AmbientService.start(this, permission)
            }
            else {
                logger.i(TAG, "Screen capture not allowed")
                binding.ambientSwitch.isChecked = false
            }
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

        // Lamps may have come or gone while the screen wasn't shown
        job?.cancel()
        job = lifecycleScope.launch {
            online = probe(devices)
            adapter.update(devices, online)
        }
    }

    /** Searches for lamps, puts them into the list and saves it. The found lamps are online. */
    private fun searchDevices() {
        job?.cancel()
        job = lifecycleScope.launch {
            logger.i(TAG, "Searching devices...")
            showSearching(true)
            try {
                val found = Discovery.search()
                val foundKeys = found.map { it.key }.toSet()
                val merged = Discovery.merge(devices, found)

                // A lamp added by hand may not answer the search but still answer by TCP
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

    /** While searching, shows the spinner instead of the search and add buttons. */
    private fun showSearching(searching: Boolean) {
        binding.searchProgress.isVisible = searching
        binding.searchButton.isVisible = !searching
        binding.addButton.isVisible = !searching
    }

    /** Asks all the lamps by TCP at once and returns the keys of those that answer. */
    private suspend fun probe(devices: List<Device>): Set<String> = coroutineScope {
        val answered = devices.map { device -> async { requestDevice(device) } }.awaitAll()

        answered.filterNotNull().map { it.key }.toSet()
    }

    /** Asks the lamp about itself over a new connection; null if it doesn't answer. */
    private suspend fun requestDevice(device: Device): Device? {
        try {
            return openConnection(device).use { connection -> connection.requestDevice() }
        }
        catch (e: IOException) {
            logger.w(TAG, "${device.host} does not answer -> $e")
            return null
        }
    }

    /** Turns the ambient light on or off for this lamp and saves the list. */
    private fun setEnabled(device: Device, enabled: Boolean) {
        devices = devices.map { if (it.key == device.key) it.copy(enabled = enabled) else it }
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
     * Removes the lamp from the list and saves the list. If the light is on, the service sees
     * the change and gives the lamp its old state back.
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
        requestCapturePermission.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun showToast(message: CharSequence) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Shows the dialog that adds a lamp by its IP address. Closing the dialog cancels the check
     * of the lamp, if it still runs.
     */
    private fun showAddDeviceDialog() {
        val dialogBinding = AddDeviceDialogBinding.inflate(layoutInflater)
        // The Add button gets its listener after show(): then a click doesn't close the dialog
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
     * Adds the lamp at the address from the dialog, but only if it answers by TCP, then closes
     * the dialog. A wrong address or a lamp that doesn't answer is shown as an error in the field.
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

        val device = Device(id = null, model = "", name = "", host = host, enabled = true)

        dialogBinding.hostLayout.error = null
        showChecking(dialog, dialogBinding, checking = true)
        job?.cancel()
        job = lifecycleScope.launch {
            // If the lamp also answers the search request, we get its id, model and whether it
            // has music mode
            val request = async { Discovery.request(host) }
            val answered = requestDevice(device)
            val found = request.await()

            showChecking(dialog, dialogBinding, checking = false)
            if (answered == null) {
                dialogBinding.hostLayout.error = getString(R.string.lamp_not_answering)
                return@launch
            }

            val added = found?.copy(enabled = true) ?: answered

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