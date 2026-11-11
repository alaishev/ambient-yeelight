package com.ambient.yeelight.network

import android.os.SystemClock
import com.ambient.yeelight.logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Finds Yeelight lamps in the LAN: sends an SSDP M-SEARCH to the lamps' multicast group and
 * collects the answers. The lamps answer straight to our socket, so no multicast lock is needed.
 * [request] asks one lamp the same way, at its address.
 */
class Discovery {

    /**
     * Searches for [DURATION_MS] and returns the lamps that answered, each once. Runs on the IO
     * dispatcher; cancelling the coroutine stops the search within [RECEIVE_TIMEOUT_MS].
     */
    suspend fun search(): List<Device> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, Device>() // By id: a lamp answers every request

        try {
            DatagramSocket().use { socket -> collect(socket, found) }
        }
        catch (e: IOException) {
            logger.e(TAG, "Search failed -> $e")
        }

        found.values.toList()
    }

    /**
     * Asks the lamp at [host] directly, for a lamp the search does not reach: a router may drop
     * multicast but pass a datagram to one address. The lamp answers as it does to the search.
     * Returns null if it does not answer within about [REQUEST_COUNT] × [ANSWER_TIMEOUT_MS]. Runs
     * on the IO dispatcher.
     */
    suspend fun request(host: String): Device? = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { socket -> receiveAnswer(socket, InetAddress.getByName(host)) }
        }
        catch (e: IOException) {
            logger.e(TAG, "$host: request failed -> $e")
            null
        }
    }

    /** Sends the request [REQUEST_COUNT] times and reads the answers until [DURATION_MS] pass. */
    private suspend fun collect(socket: DatagramSocket, found: MutableMap<String, Device>) {
        val group = InetAddress.getByName(GROUP)
        val request = DatagramPacket(REQUEST, REQUEST.size, group, PORT)
        val start = SystemClock.elapsedRealtime()
        var sent = 0

        socket.soTimeout = RECEIVE_TIMEOUT_MS
        while (true) {
            currentCoroutineContext().ensureActive()

            val elapsed = SystemClock.elapsedRealtime() - start

            if (elapsed >= DURATION_MS) break

            if (sent < REQUEST_COUNT && elapsed >= sent * REQUEST_INTERVAL_MS) {
                socket.send(request)
                sent++
            }

            val packet = DatagramPacket(ByteArray(ANSWER_SIZE), ANSWER_SIZE)

            try {
                socket.receive(packet)
            }
            catch (e: SocketTimeoutException) {
                continue
            }

            val device = parse(String(packet.data, 0, packet.length, Charsets.US_ASCII))
            val id = device?.id ?: continue

            if (found.put(id, device) == null) {
                logger.i(TAG, "Found $device")
            }
        }
    }

    /** Sends the request to [address] up to [REQUEST_COUNT] times; returns the lamp that answers. */
    private suspend fun receiveAnswer(socket: DatagramSocket, address: InetAddress): Device? {
        val request = DatagramPacket(REQUEST, REQUEST.size, address, PORT)

        socket.soTimeout = ANSWER_TIMEOUT_MS
        for (attempt in 1..REQUEST_COUNT) {
            currentCoroutineContext().ensureActive()
            socket.send(request)

            val packet = DatagramPacket(ByteArray(ANSWER_SIZE), ANSWER_SIZE)

            try {
                socket.receive(packet)
            }
            catch (e: SocketTimeoutException) {
                continue
            }

            val device = parse(String(packet.data, 0, packet.length, Charsets.US_ASCII))

            if (device != null) {
                logger.i(TAG, "${address.hostAddress} answered: $device")
                return device
            }
        }
        return null
    }

    /**
     * Reads a lamp from its answer; null if it is not a lamp's answer or misses a field. Field
     * names are case-insensitive. An answer looks like this:
     * ```
     * HTTP/1.1 200 OK
     * Cache-Control: max-age=3600
     * Date:
     * Ext:
     * Location: yeelight://192.168.1.239:55443
     * Server: POSIX UPnP/1.0 YGLC/1
     * id: 0x000000000015243f
     * model: color
     * fw_ver: 18
     * support: get_prop set_default set_power toggle set_bright start_cf stop_cf set_scene
     * cron_add cron_get cron_del set_ct_abx set_rgb
     * power: on
     * bright: 100
     * color_mode: 2
     * ct: 4000
     * rgb: 16711680
     * hue: 100
     * sat: 35
     * name: my_bulb
     * ```
     */
    private fun parse(answer: String): Device? {
        val fields = answer.lineSequence()
            .filter { ':' in it }
            .associate { line ->
                line.substringBefore(':').trim().lowercase() to line.substringAfter(':').trim()
            }
        val location = fields["location"]?.takeIf { it.startsWith(SCHEME) } ?: return null
        val address = location.removePrefix(SCHEME)
        val port = address.substringAfter(':', "").toIntOrNull() ?: return null
        val id = fields["id"] ?: return null
        val methods = fields["support"].orEmpty().split(' ')

        return Device(
            id = id,
            model = fields["model"].orEmpty(),
            name = fields["name"].orEmpty(),
            host = address.substringBefore(':'),
            port = port,
            musicMode = Commands.SET_MUSIC in methods,
        )
    }

    companion object {
        private const val TAG = "Discovery"
        private const val GROUP = "239.255.255.250" // The lamps' multicast group
        private const val PORT = 1982
        private const val SCHEME = "yeelight://"
        private const val DURATION_MS = 10_000L
        private const val REQUEST_COUNT = 3 // UDP can lose a request, so it is sent again
        private const val REQUEST_INTERVAL_MS = 1_000L
        private const val RECEIVE_TIMEOUT_MS = 300 // How often the loop resends and checks the end
        private const val ANSWER_TIMEOUT_MS = 700 // How long request() waits for each answer
        private const val ANSWER_SIZE = 1024 // Bytes; a lamp's answer is about 500

        private val REQUEST = listOf(
            "M-SEARCH * HTTP/1.1",
            "HOST:$GROUP:$PORT",
            "MAN:\"ssdp:discover\"",
            "ST:wifi_bulb",
        ).joinToString("") { "$it\r\n" }.toByteArray()

        /**
         * Puts the [found] lamps into the [saved] list. A saved lamp is matched by id, or by host
         * if its id is unknown (added by hand); it takes the found id, address, model and name and
         * keeps its place and [Device.used]. Found lamps not matched go to the end, not used.
         * Saved lamps that were not found stay as they are.
         */
        fun merge(saved: List<Device>, found: List<Device>): List<Device> {
            val rest = found.toMutableList() // Found lamps not matched yet
            // By id first, so a lamp added by hand cannot take the found lamp of a known one
            val byId = saved.map { device ->
                val index = rest.indexOfFirst { lamp -> lamp.id == device.id }

                if (index < 0) device else rest.removeAt(index).copy(used = device.used)
            }
            val byHost = byId.map { device ->
                val index = rest.indexOfFirst { lamp ->
                    device.id == null && lamp.host == device.host
                }

                if (index < 0) device else rest.removeAt(index).copy(used = device.used)
            }

            return byHost + rest
        }
    }
}
