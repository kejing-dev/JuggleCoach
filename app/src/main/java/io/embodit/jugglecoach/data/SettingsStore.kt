package io.embodit.jugglecoach.data

import android.content.Context
import io.embodit.jugglecoach.domain.model.BodyParams
import io.embodit.jugglecoach.domain.model.DemoSlow
import io.embodit.jugglecoach.domain.model.DwellTime
import io.embodit.jugglecoach.domain.model.LanguagePref
import io.embodit.jugglecoach.domain.model.SetSize
import io.embodit.jugglecoach.domain.model.Settings

/**
 * Settings, per-lesson "last set" counts, the first-run flags and the spike record in SharedPreferences (design
 * `persistence`). The body frame and the progress inside a set are never saved.
 */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("juggle_coach", Context.MODE_PRIVATE)

    fun load(): Settings {
        val d = Settings()
        val b = d.body
        return Settings(
            body =
                BodyParams(
                    shoulder = prefs.getFloat("shoulder", b.shoulder),
                    handDrop = prefs.getFloat("handDrop", b.handDrop),
                    zFront = prefs.getFloat("zFront", b.zFront),
                    peakRise = prefs.getFloat("peakRise", b.peakRise),
                    vt = prefs.getFloat("vt", b.vt),
                ).clamped(),
            demoSlow = enumOr(prefs.getString("demoSlow", null), d.demoSlow),
            setSize = enumOr(prefs.getString("setSize", null), d.setSize),
            muted = prefs.getBoolean("muted", d.muted),
            dwell = enumOr(prefs.getString("dwell", null), d.dwell),
            autoDemo = prefs.getBoolean("autoDemo", d.autoDemo),
            language = enumOr(prefs.getString("language", null), d.language),
        )
    }

    fun save(s: Settings) {
        prefs.edit()
            .putFloat("shoulder", s.body.shoulder)
            .putFloat("handDrop", s.body.handDrop)
            .putFloat("zFront", s.body.zFront)
            .putFloat("peakRise", s.body.peakRise)
            .putFloat("vt", s.body.vt)
            .putString("demoSlow", s.demoSlow.name)
            .putString("setSize", s.setSize.name)
            .putBoolean("muted", s.muted)
            .putString("dwell", s.dwell.name)
            .putBoolean("autoDemo", s.autoDemo)
            .putString("language", s.language.name)
            .apply()
    }

    var welcomed: Boolean
        get() = prefs.getBoolean("welcomed", false)
        set(value) = prefs.edit().putBoolean("welcomed", value).apply()

    /** Lessons whose first demo (with the legend line) has been shown. */
    var legendSeen: Set<Int>
        get() = prefs.getStringSet("legendSeen", emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
        set(value) = prefs.edit().putStringSet("legendSeen", value.map { it.toString() }.toSet()).apply()

    /** Lessons whose demo has played at least once (auto demo only the first time). */
    var demoSeen: Set<Int>
        get() = prefs.getStringSet("demoSeen", emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
        set(value) = prefs.edit().putStringSet("demoSeen", value.map { it.toString() }.toSet()).apply()

    fun lastSet(course: Int): Int = prefs.getInt("last_$course", 0)

    fun setLastSet(course: Int, n: Int) = prefs.edit().putInt("last_$course", n).apply()

    var eyeAsked: Boolean
        get() = prefs.getBoolean("eyeAsked", false)
        set(value) = prefs.edit().putBoolean("eyeAsked", value).apply()

    var eyePermanent: Boolean
        get() = prefs.getBoolean("eyePermanent", false)
        set(value) = prefs.edit().putBoolean("eyePermanent", value).apply()

    fun saveSpike(line: String) = prefs.edit().putString("spike", line).apply()

    /** Clears every saved thing (debug `clear`). */
    fun clear() = prefs.edit().clear().apply()

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: fallback
}
