package io.github.deoken.mainsailview

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Printer(val id: String, val name: String, val url: String)

object Prefs {
    private const val KEY_PRINTERS = "printers"
    private const val KEY_SELECTED = "selected_printer_id"

    private fun sp(context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)

    fun printers(context: Context): List<Printer> {
        migrateLegacyPrinters(context)
        val json = sp(context).getString(KEY_PRINTERS, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            List(array.length()) { i ->
                val o = array.getJSONObject(i)
                Printer(o.getString("id"), o.optString("name"), o.optString("url"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun savePrinters(context: Context, printers: List<Printer>) {
        val array = JSONArray()
        printers.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url)) }
        sp(context).edit { putString(KEY_PRINTERS, array.toString()) }
    }

    fun newPrinter(name: String, url: String) = Printer(UUID.randomUUID().toString(), name, url)

    /** The selected printer, falling back to the first one; null when none are set up. */
    fun selectedPrinter(context: Context): Printer? {
        val printers = printers(context)
        val id = sp(context).getString(KEY_SELECTED, null)
        return printers.firstOrNull { it.id == id } ?: printers.firstOrNull()
    }

    fun setSelectedPrinter(context: Context, id: String) =
        sp(context).edit { putString(KEY_SELECTED, id) }

    /** Version 0.1 stored exactly two printers under fixed keys; fold them into the list. */
    private fun migrateLegacyPrinters(context: Context) {
        val sp = sp(context)
        if (sp.contains(KEY_PRINTERS)) return
        val legacy = (1..2).mapNotNull { n ->
            val url = sp.getString("printer${n}_url", null)?.trim().orEmpty()
            if (url.isEmpty()) null
            else newPrinter(sp.getString("printer${n}_name", null)?.trim()?.ifEmpty { null } ?: "Printer $n", url)
        }
        savePrinters(context, legacy)
        sp.edit {
            (1..2).forEach { remove("printer${it}_name"); remove("printer${it}_url") }
            remove("selected_printer")
        }
    }

    fun normalizeUrl(raw: String?): String? {
        val url = raw?.trim().orEmpty()
        if (url.isEmpty()) return null
        return if (url.contains("://")) url else "http://$url"
    }

    fun barAtBottom(context: Context): Boolean =
        sp(context).getString("bar_position", "top") == "bottom"

    fun uiScaleKey(role: ScreenRole) = if (role == ScreenRole.COVER) "ui_scale_cover" else "ui_scale_main"

    /** Page scale for [role]'s screen as a fraction; below 1 gives Mainsail a wider page than the screen. */
    fun uiScale(context: Context, role: ScreenRole): Float {
        val sp = sp(context)
        // 0.2 had a single "ui_scale"; it becomes the main screen's.
        sp.getString("ui_scale", null)?.let { old -> sp.edit { putString("ui_scale_main", old); remove("ui_scale") } }
        return (sp.getString(uiScaleKey(role), null)?.toIntOrNull() ?: 100).coerceIn(10, 400) / 100f
    }

    /** Minutes in the background before the app closes itself; 0 means never. */
    fun killMinutes(context: Context): Int =
        sp(context).getString("kill_minutes", null)?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 10

    fun keepScreenOn(context: Context): Boolean =
        sp(context).getBoolean("keep_screen_on", true)

    fun askedNotificationPermission(context: Context): Boolean =
        sp(context).getBoolean("asked_notification_permission", false)

    fun setAskedNotificationPermission(context: Context) =
        sp(context).edit { putBoolean("asked_notification_permission", true) }
}
