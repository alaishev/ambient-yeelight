package com.ambient.yeelight

import android.app.Application
import android.content.Context
import com.ambient.yeelight.common.ILogger
import com.ambient.yeelight.common.Logger

class AmbientLightApp : Application() {

    override fun onCreate() {
        super.onCreate()

        mContext = this

        mLogger = Logger("${filesDir!!.path}/$FILE_NAME")
        mLogger.logI(TAG, "Starting Application v${BuildConfig.VERSION_NAME}")
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
    }

    companion object {
        fun getAppContext(): AmbientLightApp {
            return mContext
        }

        fun getAppLogger(): ILogger {
            return mLogger
        }

        private lateinit var mContext: AmbientLightApp
        private lateinit var mLogger: ILogger

        const val TAG = "AmbientLightApp"
        const val FILE_NAME = "ambient_light.log"
    }
}
