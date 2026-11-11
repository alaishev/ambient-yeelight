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
    RUNNING, // Capturing the screen and setting the lamps' colors
    FAILED, // Could not start: the permission to capture the screen did not work
}

/**
 * The foreground service of the ambient light: captures the screen, finds its color and sets it
 * on the lamps chosen in the settings, following the changes of that choice while it runs.
 *
 * Started by [start] with the user's permission to capture the screen; stopped by [stop], by the
 * button in its notification, or when the system ends the capture. On stop the lamps get back
 * what they showed before. The service is not restarted after the system kills it: the permission
 * lives only in memory, so the user has to start the light again.
 */
class AmbientService : Service() {
    private lateinit var settings: Settings
    private lateinit var capture: ScreenCapture
    private val colorExtractor = ColorExtractor() // Used on the capture's thread only
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val lampsLock = Mutex() // One updateLamps() at a time

    @Volatile
    private var lamps: List<LampController> = emptyList() // Set on the main thread

    @Volatile
    private var lastColor: Int? = null // Set on the capture's thread; new lamps start with it

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        capture = ScreenCapture(
            context = this,
            onFrame = { frame -> sendColor(frame) },
            onStopped = { stopSelf() },
        )
        createChannel()
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

    /** Starts capturing the screen with the permission in [intent] and sets the lamps going. */
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
            notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        if (permission == null || !capture.start(permission, COLOR_INTERVAL_MS)) {
            mutableState.value = AmbientState.FAILED
            stopSelf()
            return
        }

        mutableState.value = AmbientState.RUNNING
        settings.listenToDevices(onChange = { scope.launch { updateLamps() } })
        scope.launch { updateLamps() }
    }

    /** Finds the color of [frame] and sends it to the lamps if it has changed. */
    private fun sendColor(frame: Bitmap) {
        val color = colorExtractor.nextColor(frame) ?: return

        lastColor = color
        lamps.forEach { lamp -> lamp.setColor(color) }
    }

    /**
     * Matches the running lamps to the ones chosen in the settings. A lamp no longer chosen stops
     * and gets its state back; a newly chosen one starts with the last color. A lamp whose data
     * has changed, its address after a search for example, is restarted. The new lamps start only
     * after the removed ones have their state back, so a restarted lamp saves its own state, not
     * the ambient light's color. Does nothing once the light is stopped.
     */
    private suspend fun updateLamps() {
        lampsLock.withLock {
            if (mutableState.value != AmbientState.RUNNING) return

            val chosen = settings.devices.filter { it.used }
            val kept = lamps.filter { lamp -> lamp.device in chosen }
            val removed = lamps.filter { lamp -> lamp !in kept }
            val keptDevices = kept.map { lamp -> lamp.device }
            val started = chosen
                .filter { device -> device !in keptDevices }
                .map { device -> LampController(device) }

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
                .notify(NOTIFICATION_ID, notification())
        }
    }

    /** Stops [stopping] all at once; returns when every one has its state back. */
    private suspend fun stopLamps(stopping: List<LampController>) {
        coroutineScope {
            stopping.forEach { lamp -> launch { lamp.stop() } }
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Ambient light",
            NotificationManager.IMPORTANCE_LOW,
        )

        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** The service's notification: the number of lamps, a tap opens the app, a stop button. */
    private fun notification(): Notification {
        val text = if (lamps.isEmpty()) "No lamps chosen" else "Lamps: ${lamps.size}"
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val stop = Intent(this, AmbientService::class.java).setAction(ACTION_STOP)
        val flags = PendingIntent.FLAG_IMMUTABLE

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_light_bulb_icon)
            .setContentTitle("Ambient light is on")
            .setContentText(text)
            .setContentIntent(PendingIntent.getActivity(this, 0, open, flags))
            .addAction(0, "Stop", PendingIntent.getService(this, 0, stop, flags))
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
        private const val COLOR_INTERVAL_MS = 330L // How often a frame is captured

        private val mutableState = MutableStateFlow(AmbientState.STOPPED)

        /** What the ambient light is doing now; only the service changes it. */
        val state: StateFlow<AmbientState> = mutableState.asStateFlow()

        /** Starts the ambient light with [permission], the result of the capture dialog. */
        fun start(context: Context, permission: Intent) {
            val intent = Intent(context, AmbientService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PERMISSION, permission)

            ContextCompat.startForegroundService(context, intent)
        }

        /** Stops the ambient light; the lamps get back what they showed before it. */
        fun stop(context: Context) {
            context.stopService(Intent(context, AmbientService::class.java))
        }
    }
}
