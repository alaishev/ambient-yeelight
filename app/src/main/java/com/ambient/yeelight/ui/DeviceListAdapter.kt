package com.ambient.yeelight.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ambient.yeelight.R
import com.ambient.yeelight.databinding.DeviceListItemBinding
import com.ambient.yeelight.network.Device

/**
 * The lamps in the main screen's list. A whole row takes the remote's focus; a click on it turns
 * the lamp's ambient light on or off, a long click asks to remove the lamp.
 *
 * @param onEnabledChange called with the lamp's new state when the user clicks it
 * @param onRemoveRequest called when the user long-clicks a lamp
 */
class DeviceListAdapter(
    private val onEnabledChange: (device: Device, enabled: Boolean) -> Unit,
    private val onRemoveRequest: (device: Device) -> Unit,
) : RecyclerView.Adapter<DeviceListAdapter.LampViewHolder>() {
    private var devices: List<Device> = emptyList()
    private var online: Set<String> = emptySet() // Keys of the lamps that answer

    init {
        setHasStableIds(true) // A row keeps its view, and the focus, when the list is updated
    }

    override fun getItemCount(): Int = devices.size

    override fun getItemId(position: Int): Long = devices[position].key.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LampViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        return LampViewHolder(DeviceListItemBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: LampViewHolder, position: Int) {
        val device = devices[position]
        val binding = holder.binding
        val context = binding.root.context
        val statusId = if (device.key in online) R.string.lamp_online else R.string.lamp_offline
        // A lamp added by hand may have no name and no model
        val title = when {
            device.name.isEmpty() -> device.model.ifEmpty { device.host }
            device.model.isEmpty() -> device.name
            else -> context.getString(R.string.dot_separated, device.name, device.model)
        }

        binding.lampTitle.text = title
        binding.lampDetails.text =
            context.getString(R.string.dot_separated, device.host, context.getString(statusId))
        binding.enabledSwitch.isChecked = device.enabled
        binding.lampIcon.isActivated = device.enabled // Picks the icon's color
        binding.root.setOnClickListener {
            onEnabledChange(device, !device.enabled)
        }
        binding.root.setOnLongClickListener {
            onRemoveRequest(device)
            true
        }
    }

    /** Shows the lamps; online holds the keys of the lamps that answer. */
    @SuppressLint("NotifyDataSetChanged") // The whole list changes: a search, an added lamp
    fun update(devices: List<Device>, online: Set<String>) {
        this.devices = devices
        this.online = online
        notifyDataSetChanged()
    }

    /** One row of the list. */
    class LampViewHolder(val binding: DeviceListItemBinding) : RecyclerView.ViewHolder(binding.root)
}
