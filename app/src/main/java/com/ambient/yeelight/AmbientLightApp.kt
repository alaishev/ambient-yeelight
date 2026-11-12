package com.ambient.yeelight

import android.app.Application
import com.ambient.yeelight.common.Logger
import java.io.File

/**
 * The app itself. Android creates it before any screen or service, so this is the place to set
 * up the log and to catch crashes.
 */
class AmbientLightApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val logFolder = File(filesDir, "logs")
        val level = if (BuildConfig.DEBUG) Logger.Level.DEBUG else Logger.Level.INFO

        logFolder.mkdirs()
        logger = Logger(level, logcat = BuildConfig.DEBUG, File(logFolder, "ambient_light.log"))
        logger.i(TAG, "Starting application v${BuildConfig.VERSION_NAME}")

        // Write a crash to the log file too, since logcat is cleared quickly. Then hand the crash
        // on to the system handler, which closes the app as usual.
        val systemHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            logger.e(TAG, "Crash on thread \"${thread.name}\"", e)
            systemHandler?.uncaughtException(thread, e)
        }
    }

    private companion object {
        const val TAG = "AmbientLightApp"
    }
}

/**
 * The app's log, shared by all the code. AmbientLightApp.onCreate() sets it up before anything
 * else runs. There must be only one, because two loggers would write to the same file at once.
 * DEBUG messages and logcat are on only in debug builds.
 */
lateinit var logger: Logger
    private set
