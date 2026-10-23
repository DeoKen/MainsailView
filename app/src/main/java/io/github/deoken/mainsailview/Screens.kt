package io.github.deoken.mainsailview

import android.content.Context
import android.graphics.Point
import android.view.Display
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.json.JSONObject

enum class ScreenRole { MAIN, COVER }

/**
 * Tells a foldable's cover screen from its main screen. Every physical screen the app is
 * shown on is remembered by its pixel size; once two are known, the one with the narrower
 * short side is the cover screen. Phones with one screen only ever see MAIN.
 */
object Screens {
    private const val KEY = "known_screens"

    /** Records the screen [context]'s window is on (or [display] if given) and returns its role. */
    fun current(context: Context, display: Display? = null): ScreenRole {
        val d = display ?: ContextCompat.getDisplayOrDefault(context)
        val size = Point()
        @Suppress("DEPRECATION")
        d.getRealSize(size)
        val key = "${minOf(size.x, size.y)}x${maxOf(size.x, size.y)}"
        val widthDp = context.resources.configuration.screenWidthDp

        val known = load(context)
        if (known[key] != widthDp) {
            known[key] = widthDp
            save(context, known)
        }
        return roleOf(key, known)
    }

    /** Last seen window width (dp) on the screen with [role], or null if not seen yet. */
    fun widthDp(context: Context, role: ScreenRole): Int? {
        val known = load(context)
        return known.keys.firstOrNull { roleOf(it, known) == role }?.let { known[it] }
    }

    private fun roleOf(key: String, known: Map<String, Int>): ScreenRole {
        if (known.size < 2) return ScreenRole.MAIN
        val cover = known.keys.minByOrNull { it.substringBefore('x').toIntOrNull() ?: Int.MAX_VALUE }
        return if (key == cover) ScreenRole.COVER else ScreenRole.MAIN
    }

    private fun load(context: Context): MutableMap<String, Int> {
        val json = PreferenceManager.getDefaultSharedPreferences(context).getString(KEY, null) ?: return mutableMapOf()
        return try {
            val o = JSONObject(json)
            o.keys().asSequence().associateWith { o.getInt(it) }.toMutableMap()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun save(context: Context, known: Map<String, Int>) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(KEY, JSONObject(known as Map<*, *>).toString())
        }
    }
}
