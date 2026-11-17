package org.ledfxmobile.app

import android.app.Application
import org.ledfxmobile.app.engine.LightEngine

class LedApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LightEngine.init(this)
    }
}
