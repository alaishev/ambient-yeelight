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
 * Once the file reaches MAX_SIZE, it becomes the previous file, its name with ".1", in place of
 * the older one, and a new file starts. Each new file has the system info at its top.
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

    /** The log files that exist, the previous one first: for saving or sending them. */
    val files: List<File>
        get() {
            val file = file ?: return emptyList()

            return listOf(previousFile(file), file).filter { it.exists() }
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

    /** Deletes both log files; the next message starts a new one. */
    @Synchronized
    fun clear() {
        val file = file ?: return

        writer?.close()
        writer = null
        previousFile(file).delete()
        file.delete()
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

    /**
     * Writes one entry to the file. A full file becomes the previous one first; a missing or empty
     * file gets the system info at its top.
     */
    @Synchronized
    private fun write(file: File, level: Level, tag: String, message: String, trace: String?) {
        val thread = Thread.currentThread().name.padEnd(28) // Fits "DefaultDispatcher-worker-12"

        try {
            if (file.length() >= MAX_SIZE) {
                writer?.close()
                // Replaces the older previous file; if the rename fails, the history is dropped
                if (!file.renameTo(previousFile(file))) {
                    file.delete()
                }
            }

            if (!file.exists() || file.length() == 0L) {
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

    /** Opens the file for appending. */
    private fun open(file: File) {
        try {
            writer?.close()
            writer = FileOutputStream(file, true).bufferedWriter()
        }
        catch (e: IOException) {
            writer = null
            Log.e(TAG, "Cannot open ${file.absolutePath}", e)
        }
    }

    private fun previousFile(file: File): File = File("${file.path}.1")

    companion object {
        private const val TAG = "Logger"
        const val MAX_SIZE: Long = 2L * 1024 * 1024 // One log file's size limit, bytes

        private fun priority(level: Level): Int = when (level) {
            Level.DEBUG -> Log.DEBUG
            Level.INFO -> Log.INFO
            Level.WARN -> Log.WARN
            Level.ERROR -> Log.ERROR
        }
    }
}
