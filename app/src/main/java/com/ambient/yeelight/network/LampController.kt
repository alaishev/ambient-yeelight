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
 * Runs the ambient light on one lamp:
 * - start() remembers what the lamp shows, turns it on, moves to music mode if the lamp can, and
 *   sets the brightness;
 * - setColor() then sends the colors;
 * - stop() leaves music mode, gives the lamp its old state back and closes the connection.
 *
 * Commands go at least commandIntervalMs apart; a new color fades in over transitionMs. If the
 * connection is lost, the next command opens a new one, but not more often than once in
 * RECONNECT_DELAY_MS. In music mode the lamp takes any number of commands. If music mode doesn't
 * work, the controller uses usual connections and opens a new one after every MAX_COMMANDS
 * commands, the lamp's limit for one connection.
 *
 * start() works in the background; stop() waits until the lamp has its old state back. All the
 * methods can be called from any thread.
 */
class LampController(
    val device: Device,
    private val commandIntervalMs: Long,
    private val transitionMs: Int,
    private val brightness: Int, // Percent, for the whole ambient light
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val colors = Channel<Int>(Channel.CONFLATED)

    @Volatile
    private var connection: Connection? = null // Volatile: close() may run on another thread

    // Used only by the coroutines of start() and stop(), and they run one after the other
    private var lastSentTime = 0L // SystemClock.elapsedRealtime() of the last command
    private var nextConnectTime = 0L // Don't try to connect before this time, after a failure
    private var useMusicMode = false // connect() opens connections in music mode
    private var musicModeFailed = false // The lamp refused music mode; don't ask again
    private var savedState: LampState? = null // What the lamp showed before start()
    private var colorSender: Job? = null // The coroutine of start(); it ends after stop()

    /**
     * Starts the ambient light on the lamp, in the background: saves its state and turns it on,
     * moves to music mode, sets the brightness, then sends the colors from setColor() until
     * stop().
     */
    fun start() {
        colorSender = scope.launch {
            saveStateAndTurnOn()
            startMusicMode()
            send(Commands.SET_BRIGHT, listOf(brightness, Commands.SMOOTH, FADE_MS))
            for (color in colors) {
                send(Commands.SET_RGB, listOf(color, Commands.SMOOTH, transitionMs))
            }
        }
    }

    /** Sends a color, 0xRRGGBB, without waiting. A color still waiting to go is replaced by it. */
    fun setColor(color: Int) {
        colors.trySend(color)
    }

    /**
     * Ends the ambient light and returns when it's done: a waiting color still goes, then the lamp
     * leaves music mode, gets back what it showed before start(), and the connection closes.
     */
    suspend fun stop() {
        colors.close()
        colorSender?.join()
        stopMusicMode()
        restoreState()
        close()
    }

    /** Closes the connection at once and leaves the lamp as it is. */
    private fun close() {
        colors.close()
        scope.cancel()
        connection?.close()
    }

    /** Remembers what the lamp shows and turns the lamp on if it's off. */
    private suspend fun saveStateAndTurnOn() {
        val props = send(Commands.GET_PROP, LampState.PROPS, reply = true) ?: return
        val state = LampState.from(props)

        savedState = state
        logger.d(TAG, "${device.host}: saved $state")
        if (!state.power) {
            send(Commands.SET_POWER, listOf("on", Commands.SMOOTH, FADE_MS))
        }
    }

    /** Moves to music mode now, if the lamp can. In music mode the lamp doesn't answer commands. */
    private suspend fun startMusicMode() {
        if (!device.supportsMusicMode) return

        useMusicMode = true
        connection?.close()
        connection = connect() // In music mode, or a usual one if the lamp refuses
    }

    /** Leaves music mode: the lamp turns it off when its music connection closes. */
    private fun stopMusicMode() {
        if (!useMusicMode) return

        useMusicMode = false
        connection?.close()
    }

    /**
     * Gives the lamp back what it showed before: turns it off if it was off, otherwise brings back
     * its color and brightness. Does nothing if the state wasn't saved.
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
     * Sends a command, waiting first so commands stay commandIntervalMs apart. With reply, waits
     * for the lamp's answer and returns its result. Returns null if the lamp can't be reached or
     * the command fails; the next call then connects again.
     */
    private suspend fun send(
        method: String,
        params: List<Any>,
        reply: Boolean = false,
    ): JSONArray? {
        val current = connect() ?: return null

        connection = current
        // A negative delay returns at once: the pause is already over
        delay(lastSentTime + commandIntervalMs - SystemClock.elapsedRealtime())
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
     * The connection to send on: the current one while it's open and has commands left. Otherwise
     * closes it and opens a new one, in music mode if we use it. Null if the lamp can't be reached
     * right now.
     */
    private suspend fun connect(): Connection? {
        val current = connection

        if (current != null && current.isOpen) {
            if (!current.commandsLimitReached) return current

            logger.d(TAG, "${device.host}: reconnecting after ${current.sentCommands} commands")
        }
        current?.close()

        val now = SystemClock.elapsedRealtime()

        if (now < nextConnectTime) return null

        try {
            return openMusicMode() ?: openConnection(device)
        }
        catch (e: IOException) {
            logger.e(TAG, "${device.host}: cannot connect -> $e")
            nextConnectTime = now + RECONNECT_DELAY_MS
            return null
        }
    }

    /**
     * Opens a music mode connection: over a usual connection, asks the lamp to connect back to a
     * server socket of ours. Null if we don't use music mode or the lamp refused it; the caller
     * then opens a usual connection. Throws IOException if the lamp can't be reached.
     */
    private suspend fun openMusicMode(): Connection? {
        if (!useMusicMode || musicModeFailed) return null

        val control = openConnection(device)
        var server: ServerSocket? = null

        try {
            server = ServerSocket(0) // Any free port; the lamp connects to it

            val params = listOf(1, control.localAddress.hostAddress, server.localPort)

            server.soTimeout = MUSIC_ACCEPT_TIMEOUT_MS
            if (control.sendRequest(Commands.SET_MUSIC, params) != null) {
                logger.i(TAG, "${device.host}: music mode on")
                return acceptConnection(device, server)
            }
        }
        catch (e: SocketTimeoutException) {
            logger.w(TAG, "${device.host}: did not connect for music mode -> $e")
        }
        finally {
            server?.close() // The accepted connection lives on without the server
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
        const val FADE_MS = 500 // How long a lamp fades on start and when its state comes back
    }
}
