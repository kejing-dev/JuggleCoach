package io.embodit.jugglecoach.platform

import android.content.Context
import io.embodit.jugglecoach.data.SettingsStore
import io.embodit.jugglecoach.platform.audio.BeatEngine
import io.embodit.jugglecoach.platform.tracking.EyeTracker
import io.embodit.jugglecoach.platform.tracking.HeadTracker

/**
 * Process-wide wiring of the platform services. The scene starts and stops the trackers and the audio stream (every
 * start has a stop: see `JcScene.attach` / `detach` / `onBackground`); the ViewModel only sees state.
 */
object JcGraph {
    val head = HeadTracker()
    val eye = EyeTracker()
    val audio = BeatEngine()

    @Volatile var appContext: Context? = null

    @Volatile private var store: SettingsStore? = null

    fun settings(context: Context): SettingsStore =
        store ?: synchronized(this) { store ?: SettingsStore(context).also { store = it } }
}
