package com.ambient.yeelight.common

import java.math.BigInteger

/**
 * @author Artem R. Laishev
 * Utils class
 * Contains useful methods which cannot be included to other classes
 */
object Utils {
    // Timeouts
    const val CTRL_RETRY_INTERVAL = 5 * 1000 // in Milliseconds
    const val THD_WAIT_TIMEOUT = 2 * 1000 // in Milliseconds

    /**
     * Swap nibbles of octet high <-> low
     * @param buf target buf
     * @param len length of buffer
     */
    @JvmStatic
    fun swapNibbles(buf: ByteArray, len: Int) {
        // In java we don`t have unsigned types, so we use int array for swapping bits
        val unsignedBuf = IntArray(len)

        for (i in 0 until len) {
            unsignedBuf[i] = buf[i].toInt() and 0xFF
            unsignedBuf[i] = (unsignedBuf[i] shr 4 or (unsignedBuf[i] shl 4)).toByte().toInt()
            buf[i] = unsignedBuf[i].toByte()
        }
    }

    /**
     * Sleep current thread with possible interrupt
     * @param timeout timeout of waiting in milliseconds
     * @return true if sleeping was interrupted, false otherwise
     */
    @JvmStatic
    fun interruptedWait(timeout: Int): Boolean {
        return try {
            Thread.sleep(timeout.toLong())
            false
        }
        catch (ignored: InterruptedException) {
            true
        }
    }

    /**
     * Convert two bytes from byte buffer to short value
     * @param buf    The bytes to be decoded into short
     * @param offset The index of the first byte to decode
     * @return decoded short value
     */
    fun getUShort(buf: ByteArray, offset: Int): UShort {
        var len = buf[offset].toInt() and 0xFF
        len = len shl 8 or (buf[offset + 1].toInt() and 0xFF)
        return len.toUShort()
    }

    /**
     * Convert four bytes from byte buffer to int value
     * @param buf    The bytes to be decoded into int
     * @param offset The index of the first byte to decode
     * @return decoded int value
     */
    @JvmStatic
    fun getInt(buf: ByteArray, offset: Int): Int {
        return buf[offset].toInt() shl 24 or (
                buf[offset + 1].toInt() and 0xFF shl 16) or (
                buf[offset + 2].toInt() and 0xFF shl 8) or
                (buf[offset + 3].toInt() and 0xFF)
    }

    /**
     * Convert IPv4 address from byte buffer to string representation
     * @param buf    byte array with ipv4 address
     * @param offset The index of the first byte to decode
     * @return string representation of IPv4
     */
    fun iPv4FromByteArray(buf: ByteArray, offset: Int): String {
        val addressStr = StringBuilder()
        for (i in offset until offset + 4) {
            val t = 0xFF and buf[i].toInt()
            addressStr.append(".").append(t)
        }
        return addressStr.substring(1)
    }

    /**
     * Convert IPv6 address from byte buffer to string representation
     * @param buf    byte array with ipv6 address
     * @param offset The index of the first byte to decode
     * @return string representation of IPv6
     */
    fun iPv6FromByteArray(buf: ByteArray, offset: Int): String {
        val addressStr = StringBuilder()
        for (i in offset until offset + 16 step 2) {
            val t = getUShort(buf, i).toInt()
            addressStr.append(":").append(Integer.toHexString(t))
        }
        return addressStr.substring(1)
    }

    /**
     * Convert id from byte array to String
     * @param buf byte array with id
     * @return converted String id
     */
    @JvmStatic
    fun sidHexStr(buf: ByteArray?): String {
        // Convert big endian to little endian
        val bigReqId = BigInteger(1, buf)
        return bigReqId.toString(16)
        //return str.substring(0, 8) + '-' +
        //        str.substring(8, 12) +
        //        '-' +
        //        str.substring(12, 16) +
        //        '-' +
        //        str.substring(16, 20) +
        //        '-' +
        //        str.substring(20, 32);
    }

    /**
     * Padding string according to requested padding length
     * @param name string to be padded
     * @param padding length to padding
     * @return padded name to specified len
     */
    @JvmStatic
    fun padName(name: String, padding: Int): String {
        val pad = StringBuilder()
        val len = padding - name.length
        while (pad.length < len) {
            pad.append(' ')
        }

        return name + pad.toString()
    }

    /**
     * Check if host is IPv4 address
     * @param host Host to be checked
     * @return status of checking
     */
    @JvmStatic
    fun isIpV4(host: String): Boolean {
        //"([0-9]{1,3}[\.]){3}[0-9]{1,3}"
        val regex = Regex("^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)[.]){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$")
        return host.matches(regex)
    }

    /**
     * Check if host is IPv6 address
     * @param host Host to be checked
     * @return status of checking
     */
    @JvmStatic
    fun isIpV6(host: String): Boolean {
        val regex = Regex("((^|:)([0-9a-fA-F]{0,4})){1,8}")
        return host.matches(regex)
    }
}