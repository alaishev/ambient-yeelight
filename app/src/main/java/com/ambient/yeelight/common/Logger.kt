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
 * The app's log. Every message goes to the log file, and to logcat if it's on.
 *
 * The file grows up to MAX_FILE_SIZE. Then it's renamed to "<name>.1" (the old ".1" file is
 * replaced) and a new file starts, so the log keeps the latest 2 to 4 MB. Each new file begins
 * with a few lines about the device and the app version.
 *
 * Can be used from any thread.
 *
 * @param minLevel messages below this level are skipped
 * @param logcat also write the messages to logcat
 * @param file where the log is written
 */
class Logger(
    private val minLevel: Level,
    private val logcat: Boolean,
    private val file: File,
) {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    private val previousFile = File("${file.path}.1")
    private val timeFormat = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss.SSS", Locale.US)
    private var writer: BufferedWriter? = null // Null if the file can't be opened

    // Written at the top of every new log file
    private val systemInfo = buildString {
        appendLine("OS Version: ${System.getProperty("os.version")} (${Build.VERSION.INCREMENTAL})")
        appendLine("OS API Level: ${Build.VERSION.SDK_INT}")
        appendLine("Device: ${Build.DEVICE}")
        appendLine("Model (and Product): ${Build.MODEL} (${Build.PRODUCT})")
        append("App version: ${BuildConfig.VERSION_NAME}")
    }

    /** The log files that exist, the older one first. For saving or sending the log. */
    val files: List<File>
        get() = listOf(previousFile, file).filter { it.exists() }

    init {
        open()
    }

    /** Logs a DEBUG message. Like the other levels, it's skipped if it's below minLevel. */
    fun d(tag: String, message: String) {
        log(Level.DEBUG, tag, message, null)
    }

    /** Logs an INFO message. */
    fun i(tag: String, message: String) {
        log(Level.INFO, tag, message, null)
    }

    /** Logs a WARN message, with the stack trace of throwable if there is one. */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        log(Level.WARN, tag, message, throwable)
    }

    /** Logs an ERROR message, with the stack trace of throwable if there is one. */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        log(Level.ERROR, tag, message, throwable)
    }

    /** Deletes both log files. The next message starts a new file. */
    @Synchronized
    fun clear() {
        writer?.close()
        writer = null
        previousFile.delete()
        file.delete()
    }

    private fun log(level: Level, tag: String, message: String, throwable: Throwable?) {
        if (level < minLevel) return

        val trace = throwable?.stackTraceToString()

        if (logcat) {
            Log.println(toPriority(level), tag, if (trace == null) message else "$message\n$trace")
        }
        write(level, tag, message, trace)
    }

    /**
     * Writes one message to the file. A full file becomes the previous one first. A new file gets
     * the system info at its top.
     */
    @Synchronized
    private fun write(level: Level, tag: String, message: String, trace: String?) {
        // Padded, so the messages line up; our longest thread name is "DefaultDispatcher-worker-12"
        val thread = Thread.currentThread().name.padEnd(28)

        try {
            if (file.length() >= MAX_FILE_SIZE) {
                writer?.close()
                // Replaces the old previous file. If renaming fails, the full file is just dropped
                if (!file.renameTo(previousFile)) {
                    file.delete()
                }
            }

            if (!file.exists() || file.length() == 0L) {
                open()
                writer?.apply {
                    appendLine(systemInfo)
                }
            }

            writer?.apply {
                val time = timeFormat.format(LocalDateTime.now())

                appendLine("$time $thread ${level.name.first()}/$tag: $message")
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

    /** Opens the file to add to its end. */
    private fun open() {
        try {
            writer?.close()
            writer = FileOutputStream(file, true).bufferedWriter()
        }
        catch (e: IOException) {
            writer = null
            Log.e(TAG, "Cannot open ${file.absolutePath}", e)
        }
    }

    private fun toPriority(level: Level): Int = when (level) {
        Level.DEBUG -> Log.DEBUG
        Level.INFO -> Log.INFO
        Level.WARN -> Log.WARN
        Level.ERROR -> Log.ERROR
    }

    private companion object {
        const val TAG = "Logger"
        const val MAX_FILE_SIZE = 2L * 1024 * 1024 // Bytes
    }
}
