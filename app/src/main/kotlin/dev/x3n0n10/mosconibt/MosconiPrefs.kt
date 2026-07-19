package dev.x3n0n10.mosconibt

import android.content.Context
import dev.x3n0n10.mosconibt.protocol.MosconiProtocol
import kotlin.reflect.KProperty

/** Whether the app follows the system light/dark setting or overrides it. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Tiny SharedPreferences-backed store for "last known" control positions, mirroring
 * the factory app's use of App Inventor's TinyDB to restore slider positions on launch.
 */
class MosconiPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mosconi_controls", Context.MODE_PRIVATE)

    var volumeTarget: MosconiProtocol.VolumeTarget
        get() = if (prefs.getBoolean(KEY_VOLUME_IS_INPUT, false)) {
            MosconiProtocol.VolumeTarget.INPUT
        } else {
            MosconiProtocol.VolumeTarget.OUTPUT
        }
        set(value) = prefs.edit().putBoolean(KEY_VOLUME_IS_INPUT, value == MosconiProtocol.VolumeTarget.INPUT).apply()

    var volumeStep: Int by IntPref(KEY_VOLUME_STEP, MosconiProtocol.VOLUME_STEPS / 2)
    var subLevel: Int by IntPref(KEY_SUB, MosconiProtocol.SUB_STEPS)
    var balance: Int by IntPref(KEY_BALANCE, MosconiProtocol.BALANCE_FADER_STEPS / 2)
    var fader: Int by IntPref(KEY_FADER, MosconiProtocol.BALANCE_FADER_STEPS / 2)
    var treble: Int by IntPref(KEY_TREBLE, 8)
    var mid: Int by IntPref(KEY_MID, 8)
    var bass: Int by IntPref(KEY_BASS, 8)
    var selectedPreset: Int by IntPref(KEY_PRESET, 0)
    var hapticFeedback: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTIC, value).apply()
    var themeMode: ThemeMode
        get() = ThemeMode.entries.getOrElse(prefs.getInt(KEY_THEME_MODE, ThemeMode.SYSTEM.ordinal)) { ThemeMode.SYSTEM }
        set(value) = prefs.edit().putInt(KEY_THEME_MODE, value.ordinal).apply()
    var lastDeviceAddress: String?
        get() = prefs.getString(KEY_LAST_DEVICE, null)
        set(value) = prefs.edit().putString(KEY_LAST_DEVICE, value).apply()

    /** Local-only safety gate mirroring the factory app's "Enable Preset" toggles -
     *  purely a UI guard against activating an unprogrammed preset slot, never sent
     *  to the DSP. Defaults to enabled so a fresh install behaves like every preset
     *  is usable, matching the factory app's default. */
    fun isPresetEnabled(index: Int): Boolean = prefs.getBoolean(presetEnabledKey(index), true)

    fun setPresetEnabled(index: Int, enabled: Boolean) =
        prefs.edit().putBoolean(presetEnabledKey(index), enabled).apply()

    private fun presetEnabledKey(index: Int) = "preset_enabled_$index"

    private inner class IntPref(private val key: String, private val default: Int) {
        operator fun getValue(thisRef: Any?, property: KProperty<*>): Int = prefs.getInt(key, default)
        operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            prefs.edit().putInt(key, value).apply()
        }
    }

    private companion object {
        const val KEY_VOLUME_IS_INPUT = "volume_is_input"
        const val KEY_VOLUME_STEP = "volume_step"
        const val KEY_SUB = "sub"
        const val KEY_BALANCE = "geo_x"
        const val KEY_FADER = "geo_y"
        const val KEY_TREBLE = "treble"
        const val KEY_MID = "mid"
        const val KEY_BASS = "bass"
        const val KEY_PRESET = "preset"
        const val KEY_HAPTIC = "haptic"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_LAST_DEVICE = "last_device_address"
    }
}
