package com.ambient.yeelight.network

import com.ambient.yeelight.logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Talks to one lamp over an open socket. Sends commands as lines (see Commands), each with its
 * own id, and matches the lamp's answers to them by that id. Made by openConnection() or
 * acceptConnection(). Once the socket is closed, by close() or by the lamp, the connection is
 * done and isn't used again.
 *
 * A lamp takes at most MAX_COMMANDS commands a minute on one connection and silently drops the
 * rest. So a connection sends no more than that: the next command throws CommandLimitException.
 * With noLimit there's no such limit; that's for music mode, where the lamp takes any number.
 *
 * Only one caller may send at a time. Answers are read on the connection's own thread; close()
 * can be called from any thread.
 */
class Connection(
    val device: Device,
    private val socket: Socket,
    private val noLimit: Boolean = false,
) : Closeable {
    // Answers we wait for, by command id
    private val replies = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private var nextId = 1

    @Volatile
    private var closed = false // Set by close(), so the reader doesn't log it as a lost connection

    /** How many commands this connection has sent. */
    var sentCommands = 0
        private set

    /** True until the socket is closed. */
    val isOpen: Boolean
        get() = !socket.isClosed

    /** True once MAX_COMMANDS commands are sent, unless there's noLimit. */
    val commandsLimitReached: Boolean
        get() = !noLimit && (sentCommands >= MAX_COMMANDS)

    /** Our address in the lamp's network: the one the lamp can connect back to. */
    val localAddress: InetAddress
        get() = socket.localAddress

    init {
        thread(isDaemon = true, name = "Lamp ${device.host}") { read() }
        logger.i(TAG, "${device.host}: connected")
    }

    /** Sends a command and doesn't wait for an answer. Throws IOException. */
    suspend fun sendCommand(method: String, params: List<Any>) {
        write(method, params, reply = null)
    }

    /**
     * Sends a command and waits up to REPLY_TIMEOUT_MS for the answer. Returns the answer's
     * result, or null if the lamp doesn't answer or reports an error. Throws IOException if the
     * command can't be sent.
     */
    suspend fun sendRequest(method: String, params: List<Any>): JSONArray? {
        val reply = CompletableDeferred<JSONObject>()
        val id = write(method, params, reply)
        val answer = withTimeoutOrNull(REPLY_TIMEOUT_MS) { reply.await() }

        replies.remove(id)
        if (answer == null) {
            logger.w(TAG, "${device.host}: no reply to $method")
            return null
        }

        val result = answer.optJSONArray("result")

        if (result == null) {
            logger.w(TAG, "${device.host}: $method failed: ${answer.opt("error")}")
        }
        return result
    }

    /**
     * Asks the lamp for its name and whether it has music mode, and returns the device with them;
     * null if the lamp doesn't answer. The name may be empty. A lamp answers an unknown property
     * with an empty string (Yeelight spec), so an empty music_on means there's no music mode.
     */
    suspend fun requestDevice(): Device? {
        val props = sendRequest(Commands.GET_PROP, DEVICE_PROPS) ?: return null

        return device.copy(
            name = props.optString(0),
            supportsMusicMode = props.optString(1).isNotEmpty(),
        )
    }

    /** Closes the socket. That also ends the reader thread. */
    override fun close() {
        closed = true
        socket.close()
    }

    /**
     * Writes one command line. If reply is given, it gets the lamp's answer to this command.
     * Returns the command id. Throws CommandLimitException once the limit is reached.
     */
    private suspend fun write(
        method: String,
        params: List<Any>,
        reply: CompletableDeferred<JSONObject>?,
    ): Int {
        if (commandsLimitReached) throw CommandLimitException()

        val id = nextId++
        val line = createCommand(id, method, params)

        if (reply != null) {
            replies[id] = reply
        }

        try {
            withContext(Dispatchers.IO) {
                socket.getOutputStream().apply {
                    write(line.toByteArray())
                    flush()
                }
            }
        }
        catch (e: IOException) {
            replies.remove(id)
            throw e
        }

        sentCommands++
        return id
    }

    /**
     * Reads the lamp's answers until the socket closes, then closes it on our side too, so isOpen
     * turns false. Lines without an id are the lamp's state notifications; we skip them.
     */
    private fun read() {
        try {
            socket.getInputStream().bufferedReader().forEachLine { line ->
                try {
                    val json = JSONObject(line)

                    if (json.has("id")) {
                        replies.remove(json.getInt("id"))?.complete(json)
                    }
                }
                catch (e: JSONException) {
                    logger.w(TAG, "${device.host}: bad reply \"$line\"", e)
                }
            }
            logger.i(TAG, "${device.host}: closed by the lamp")
        }
        catch (e: IOException) {
            if (!closed) {
                logger.w(TAG, "${device.host}: connection lost -> $e")
            }
        }
        finally {
            socket.close()
        }
    }

    companion object {
        /** A lamp takes at most 60 commands a minute on one connection (Yeelight spec). */
        const val MAX_COMMANDS = 60

        private const val TAG = "Connection"
        private const val REPLY_TIMEOUT_MS = 2_000L
        private val DEVICE_PROPS = listOf("name", "music_on") // In the order requestDevice() reads
    }
}

private const val CONNECT_TIMEOUT_MS = 2_000

/** Connects to the lamp's control port, waiting up to CONNECT_TIMEOUT_MS. Throws IOException. */
suspend fun openConnection(device: Device): Connection {
    val socket = Socket()

    try {
        withContext(Dispatchers.IO) {
            socket.connect(InetSocketAddress(device.host, device.port), CONNECT_TIMEOUT_MS)
        }
    }
    catch (e: IOException) {
        socket.close()
        throw e
    }
    return Connection(device, socket)
}

/**
 * Waits until the lamp connects to our server, for music mode: such a connection has no command
 * limit. Waits as long as the server's timeout. Throws IOException, or SocketTimeoutException if
 * the lamp doesn't come.
 */
suspend fun acceptConnection(device: Device, server: ServerSocket): Connection {
    val socket = withContext(Dispatchers.IO) { server.accept() }

    return Connection(device, socket, noLimit = true)
}

/**
 * Thrown when a connection has already sent MAX_COMMANDS; the caller opens a new one. It's an
 * IOException, so it's handled like any other send failure.
 */
class CommandLimitException : IOException("Commands limit reached")
