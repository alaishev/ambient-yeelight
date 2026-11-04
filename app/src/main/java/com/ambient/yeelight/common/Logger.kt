package com.ambient.yeelight.common

import android.os.Build
import android.util.Log
import com.ambient.yeelight.BuildConfig
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * @author Artem R. Laishev
 * Logger class, allows logging to internal log file
 * @param fullPath full path to log file (directory and file name)
 */
class Logger(fullPath: String) : ILogger {
    private val mDateFormat = SimpleDateFormat("dd-MM-yyyy HH:mm:ss.SSS", Locale.US)
    private var mSysInfo: String
    private val mLogFile: File
    private var mFileWriter: FileWriter? = null
    private var mBufferedWriter: BufferedWriter? = null

    /**
     * Initialization
     */
    init {
        mSysInfo = "OS Version: " + System.getProperty("os.version") + "(" + Build.VERSION.INCREMENTAL + ")"
        mSysInfo += "\nOS API Level: ${Build.VERSION.SDK_INT}"
        mSysInfo += "\nDevice: ${Build.DEVICE}"
        mSysInfo += "\nModel (and Product): ${Build.MODEL} (${Build.PRODUCT})"
        mSysInfo += "\nJbagent version: ${BuildConfig.VERSION_NAME}"

        mLogFile = File(fullPath)
        createOrRewindLogFile()
    }

    /**
     * Synchronized write message to log file
     * @param level message level (See constants above)
     * @param tag   class tag
     * @param msg   message for writing
     */
    @Synchronized
    private fun writeMsg(level: String, tag: String, msg: String) {
        val thdId = Utils.padName(Thread.currentThread().name, 32)
        logToLogcat(thdId, msg)

        try {
            // Create new file or rewind and print system info
            if (!mLogFile.exists() || mLogFile.length() >= LOG_SIZE || mLogFile.length() == 0L) {
                createOrRewindLogFile()
                mBufferedWriter?.append(mSysInfo)
                mBufferedWriter?.newLine()
            }

            // Get timestamp of message
            val fDate = mDateFormat.format(Calendar.getInstance().time)
            val str = String.format(Locale.US, "%s %s %s/%s: %s", fDate, thdId, level, tag, msg)
            mBufferedWriter?.append(str)
            mBufferedWriter?.newLine()
            mBufferedWriter?.flush()
        }
        catch (e: IOException) {
            e.printStackTrace()
        }
    }

    /**
     * Write message to logcat with debug information (thread id, function name, line number)
     * @param thdId current thread id
     * @param msg   message to write
     */
    private fun logToLogcat(thdId: String, msg: String) {
        if (!BuildConfig.DEBUG)
            return

        val stackDepth = 4
        val fullClassName = Thread.currentThread().stackTrace[stackDepth].className
        var className = fullClassName.substring(fullClassName.lastIndexOf(".") + 1)
        var methodName = Thread.currentThread().stackTrace[stackDepth].methodName

        // Without these lines android logcat cannot show stack inside lambdas
        if (className.contains("$"))
            className = className.substring(className.indexOf("$") + 1)

        if (methodName.contains("$")) {
            methodName = methodName.substring(methodName.indexOf("$") + 1)
            methodName = methodName.substring(0, methodName.indexOf("$"))
        }

        val lineNumber = Thread.currentThread().stackTrace[stackDepth].lineNumber
        val tag = String.format(Locale.US, "[%s\\%s:%d]", className, methodName, lineNumber)

        Log.d("$thdId $tag", msg)
    }

    /**
     * Create new File and FileWriter for writing logs
     */
    private fun createOrRewindLogFile() {
        try {
            if (!mLogFile.createNewFile()) {
                val msg = String.format(Locale.US, "Log file '%s' is already exists", mLogFile.absoluteFile.absolutePath)
                if (BuildConfig.DEBUG)
                    Log.d(TAG, msg)
            }

            mBufferedWriter?.close()
            mFileWriter?.close()

            mFileWriter = FileWriter(mLogFile, mLogFile.length() < LOG_SIZE)
            mBufferedWriter = BufferedWriter(mFileWriter)
        }
        catch (e: IOException) {
            e.printStackTrace()
        }
    }

    /**
     * Interface function for logging debug messages with format
     * @param tag    class tag
     * @param format format of message
     * @param args   variable arguments for format
     */
    override fun logD(tag: String, format: String, vararg args: Any) {
        if (!BuildConfig.DEBUG)
            return

        val msg = String.format(Locale.US, format, *args)
        writeMsg(DEBUG, tag, msg)
    }

    /**
     * Interface function for logging info messages with format
     * @param tag    class tag
     * @param format format of message
     * @param args   variable arguments for format
     */
    override fun logI(tag: String, format: String, vararg args: Any) {
        val msg = String.format(Locale.US, format, *args)
        writeMsg(INFO, tag, msg)
    }

    /**
     * Interface function for logging warning messages with format
     * @param tag    class tag
     * @param format format of message
     * @param args   variable arguments for format
     */
    override fun logW(tag: String, format: String, vararg args: Any) {
        val msg = String.format(Locale.US, format, *args)
        writeMsg(WARN, tag, msg)
    }

    /**
     * Interface function for logging error messages with format
     * @param tag    class tag
     * @param format format of message
     * @param args   variable arguments for format
     */
    override fun logE(tag: String, format: String, vararg args: Any) {
        val msg = String.format(Locale.US, format, *args)
        writeMsg(ERROR, tag, msg)
    }

    companion object {
        private const val TAG = "Logger"

        // Message levels
        private const val DEBUG = "D"
        private const val INFO = "I"
        private const val WARN = "W"
        private const val ERROR = "E"

        const val LOG_SIZE = (10 * 1024 * 1024).toLong() // Maximum log file size
    }
}