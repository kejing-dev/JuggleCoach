package io.embodit.jugglecoach.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.jugglecoach.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        JcGraph.appContext = applicationContext
        launch(::mainApp)
    }
}
