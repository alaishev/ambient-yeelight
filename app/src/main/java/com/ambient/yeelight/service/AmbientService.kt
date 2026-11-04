package com.ambient.yeelight.service


import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.ambient.yeelight.AmbientLightApp
import com.ambient.yeelight.R
import com.ambient.yeelight.common.PreferenceManager
import com.ambient.yeelight.ui.MainActivity
import com.ambient.yeelight.yeelight.YeeLightDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray


class AmbientService : Service() {
    private var mLogger = AmbientLightApp.getAppLogger()

    private lateinit var mYeeLightDevices: ArrayList<YeeLightDevice>
    private lateinit var mScreenRecorder: ScreenRecorder
    private lateinit var mPrefs: PreferenceManager

    private var job: Job = Job()
    private var scope = CoroutineScope(Dispatchers.IO)

    override fun onBind(intent: Intent): IBinder {
        TODO("Return the communication channel to the service.")
    }

    override fun onCreate() {
        super.onCreate()
        mLogger.logD(TAG, "Create Service")
        mPrefs = PreferenceManager(this)

        // Create staff
        mYeeLightDevices = ArrayList()

        mScreenRecorder = ScreenRecorder(this) { color: Int, _: FloatArray ->
            for (device in mYeeLightDevices) {
            job = scope.launch {
                    device.setColor(color)
                }
            }
        }

        showNotification("No devices are used to light")
    }

    override fun onDestroy() {
        super.onDestroy()
        mLogger.logD(TAG, "Destroy Service")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            "start" -> {
                val pData: Intent? = intent.getParcelableExtra("pData")
                startAmbientLight(pData!!, delay)
            }

            "stop"  -> {
                stopAmbientLight()
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent) {
        mLogger.logI(TAG, "App was swiped by user")
        val restartServiceIntent = Intent(applicationContext, this.javaClass)
        restartServiceIntent.setPackage(packageName)
        var flags = PendingIntent.FLAG_ONE_SHOT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags =
            flags or PendingIntent.FLAG_IMMUTABLE
        val restartServicePendingIntent =
            PendingIntent.getService(applicationContext, 1, restartServiceIntent, flags)
        val alarmService = applicationContext.getSystemService(ALARM_SERVICE) as AlarmManager
        alarmService[AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000] =
            restartServicePendingIntent
    }

    private fun showNotification(status: String) {
        mLogger.logI(TAG, "Show notification: %s", status)
        val notification: Notification
        val builder = Notification.Builder(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Ambient YeeLight Service", NotificationManager.IMPORTANCE_LOW)
            channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC // will be shown in lock screen
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
            builder.setChannelId(CHANNEL_ID)
        }

        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        var flags = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags =
            flags or PendingIntent.FLAG_IMMUTABLE
        @SuppressLint("UnspecifiedImmutableFlag")
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)

        // Set Notification when Service will be started as foreground
        builder
            .setSmallIcon(R.drawable.ic_light_bulb_icon)
            .setContentTitle("Ambient Service is running")
            .setContentText(status)
            .setPriority(Notification.PRIORITY_MAX)
            .setAutoCancel(false)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
        notification = builder.build()

        flags = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            flags = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_CHANNEL, notification, flags)
        }
        else {
            startForeground(NOTIFICATION_CHANNEL, notification)
        }
    }

    private fun startAmbientLight(data: Intent, delay: Long) {
        mLogger.logI(TAG, "Start ambient light")
        initializeDevices()
        mScreenRecorder.start(data, delay)
    }

    private fun stopAmbientLight() {
        mLogger.logI(TAG, "Stop ambient light")
        mScreenRecorder.stop()
    }

    private fun initializeDevices() {
        // 192.168.1.97 - colorb
        // 192.168.1.237 - color
        // 192.168.1.198 - lamp15
        // 192.168.1.113 - stripe
        // 192.168.1.103 - stripe6
        val hosts = arrayOf("192.168.1.103", "192.168.1.237", "192.168.1.113")
        val port = 55443

        job = scope.launch {
            // Initialize devices which are chosen for ambient light
            val devices = loadDevices()
            for (device in devices) {
                if (device.used) {
                    job = scope.launch {
                        try {
                            // We want to check current status of device and it state
                            device.initialize()
                            mYeeLightDevices.add(device)
                            mLogger.logD(TAG, "Initialized device with id = ${device.id}")
                            showNotification("Using ${mYeeLightDevices.size} devices")
                        } catch (e: Exception) {
                            e.printStackTrace()
                            mLogger.logE(TAG, "Initialize device error: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    // TODO remove duplication
    private fun loadDevices(): ArrayList<YeeLightDevice> {
        val devices = ArrayList<YeeLightDevice>()

        val str = mPrefs.getDevices()
        if (str.isEmpty()) {
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
            devices.add(device)
        }

        return devices
    }

    companion object {
        const val TAG = "AmbientService"

        private const val NOTIFICATION_CHANNEL = 2024
        private const val CHANNEL_ID = "service_channel"

        private const val delay: Long = 330
    }
}