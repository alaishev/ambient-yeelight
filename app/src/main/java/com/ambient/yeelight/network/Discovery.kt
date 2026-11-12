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
 * Finds Yeelight lamps in the local network. Sends an SSDP M-SEARCH to the lamps' multicast group
 * and collects the answers. The lamps answer straight to our socket, so no multicast lock is
 * needed. request() asks one lamp the same way, at its address.
 */
object Discovery {
    private const val TAG = "Discovery"
    private const val GROUP = "239.255.255.250" // The lamps' multicast group
    private const val PORT = 1982
    private const val SCHEME = "yeelight://"
    private const val DURATION_MS = 10_000L
    private const val REQUEST_COUNT = 3 // UDP may lose a request, so we send it a few times
    private const val REQUEST_INTERVAL_MS = 1_000L
    private const val RECEIVE_TIMEOUT_MS = 300 // How often the loop wakes up to resend or stop
    private const val ANSWER_TIMEOUT_MS = 700 // How long request() waits for each answer
    private const val ANSWER_SIZE = 1024 // Bytes; a lamp's answer is about 500

    private val REQUEST = listOf(
        "M-SEARCH * HTTP/1.1",
        "HOST:$GROUP:$PORT",
        "MAN:\"ssdp:discover\"",
        "ST:wifi_bulb",
    ).joinToString("") { "$it\r\n" }.toByteArray()

    /**
     * Searches for DURATION_MS and returns the lamps that answered, each one once. Runs on the IO
     * dispatcher. Cancelling the coroutine stops the search within RECEIVE_TIMEOUT_MS.
     */
    suspend fun search(): List<Device> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, Device>() // By id: a lamp answers each of our requests

        try {
            DatagramSocket().use { socket -> collect(socket, found) }
        }
        catch (e: IOException) {
            logger.e(TAG, "Search failed -> $e")
        }

        found.values.toList()
    }

    /**
     * Asks the lamp at this address directly, for a lamp the search doesn't reach: a router may
     * drop multicast but still pass a packet to one address. The lamp answers the same way as to
     * the search. Returns null if there's no answer within about
     * REQUEST_COUNT × ANSWER_TIMEOUT_MS. Runs on the IO dispatcher.
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

    /** Sends the request REQUEST_COUNT times and reads the answers until DURATION_MS pass. */
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

            // Not a lamp's answer if null. A found lamp always has an id, so its key is the id
            val device = parse(String(packet.data, 0, packet.length, Charsets.US_ASCII)) ?: continue

            if (device.key !in found) {
                logger.i(TAG, "Found $device")
            }
            found[device.key] = device
        }
    }

    /** Asks this address up to REQUEST_COUNT times and returns the lamp that answers. */
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
     * Reads a lamp from its answer; null if it's not a lamp's answer or a field is missing. Field
     * names may come in any case. An answer looks like this:
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
        val fields = mutableMapOf<String, String>() // Field names in lower case

        for (line in answer.lines()) {
            if (':' !in line) continue

            fields[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
        }

        val location = fields["location"] ?: return null
        val id = fields["id"] ?: return null

        if (!location.startsWith(SCHEME)) return null

        val address = location.removePrefix(SCHEME) // "192.168.1.239:55443"
        val port = address.substringAfter(':', "").toIntOrNull() ?: return null
        val methods = fields["support"].orEmpty().split(' ')

        return Device(
            id = id,
            model = fields["model"].orEmpty(),
            name = fields["name"].orEmpty(),
            host = address.substringBefore(':'),
            port = port,
            supportsMusicMode = Commands.SET_MUSIC in methods,
        )
    }

    /**
     * Puts the found lamps into the saved list. A saved lamp is matched by its id, or by its
     * address if it has no id yet (added by hand). A matched lamp gets the found id, address,
     * model and name, but keeps its place in the list and whether it's enabled. Found lamps
     * with no match go to the end, disabled. Saved lamps that weren't found stay as they are.
     */
    fun merge(saved: List<Device>, found: List<Device>): List<Device> {
        val merged = saved.toMutableList()
        val rest = found.toMutableList() // Found lamps that aren't matched yet

        // By id first, so a lamp added by hand can't take the found lamp of a known one
        for (index in merged.indices) {
            val device = merged[index]
            val lamp = rest.find { it.id != null && it.id == device.id } ?: continue

            merged[index] = lamp.copy(enabled = device.enabled)
            rest.remove(lamp)
        }
        // Then by address, for lamps added by hand: they have no id yet
        for (index in merged.indices) {
            val device = merged[index]

            if (device.id != null) continue

            val lamp = rest.find { it.host == device.host } ?: continue

            merged[index] = lamp.copy(enabled = device.enabled)
            rest.remove(lamp)
        }
        return merged + rest
    }
}
