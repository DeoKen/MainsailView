package io.github.deoken.mainsailview

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager

object Prefs {
    const val PRINTER_COUNT = 2

    private fun sp(context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)

    fun printerName(context: Context, index: Int): String {
        val fallback = context.getString(if (index == 0) R.string.printer_1 else R.string.printer_2)
        return sp(context).getString("printer${index + 1}_name", null)?.trim()?.ifEmpty { null } ?: fallback
    }

    /** The printer's address with a scheme added if the user left it off, or null if unset. */
    fun printerUrl(context: Context, index: Int): String? =
        normalizeUrl(sp(context).getString("printer${index + 1}_url", null))

    fun normalizeUrl(raw: String?): String? {
        val url = raw?.trim().orEmpty()
        if (url.isEmpty()) return null
        return if (url.contains("://")) url else "http://$url"
    }

    fun selectedPrinter(context: Context): Int =
        sp(context).getInt("selected_printer", 0).coerceIn(0, PRINTER_COUNT - 1)

    fun setSelectedPrinter(context: Context, index: Int) =
        sp(context).edit { putInt("selected_printer", index) }

    fun barAtBottom(context: Context): Boolean =
        sp(context).getString("bar_position", "top") == "bottom"

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
