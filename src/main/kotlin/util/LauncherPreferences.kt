package util

import java.util.prefs.Preferences

object LauncherPreferences {
    private val prefs = Preferences.userNodeForPackage(LauncherPreferences::class.java)
    private const val KEY_BACKGROUND_ANIMATION_ENABLED = "background_animation_enabled"

    var isBackgroundAnimationEnabled: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND_ANIMATION_ENABLED, true)
        set(value) {
            try {
                prefs.putBoolean(KEY_BACKGROUND_ANIMATION_ENABLED, value)
                prefs.flush()
            } catch (_: Exception) {
                // Ignore storage flush errors
            }
        }
}
