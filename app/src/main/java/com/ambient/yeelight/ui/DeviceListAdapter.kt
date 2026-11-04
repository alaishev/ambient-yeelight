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
import com.ambient.yeelight.common.PreferenceManager
import com.ambient.yeelight.yeelight.YeeLightDevice
import org.json.JSONArray


class DeviceListAdapter(context: Context, devices: ArrayList<YeeLightDevice>) : BaseAdapter() {

    private var mDeviceList: ArrayList<YeeLightDevice>
    private val mInflater: LayoutInflater
    private val mLayoutResource: Int
    private val mPrefs: PreferenceManager

    init {
        mDeviceList = devices
        mInflater = LayoutInflater.from(context)
        mLayoutResource = R.layout.device_list_item
        mPrefs = PreferenceManager(context)
    }

    override fun getCount(): Int {
        return mDeviceList.size
    }

    override fun getItem(position: Int): Any {
        return mDeviceList[position]
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    @SuppressLint("SetTextI18n", "UseSwitchCompatOrMaterialCode")
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val device = getItem(position) as YeeLightDevice
        val view: View = convertView ?: mInflater.inflate(mLayoutResource, parent, false)

        val textModel = view.findViewById<View>(R.id.device_model) as TextView
        textModel.text = "Model: " + device.model

        val textLocation = view.findViewById<View>(R.id.device_location) as TextView
        textLocation.text = "Location: ${device.host}"

        val textStatus = view.findViewById<View>(R.id.device_status) as TextView
        textStatus.text = "Status: " + if (device.online) "online" else "offline"

        val ambientSwitch = view.findViewById<View>(R.id.ambient_switch) as Switch
        ambientSwitch.isChecked = device.used

        ambientSwitch.setOnClickListener {
            device.used = (it as Switch).isChecked
            updatePrefs()
            sendBroadcastMessage(device)
        }

        return view
    }

    /**
     * Display new list of found/available devices in network
     * @param devices new array with available devices
     */
    fun updateResults(devices: ArrayList<YeeLightDevice>) {
        mDeviceList = devices
        notifyDataSetChanged()
    }

    /**
     * Save al listed devices information to shared preferences
     */
    private fun updatePrefs() {
        val jsonArray = JSONArray()
        for (deviceInfo in mDeviceList) {
            jsonArray.put(deviceInfo.toJson())
        }
        mPrefs.setDevices(jsonArray.toString())
    }

    /**
     * Send update to service for switch target device for using as ambient light
     * @param device information about target device
     */
    private fun sendBroadcastMessage(device: YeeLightDevice) {
        // TODO send broadcast to service for instant change
    }
}