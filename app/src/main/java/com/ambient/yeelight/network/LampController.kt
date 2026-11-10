package com.ambient.yeelight.network

import android.os.SystemClock
import com.ambient.yeelight.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException

/**
 * Drives one lamp, [device], for the ambient light:
 * - [start] saves what the lamp shows, turns it on and moves to music mode if the lamp supports it;
 * - [setColor] then sets the colors;
 * - [stop] leaves music mode, brings the saved state back and closes the connection.
 *
 * Commands go at least [COMMAND_INTERVAL_MS] apart. A lost connection is opened again on the next
 * command, at most once per [RECONNECT_DELAY_MS]. In music mode the lamp takes commands with no
 * limit; if music mode fails, the controller keeps to usual connections. A usual connection is
 * opened anew once it has sent [Connection.MAX_COMMANDS], the lamp's limit for one connection.
 *
 * [start] and [stop] do their work in the background, one after the other; all the methods can be
 * called from any thread.
 */
class LampController(val device: Device) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val colors = Channel<Int>(Channel.CONFLATED)

    @Volatile
    private var connection: Connection? = null // close() closes it from any thread

    // Used by the coroutines of start() and stop(), which run one after the other
    private var lastSentTime = 0L // SystemClock.elapsedRealtime() of the last command
    private var retryTime = 0L // No connection attempt before this time
    private var musicModeOn = false // connect() opens connections in music mode
    private var musicModeFailed = false // The lamp did not turn music mode on; do not ask again
    private var savedState: LampState? = null // What the lamp showed before start()
    private var colorSender: Job? = null // Set by start(), ends after stop()

    /**
     * Starts the ambient light on the lamp, in the background: saves its state and turns it on,
     * moves to music mode, then sends the colors from [setColor] until [stop].
     */
    fun start() {
        colorSender = scope.launch {
            saveStateAndTurnOn()
            startMusicMode()
            for (color in colors) {
                send(Commands.SET_RGB, listOf(color, Commands.SMOOTH, TRANSITION_MS))
            }
        }
    }

    /** Sets the lamp's color, `0xRRGGBB`, without waiting; a newer color replaces a waiting one. */
    fun setColor(color: Int) {
        colors.trySend(color)
    }

    /**
     * Ends the ambient light, in the background: a waiting color still goes, then the lamp leaves
     * music mode, gets back what it showed before [start], and the connection closes.
     */
    fun stop() {
        colors.close()
        scope.launch {
            colorSender?.join()
            stopMusicMode()
            restoreState()
            close()
        }
    }

    /** Closes the connection at once, leaving the lamp as it is now. */
    fun close() {
        colors.close()
        scope.cancel()
        connection?.close()
    }

    /** Reads what the lamp shows into [savedState] and turns the lamp on if it is off. */
    private suspend fun saveStateAndTurnOn() {
        val props = send(Commands.GET_PROP, LampState.PROPS, reply = true) ?: return
        val state = LampState.from(props)

        savedState = state
        logger.d(TAG, "${device.host}: saved $state")
        if (!state.power) {
            send(Commands.SET_POWER, listOf("on", Commands.SMOOTH, FADE_MS))
        }
    }

    /** Moves to music mode now, if the lamp supports it; in music mode the lamp does not reply. */
    private suspend fun startMusicMode() {
        if (!device.musicMode) return

        musicModeOn = true
        connection?.close()
        connection = connect() // In music mode, or a usual one if the lamp refuses
    }

    /** Leaves music mode: closing the lamp's connection turns it off. */
    private fun stopMusicMode() {
        if (!musicModeOn) return

        musicModeOn = false
        connection?.close()
    }

    /**
     * Brings back [savedState]: turns the lamp off if it was off, else its color and brightness.
     * Does nothing if the state was not saved.
     */
    private suspend fun restoreState() {
        val state = savedState ?: return

        if (!state.power) {
            send(Commands.SET_POWER, listOf("off", Commands.SMOOTH, FADE_MS))
            return
        }

        when (state.colorMode) {
            LampState.MODE_RGB -> {
                send(Commands.SET_RGB, listOf(state.rgb, Commands.SMOOTH, FADE_MS))
            }
            LampState.MODE_CT -> {
                send(Commands.SET_CT, listOf(state.ct, Commands.SMOOTH, FADE_MS))
            }
            LampState.MODE_HSV -> {
                send(Commands.SET_HSV, listOf(state.hue, state.sat, Commands.SMOOTH, FADE_MS))
            }
        }
        send(Commands.SET_BRIGHT, listOf(state.bright, Commands.SMOOTH, FADE_MS))
        logger.d(TAG, "${device.host}: restored $state")
    }

    /**
     * Sends a command on an open connection, after the pause [COMMAND_INTERVAL_MS] needs; with
     * [reply], waits for the lamp's reply and returns its result. Returns null if the lamp cannot
     * be reached or the command fails, and then a later call reconnects.
     */
    private suspend fun send(
        method: String,
        params: List<Any>,
        reply: Boolean = false,
    ): JSONArray? {
        val current = connect() ?: return null

        connection = current
        delay(lastSentTime + COMMAND_INTERVAL_MS - SystemClock.elapsedRealtime())
        try {
            val result = if (reply) {
                current.sendRequest(method, params)
            }
            else {
                current.sendCommand(method, params)
                null
            }

            lastSentTime = SystemClock.elapsedRealtime()
            return result
        }
        catch (e: IOException) {
            logger.e(TAG, "${device.host}: cannot send $method -> $e")
            current.close()
            return null
        }
    }

    /**
     * The connection to send on: [connection] while it is open and has commands left, else a new
     * one, in music mode if it works, and the old one is closed. Null if the lamp cannot be
     * reached now.
     */
    private suspend fun connect(): Connection? {
        val current = connection

        if (current != null && current.isOpen) {
            if (!current.commandsLimitReached) return current

            logger.d(TAG, "${device.host}: reconnecting after ${current.sentCommands} commands")
        }
        current?.close()

        val now = SystemClock.elapsedRealtime()

        if (now < retryTime) return null

        try {
            return openMusicMode() ?: openConnection(device)
        }
        catch (e: IOException) {
            logger.e(TAG, "${device.host}: cannot connect -> $e")
            retryTime = now + RECONNECT_DELAY_MS
            return null
        }
    }

    /**
     * Opens a music mode connection: over a usual connection, asks the lamp to connect to a server
     * socket of ours. Null if music mode is off or the lamp has failed to turn it on; then the
     * caller opens a usual connection. Throws IOException if the lamp cannot be reached.
     */
    private suspend fun openMusicMode(): Connection? {
        if (!musicModeOn || musicModeFailed) return null

        val control = openConnection(device)

        try {
            ServerSocket(0).use { server ->
                val params = listOf(1, control.localAddress.hostAddress, server.localPort)

                server.soTimeout = MUSIC_ACCEPT_TIMEOUT_MS
                if (control.sendRequest(Commands.SET_MUSIC, params) != null) {
                    logger.i(TAG, "${device.host}: music mode on")
                    return acceptConnection(device, server)
                }
            }
        }
        catch (e: SocketTimeoutException) {
            logger.w(TAG, "${device.host}: did not connect for music mode -> $e")
        }
        finally {
            control.close()
        }

        logger.w(TAG, "${device.host}: no music mode, usual connections from now on")
        musicModeFailed = true
        return null
    }

    private companion object {
        const val TAG = "LampController"
        const val RECONNECT_DELAY_MS = 5_000L
        const val MUSIC_ACCEPT_TIMEOUT_MS = 3_000 // How long the lamp may take to connect to us
        // Up to 3 colors a second, as often as the screen is captured; more makes no visible
        // difference. On a usual connection a lamp takes 144 commands per minute (Yeelight
        // Inter-Operation Specification), so a long run of color changes may lose a few there;
        // the color filter in ScreenRecorder makes it rare
        const val COMMAND_INTERVAL_MS = 330L
        const val TRANSITION_MS = 300 // How long a lamp fades to a new color
        const val FADE_MS = 500 // How long a lamp fades on start and when its state comes back
    }
}
