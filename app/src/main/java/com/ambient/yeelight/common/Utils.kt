package com.ambient.yeelight.common

/** Helpers that do not belong to any class. */
object Utils {
    /** True if [host] is an IPv4 address, like "192.168.1.10". */
    fun isIpV4(host: String): Boolean {
        val regex = Regex(
            "^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)[.]){3}" +
                "(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
        )

        return host.matches(regex)
    }
}
