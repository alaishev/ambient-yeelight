package com.ambient.yeelight

import android.app.Application
import com.ambient.yeelight.common.Logger
import java.io.File

/**
 * The application class. Android creates it before any activity or service, so the log and the
 * crash handler are set up here.
 */
class AmbientLightApp : Application() {

    /** The log file. */
    val logFile: File
        get() = File(filesDir, "logs/ambient_light.log")

    override fun onCreate() {
        super.onCreate()
        logFile.parentFile?.mkdirs()

        val level = if (BuildConfig.DEBUG) Logger.Level.DEBUG else Logger.Level.INFO

        logger = Logger(level, path = logFile.path)
        logger.i(TAG, "Starting application v${BuildConfig.VERSION_NAME}")

        // Write uncaught exceptions to the log file too: logcat is cleared quickly. Then pass
        // the exception on to the system handler, which closes the app as usual.
        val system = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            logger.e(TAG, "Crash on thread \"${thread.name}\"", e)
            system?.uncaughtException(thread, e)
        }
    }

    private companion object {
        const val TAG = "AmbientLightApp"
    }
}

/**
 * The shared log of the app: logcat plus [AmbientLightApp.logFile]. Set up in
 * [AmbientLightApp.onCreate], before any other code runs. There must be only one instance, since
 * two loggers would open two writers to the same file. DEBUG messages go only to debug builds.
 */
lateinit var logger: Logger
    private set
