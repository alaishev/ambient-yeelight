package com.ambient.yeelight.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.ambient.yeelight.R
import com.ambient.yeelight.common.Settings
import com.ambient.yeelight.logger
import com.ambient.yeelight.network.LampController
import com.ambient.yeelight.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the ambient light is doing, for the main screen. */
enum class AmbientState {
    STOPPED, // Not running: stopped, or never started
    RUNNING, // Capturing the screen and sending colors to the lamps
    FAILED, // Couldn't start: the screen capture didn't work
}

/**
 * The ambient light itself, as a foreground service. Captures the screen, finds its main color
 * and sends it to the lamps that are enabled in the list. If the list changes while the light is
 * on, the lamps follow at once.
 *
 * start() runs it with the permission from the capture dialog. It stops by stop(), by the button
 * in its notification, or when the system ends the capture. On stop the lamps get back what they
 * showed before. If the system kills the service, it doesn't come back by itself: the capture
 * permission lives only in memory, so the user has to turn the light on again.
 */
class AmbientService : Service() {
    private lateinit var settings: Settings
    private lateinit var capture: ScreenCapture
    // Made when the light starts; after that only the capture thread uses it
    private lateinit var colorExtractor: ColorExtractor
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val lampsLock = Mutex() // Lets only one updateLamps() or stop run at a time

    @Volatile
    private var lamps: List<LampController> = emptyList() // Changed on the main thread only

    // The last color sent to the lamps. A lamp added while the light is on starts with this
    // color, so it doesn't wait for the next change. Written on the capture thread
    @Volatile
    private var lastColor: Int? = null

    // Read from the settings when the light starts. A change in the settings waits for the next
    // start, so all the lamps of one run work the same way
    private var colorIntervalMs = Settings.DEFAULT_COLOR_INTERVAL_MS
    private var transitionMs = Settings.DEFAULT_TRANSITION_MS
    private var brightness = Settings.DEFAULT_BRIGHTNESS

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        capture = ScreenCapture(
            context = this,
            onFrame = { frame -> sendColor(frame) },
            onStopped = { stopSelf() },
        )
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            startAmbientLight(intent)
        }
        else {
            stopSelf() // The stop button in the notification
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        logger.i(TAG, "Stop ambient light")
        settings.stopListening()
        capture.stop()
        if (mutableState.value == AmbientState.RUNNING) {
            mutableState.value = AmbientState.STOPPED
        }
        // The lamps get their state back after the service is gone; the process lives on for that
        scope.launch {
            lampsLock.withLock {
                stopLamps(lamps)
                lamps = emptyList()
            }
            scope.cancel()
        }
        super.onDestroy()
    }

    /** Starts capturing the screen with the permission from the intent, and starts the lamps. */
    private fun startAmbientLight(intent: Intent) {
        val permission = IntentCompat.getParcelableExtra(
            intent,
            EXTRA_PERMISSION,
            Intent::class.java,
        )

        logger.i(TAG, "Start ambient light")

        // Before the capture starts: Android 14 lets only a media projection service capture
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            createNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        colorIntervalMs = settings.colorIntervalMs
        transitionMs = settings.transitionMs
        brightness = settings.brightness
        colorExtractor = ColorExtractor(settings.minDeltaE)
        if (permission == null || !capture.start(permission, colorIntervalMs)) {
            mutableState.value = AmbientState.FAILED
            stopSelf()
            return
        }

        mutableState.value = AmbientState.RUNNING
        settings.listenToDevices(onChange = { scope.launch { updateLamps() } })
        scope.launch { updateLamps() }
    }

    /** Finds the color of the frame and sends it to the lamps, if it has changed enough. */
    private fun sendColor(frame: Bitmap) {
        val color = colorExtractor.findNextColor(frame) ?: return

        lastColor = color
        lamps.forEach { lamp -> lamp.setColor(color) }
    }

    /**
     * Brings the running lamps in line with the lamps enabled in the list. A lamp that was turned
     * off in the list stops and gets its old state back; a newly enabled one starts with the last
     * color. A lamp whose data has changed, for example its address after a search, is restarted.
     * The new lamps start only after the removed ones have their old state back, so a restarted
     * lamp saves its own state, not the ambient light's color. Does nothing once the light is off.
     */
    private suspend fun updateLamps() {
        lampsLock.withLock {
            if (mutableState.value != AmbientState.RUNNING) return

            val enabledDevices = settings.devices.filter { it.enabled }
            // Compares whole devices: a lamp whose name or address changed is restarted
            val kept = lamps.filter { lamp -> lamp.device in enabledDevices }
            val removed = lamps.filter { lamp -> lamp !in kept }
            val keptDevices = kept.map { lamp -> lamp.device }
            val started = enabledDevices
                .filter { device -> device !in keptDevices }
                .map { device -> LampController(device, colorIntervalMs, transitionMs, brightness) }

            lamps = kept // The removed lamps get no more colors while they stop
            stopLamps(removed)

            val color = lastColor

            started.forEach { lamp ->
                lamp.start()
                if (color != null) {
                    lamp.setColor(color)
                }
            }
            lamps = kept + started
            logger.i(TAG, "Lamps: ${lamps.joinToString { lamp -> lamp.device.host }}")
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, createNotification())
        }
    }

    /** Stops these lamps in parallel and waits until each one has its old state back. */
    private suspend fun stopLamps(stopping: List<LampController>) {
        coroutineScope {
            stopping.forEach { lamp -> launch { lamp.stop() } }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )

        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** The service's notification: how many lamps are on, a tap opens the app, a Stop button. */
    private fun createNotification(): Notification {
        val text = if (lamps.isEmpty()) {
            getString(R.string.notification_no_lamps)
        }
        else {
            resources.getQuantityString(R.plurals.notification_lamps, lamps.size, lamps.size)
        }
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val stop = Intent(this, AmbientService::class.java).setAction(ACTION_STOP)
        val flags = PendingIntent.FLAG_IMMUTABLE

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(PendingIntent.getActivity(this, 0, open, flags))
            .addAction(0, getString(R.string.stop), PendingIntent.getService(this, 0, stop, flags))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val TAG = "AmbientService"
        private const val ACTION_START = "com.ambient.yeelight.START"
        private const val ACTION_STOP = "com.ambient.yeelight.STOP"
        private const val EXTRA_PERMISSION = "permission" // The capture permission, an Intent
        private const val CHANNEL_ID = "service_channel"
        private const val NOTIFICATION_ID = 2024

        private val mutableState = MutableStateFlow(AmbientState.STOPPED)

        /** What the ambient light is doing now; only the service changes it. */
        val state: StateFlow<AmbientState> = mutableState.asStateFlow()

        /** Turns the ambient light on. permission is what the capture dialog returned. */
        fun start(context: Context, permission: Intent) {
            val intent = Intent(context, AmbientService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PERMISSION, permission)

            ContextCompat.startForegroundService(context, intent)
        }

        /** Turns the ambient light off; the lamps get back what they showed before. */
        fun stop(context: Context) {
            context.stopService(Intent(context, AmbientService::class.java))
        }
    }
}
