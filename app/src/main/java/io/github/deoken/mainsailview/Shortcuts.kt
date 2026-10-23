package io.github.deoken.mainsailview

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/** Long-press launcher shortcuts that open the app straight on a given printer. */
object Shortcuts {
    const val EXTRA_PRINTER_ID = "io.github.deoken.mainsailview.PRINTER_ID"

    fun update(context: Context) {
        val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
        val shortcuts = Prefs.printers(context).take(max).mapIndexed { rank, printer ->
            ShortcutInfoCompat.Builder(context, "printer_${printer.id}")
                .setShortLabel(printer.name)
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setRank(rank)
                .setIntent(
                    Intent(context, MainActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .putExtra(EXTRA_PRINTER_ID, printer.id)
                )
                .build()
        }
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }
}
