package com.ambient.yeelight.common

import android.os.Build
import android.util.Log
import com.ambient.yeelight.BuildConfig
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The app's logger; writes to logcat, a file, or both.
 *
 * The file is started anew once it reaches [MAX_SIZE], with the system info at its top.
 *
 * @param minLevel messages below it are dropped
 * @param logcat write to logcat
 * @param path the log file; null for no file
 */
class Logger(
    private val minLevel: Level = Level.INFO,
    private val logcat: Boolean = true,
    path: String? = null,
) {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    private val file = path?.let(::File)
    private val dateFormat = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss.SSS", Locale.US)
    private var writer: BufferedWriter? = null // Null if there is no file or it cannot be opened

    private val sysInfo = buildString { // Written at the top of every new log file
        appendLine("OS Version: ${System.getProperty("os.version")} (${Build.VERSION.INCREMENTAL})")
        appendLine("OS API Level: ${Build.VERSION.SDK_INT}")
        appendLine("Device: ${Build.DEVICE}")
        appendLine("Model (and Product): ${Build.MODEL} (${Build.PRODUCT})")
        append("App version: ${BuildConfig.VERSION_NAME}")
    }

    init {
        if (file != null) {
            open(file)
        }
    }

    /** Logs a DEBUG message. Like [i], [w] and [e], drops it if it is below `minLevel`. */
    fun d(tag: String, message: String) {
        log(Level.DEBUG, tag, message, null)
    }

    /** Logs an INFO message. */
    fun i(tag: String, message: String) {
        log(Level.INFO, tag, message, null)
    }

    /** Logs a WARN message, with [throwable]'s stack trace if there is one. */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        log(Level.WARN, tag, message, throwable)
    }

    /** Logs an ERROR message, with [throwable]'s stack trace if there is one. */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        log(Level.ERROR, tag, message, throwable)
    }

    private fun log(level: Level, tag: String, message: String, throwable: Throwable?) {
        if (level < minLevel) return

        val trace = throwable?.stackTraceToString()

        if (logcat) {
            Log.println(priority(level), tag, if (trace == null) message else "$message\n$trace")
        }

        if (file != null) {
            write(file, level, tag, message, trace)
        }
    }

    /** Writes one entry to the file; starts a new file first if it is missing, empty or too big. */
    @Synchronized
    private fun write(file: File, level: Level, tag: String, message: String, trace: String?) {
        val thread = Thread.currentThread().name.padEnd(28) // Fits "DefaultDispatcher-worker-12"

        try {
            if (!file.exists() || file.length() == 0L || file.length() >= MAX_SIZE) {
                open(file)
                writer?.apply {
                    append(sysInfo)
                    newLine()
                }
            }

            writer?.apply {
                val time = dateFormat.format(LocalDateTime.now())

                append("$time $thread ${level.name.first()}/$tag: $message")
                newLine()
                if (trace != null) {
                    append(trace)
                }
                flush()
            }
        }
        catch (e: IOException) {
            Log.e(TAG, "Cannot write to ${file.absolutePath}", e)
        }
    }

    /** Opens the file for appending, or truncates it if it is too big. */
    private fun open(file: File) {
        try {
            writer?.close()
            writer = FileOutputStream(file, file.length() < MAX_SIZE).bufferedWriter()
        }
        catch (e: IOException) {
            writer = null
            Log.e(TAG, "Cannot open ${file.absolutePath}", e)
        }
    }

    companion object {
        private const val TAG = "Logger"
        const val MAX_SIZE: Long = 10L * 1024 * 1024 // The log file's size limit, bytes

        private fun priority(level: Level): Int = when (level) {
            Level.DEBUG -> Log.DEBUG
            Level.INFO -> Log.INFO
            Level.WARN -> Log.WARN
            Level.ERROR -> Log.ERROR
        }
    }
}
