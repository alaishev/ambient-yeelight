package com.ambient.yeelight.common

interface ILogger {
    /**
     * Interface function for logging debug messages with format
     * @param tag    class tag
     * @param format format of message
     * @param args   variable arguments for format
     */
    fun logD(tag: String, format: String, vararg args: Any)

    /**
     * Interface function for logging info messages with format
     * @param tag    class tag
     * @param format format of message
     * @param args   variable arguments for format
     */
    fun logI(tag: String, format: String, vararg args: Any)

    /**
     * Interface function for logging warning messages with format
     * @param tag    class tag
     * @param format format of message
     * @param args   variable arguments for format
     */
    fun logW(tag: String, format: String, vararg args: Any)

    /**
     * Interface function for logging error messages with format
     * @param tag    class tag
     * @param format format of message
     * @param args   variable arguments for format
     */
    fun logE(tag: String, format: String, vararg args: Any)
}