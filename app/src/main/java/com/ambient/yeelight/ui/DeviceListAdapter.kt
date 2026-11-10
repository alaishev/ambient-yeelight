package com.ambient.yeelight.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Switch
import android.widget.TextView
import com.ambient.yeelight.R
import com.ambient.yeelight.network.Device

/**
 * The lamps in the main screen's list, each with a switch for the ambient light.
 *
 * @param onUsedChange called when the user flips a lamp's switch
 */
class DeviceListAdapter(
    context: Context,
    private val onUsedChange: (device: Device, used: Boolean) -> Unit,
) : BaseAdapter() {
    private val inflater = LayoutInflater.from(context)
    private var devices: List<Device> = emptyList()
    private var online: Set<String> = emptySet() // Keys of the lamps that answer

    override fun getCount(): Int = devices.size

    override fun getItem(position: Int): Device = devices[position]

    override fun getItemId(position: Int): Long = position.toLong()

    @SuppressLint("SetTextI18n", "UseSwitchCompatOrMaterialCode")
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val device = getItem(position)
        val view = convertView ?: inflater.inflate(R.layout.device_list_item, parent, false)
        val status = if (device.key in online) "online" else "offline"
        val ambientSwitch = view.findViewById<Switch>(R.id.ambient_switch)

        view.findViewById<TextView>(R.id.device_model).text = "Model: ${device.model}"
        view.findViewById<TextView>(R.id.device_location).text = "Location: ${device.host}"
        view.findViewById<TextView>(R.id.device_status).text = "Status: $status"

        ambientSwitch.isChecked = device.used
        ambientSwitch.setOnClickListener {
            onUsedChange(device, ambientSwitch.isChecked)
        }

        return view
    }

    /** Shows [devices]; [online] holds the [Device.key]s of the lamps that answer. */
    fun update(devices: List<Device>, online: Set<String>) {
        this.devices = devices
        this.online = online
        notifyDataSetChanged()
    }
}
