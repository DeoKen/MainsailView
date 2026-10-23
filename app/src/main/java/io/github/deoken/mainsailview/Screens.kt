package io.github.deoken.mainsailview

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.launch

enum class ScreenRole { MAIN, COVER }

/**
 * Tells a foldable's cover screen from its main screen by the fold itself: Jetpack
 * WindowManager reports a FoldingFeature only while the app's window spans the hinge,
 * which on a Flip happens on the main screen and never on the cover screen. Screen sizes
 * proved unreliable for this (Samsung reports cover-screen sizes that don't match).
 */
object Screens {
    private const val KEY_FOLD_SEEN = "fold_seen"

    /** The screen the app is on, as last reported by any watching activity. */
    @Volatile
    var role = ScreenRole.MAIN
        private set

    /** Whether the window currently spans the fold, for display in Settings. */
    var foldVisible = false
        private set

    /**
     * Follows the fold while [activity] is started, updating [role] and calling [onChange]
     * on every report (the first arrives right after the activity starts).
     */
    fun watch(activity: ComponentActivity, onChange: (ScreenRole) -> Unit) {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { info ->
                    foldVisible = info.displayFeatures.any { it is FoldingFeature }
                    if (foldVisible) {
                        PreferenceManager.getDefaultSharedPreferences(activity).edit { putBoolean(KEY_FOLD_SEEN, true) }
                    }
                    role = if (!foldVisible && isFoldable(activity)) ScreenRole.COVER else ScreenRole.MAIN
                    PreferenceManager.getDefaultSharedPreferences(activity).edit {
                        putInt(widthKey(role), activity.resources.configuration.screenWidthDp)
                    }
                    onChange(role)
                }
            }
        }
    }

    /** A hinge sensor, or a fold seen at least once, marks the phone as a foldable. */
    fun isFoldable(context: Context): Boolean {
        val hinge = Build.VERSION.SDK_INT >= 30 &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE)
        return hinge || PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_FOLD_SEEN, false)
    }

    /** Last seen window width (dp) on the screen with [role], or null if not seen yet. */
    fun widthDp(context: Context, role: ScreenRole): Int? {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        return if (sp.contains(widthKey(role))) sp.getInt(widthKey(role), 0) else null
    }

    private fun widthKey(role: ScreenRole) = "screen_width_dp_${role.name.lowercase()}"
}
