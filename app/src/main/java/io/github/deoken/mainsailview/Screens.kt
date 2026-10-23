package io.github.deoken.mainsailview

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.json.JSONObject

enum class ScreenRole { MAIN, COVER }

/**
 * Tells a foldable's cover screen from its main screen.
 *
 * Each screen is identified by the largest window the app can have on it right now
 * (WindowManager.maximumWindowMetrics), which follows the window to whichever screen it is
 * on, unlike Display sizes that can stay stale after a fold. The biggest screen seen is the
 * main screen; any screen whose short side is clearly smaller than that is the cover screen.
 */
object Screens {
    // v2: sizes recorded by the earlier Display-based detection can be wrong, so start over.
    private const val KEY = "known_screens_v2"
    private const val COVER_RATIO = 0.9

    // Phone main screens are well over this; Flip-style cover screens are well under it.
    private const val COVER_MAX_INCHES = 5.0

    data class Info(val role: ScreenRole, val shortPx: Int, val longPx: Int, val inches: Double, val displayId: Int)

    private data class Seen(val shortPx: Int, val widthDp: Int, val inches: Double)

    /** Records the screen [context] (an Activity) is on and returns what it is. */
    fun current(context: Context): Info {
        val (shortPx, longPx) = windowSize(context)
        val metrics = context.resources.displayMetrics
        val inches = if (metrics.xdpi > 50 && metrics.ydpi > 50) {
            Math.hypot(shortPx / metrics.xdpi.toDouble(), longPx / metrics.ydpi.toDouble())
        } else {
            0.0
        }
        val displayId = ContextCompat.getDisplayOrDefault(context).displayId
        val key = "${shortPx}x$longPx"
        val seen = Seen(shortPx, context.resources.configuration.screenWidthDp, inches)

        val known = load(context)
        if (known[key] != seen) {
            known[key] = seen
            save(context, known)
        }
        return Info(roleOf(seen, known.values), shortPx, longPx, inches, displayId)
    }

    /** Last seen window width (dp) on the screen with [role], or null if not seen yet. */
    fun widthDp(context: Context, role: ScreenRole): Int? {
        val known = load(context).values
        return known.filter { roleOf(it, known) == role }
            .let { if (role == ScreenRole.MAIN) it.maxByOrNull { s -> s.shortPx } else it.minByOrNull { s -> s.shortPx } }
            ?.widthDp
    }

    /** Forgets every recorded screen, e.g. if one was recorded wrongly. */
    fun forget(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit { remove(KEY) }
    }

    /** Recorded screens, biggest first, for display in Settings. */
    fun describeKnown(context: Context): List<String> {
        val known = load(context)
        return known.entries.sortedByDescending { it.value.shortPx }.map { (key, seen) ->
            describe(roleOf(seen, known.values), key.replace('x', '×'), seen.inches)
        }
    }

    fun describe(info: Info) = describe(info.role, "${info.shortPx}×${info.longPx}", info.inches) + ", display ${info.displayId}"

    private fun describe(role: ScreenRole, size: String, inches: Double) =
        "${if (role == ScreenRole.COVER) "Cover" else "Main"}: $size px" +
            if (inches > 0) ", %.1f\"".format(inches) else ""

    private fun windowSize(context: Context): Pair<Int, Int> {
        val w: Int
        val h: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            w = bounds.width()
            h = bounds.height()
        } else {
            val size = Point()
            @Suppress("DEPRECATION")
            ContextCompat.getDisplayOrDefault(context).getRealSize(size)
            w = size.x
            h = size.y
        }
        return minOf(w, h) to maxOf(w, h)
    }

    private fun roleOf(seen: Seen, known: Collection<Seen>): ScreenRole {
        if (seen.inches > 0 && seen.inches < COVER_MAX_INCHES) return ScreenRole.COVER
        val largest = known.maxOfOrNull { it.shortPx } ?: return ScreenRole.MAIN
        return if (seen.shortPx < largest * COVER_RATIO) ScreenRole.COVER else ScreenRole.MAIN
    }

    private fun load(context: Context): MutableMap<String, Seen> {
        val json = PreferenceManager.getDefaultSharedPreferences(context).getString(KEY, null) ?: return mutableMapOf()
        return try {
            val o = JSONObject(json)
            o.keys().asSequence().associateWith { key ->
                val v = o.getJSONObject(key)
                Seen(v.getInt("shortPx"), v.getInt("widthDp"), v.optDouble("inches", 0.0))
            }.toMutableMap()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun save(context: Context, known: Map<String, Seen>) {
        val o = JSONObject()
        known.forEach { (key, seen) ->
            o.put(key, JSONObject().put("shortPx", seen.shortPx).put("widthDp", seen.widthDp).put("inches", seen.inches))
        }
        PreferenceManager.getDefaultSharedPreferences(context).edit { putString(KEY, o.toString()) }
    }
}
