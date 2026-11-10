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
import com.ambient.yeelight.R
import com.ambient.yeelight.common.Settings
import com.ambient.yeelight.logger
import com.ambient.yeelight.network.LampController
import com.ambient.yeelight.ui.MainActivity


class AmbientService : Service() {
    private lateinit var mScreenRecorder: ScreenRecorder
    private lateinit var settings: Settings

    @Volatile
    private var lamps: List<LampController> = emptyList() // Read on the recorder's thread

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        logger.d(TAG, "Create Service")
        settings = Settings(this)

        mScreenRecorder = ScreenRecorder(this) { color ->
            lamps.forEach { it.setColor(color) }
        }

        showNotification("No devices are used to light")
    }

    override fun onDestroy() {
        super.onDestroy()
        logger.d(TAG, "Destroy Service")
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
        logger.i(TAG, "App was swiped by user")
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
        logger.i(TAG, "Show notification: $status")
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
        logger.i(TAG, "Start ambient light")
        initializeDevices()
        mScreenRecorder.start(data, delay)
    }

    private fun stopAmbientLight() {
        logger.i(TAG, "Stop ambient light")
        mScreenRecorder.stop()
        lamps.forEach { it.stop() }
        lamps = emptyList()
    }

    private fun initializeDevices() {
        val used = settings.devices.filter { it.used }

        lamps.forEach { it.close() }
        lamps = used.map { LampController(it) }
        lamps.forEach { it.start() }
        logger.i(TAG, "Using ${used.size} devices: ${used.joinToString { it.host }}")
        showNotification("Using ${lamps.size} devices")
    }

    companion object {
        const val TAG = "AmbientService"

        private const val NOTIFICATION_CHANNEL = 2024
        private const val CHANNEL_ID = "service_channel"

        private const val delay: Long = 330
    }
}