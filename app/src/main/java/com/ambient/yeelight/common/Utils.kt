package com.ambient.yeelight.common

/** Small helpers that don't belong to any class. */
object Utils {
    private val IP_V4 = Regex(
        "^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)[.]){3}" +
            "(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
    )

    /** True if host is an IPv4 address like "192.168.1.10". */
    fun isIpV4(host: String): Boolean = host.matches(IP_V4)
}
