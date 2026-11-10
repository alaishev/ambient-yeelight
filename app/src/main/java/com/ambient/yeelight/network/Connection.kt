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
 * The lamp's protocol over a connected [socket] to [device]: sends [Commands] lines, each with an
 * id of its own, and matches the lamp's replies to them by the id. Made by [openConnection] or
 * [acceptConnection]. The connection lives as long as the socket: once [close] or the lamp closes
 * it, it is not used again.
 *
 * A lamp takes at most [MAX_COMMANDS] commands per minute on one connection and drops the rest,
 * so a connection sends no more than that: the next command throws [CommandLimitException].
 * [noLimit] turns the limit off, for a connection the lamp takes any number of commands on.
 *
 * One caller sends at a time. Replies are read on a thread of their own; [close] can be called
 * from any thread.
 */
class Connection(
    val device: Device,
    private val socket: Socket,
    private val noLimit: Boolean = false,
) : Closeable {
    private val replies = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>() // By command id
    private var nextId = 1

    @Volatile
    private var closed = false // Set by close(), so the reader does not report a lost connection

    /** Commands sent on this connection. */
    var sentCommands = 0
        private set

    /** True until the socket closes. */
    val isOpen: Boolean
        get() = !socket.isClosed

    /** True once [MAX_COMMANDS] are sent, unless there is [noLimit]. */
    val commandsLimitReached: Boolean
        get() = !noLimit && (sentCommands >= MAX_COMMANDS)

    /** Our address in the lamp's network, the one the lamp can connect back to. */
    val localAddress: InetAddress
        get() = socket.localAddress

    init {
        thread(isDaemon = true, name = "Lamp ${device.host}") { read() }
        logger.i(TAG, "${device.host}: connected")
    }

    /** Sends a command without waiting for the reply; throws IOException. */
    suspend fun sendCommand(method: String, params: List<Any>) {
        write(method, params, reply = null)
    }

    /**
     * Sends a command and waits up to [REPLY_TIMEOUT_MS] for the reply. Returns the reply's
     * result; null if the lamp does not reply or reports an error. Throws IOException if the
     * command cannot be sent.
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

    /** Asks the lamp for its name, which may be empty; null if it does not reply. */
    suspend fun requestName(): String? {
        return sendRequest(Commands.GET_PROP, listOf("name"))?.optString(0)
    }

    /** Closes the socket, which also stops the reader. */
    override fun close() {
        closed = true
        socket.close()
    }

    /**
     * Writes one command; [reply], if any, gets the lamp's reply to it. Returns the command id.
     * Throws [CommandLimitException] once [commandsLimitReached].
     */
    private suspend fun write(
        method: String,
        params: List<Any>,
        reply: CompletableDeferred<JSONObject>?,
    ): Int {
        if (commandsLimitReached) throw CommandLimitException()

        val id = nextId++
        val line = Commands.create(id, method, params)

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
     * Reads the lamp's replies until the socket closes, then closes it, so [isOpen] turns false.
     * Lines without an id are state notifications the lamp sends; they are skipped.
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
        /**
         * Yeelight Inter-Operation Specification: a lamp takes at most 60 commands per minute on
         * one connection.
         */
        const val MAX_COMMANDS = 60

        private const val TAG = "Connection"
        private const val REPLY_TIMEOUT_MS = 2_000L
    }
}

private const val CONNECT_TIMEOUT_MS = 2_000

/** Connects to the control port of [device] within [CONNECT_TIMEOUT_MS]; throws IOException. */
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
 * Waits for [device] to connect to [server], as long as the server's timeout, for a connection
 * with no command limit; throws IOException, a SocketTimeoutException if the lamp does not come.
 */
suspend fun acceptConnection(device: Device, server: ServerSocket): Connection {
    val socket = withContext(Dispatchers.IO) { server.accept() }

    return Connection(device, socket, noLimit = true)
}

/**
 * Thrown by [Connection] for a command over [Connection.MAX_COMMANDS]; the caller opens a new
 * connection. An IOException, so it goes the way of other send failures.
 */
class CommandLimitException : IOException("Commands limit reached")
