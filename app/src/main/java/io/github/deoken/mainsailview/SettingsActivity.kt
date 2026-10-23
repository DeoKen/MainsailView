package io.github.deoken.mainsailview

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlin.math.roundToInt

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Edge-to-edge: the app bar takes the status bar inset, the list the bottom one.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.appBar)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, 0)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settingsContainer)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            setupUiScale()
            findPreference<EditTextPreference>("kill_minutes")?.apply {
                setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER }
                setSummaryProvider { pref ->
                    val value = (pref as EditTextPreference).text?.trim()?.toIntOrNull() ?: 10
                    getString(R.string.pref_kill_minutes_summary, value.toString())
                }
            }
            findPreference<Preference>("battery")?.setOnPreferenceClickListener {
                Battery.requestUnrestricted(requireContext())
                true
            }
            rebuildPrinterList()
        }

        override fun onResume() {
            super.onResume()
            // The screen may have changed (folded/unfolded) since the labels were built.
            setupUiScale()
            findPreference<Preference>("battery")?.summary = getString(
                if (Battery.isUnrestricted(requireContext())) R.string.battery_unrestricted else R.string.battery_optimised
            )
        }

        // --- UI scale ---

        private fun setupUiScale() {
            val activity = activity ?: return
            val current = Screens.current(activity)
            Prefs.uiScale(activity, current) // runs the one-time migration of the old single setting
            for (role in ScreenRole.values()) {
                val pref = findPreference<ListPreference>(Prefs.uiScaleKey(role)) ?: continue
                val baseTitle = getString(if (role == ScreenRole.COVER) R.string.pref_ui_scale_cover else R.string.pref_ui_scale_main)
                pref.title = if (role == current) getString(R.string.this_screen, baseTitle) else baseTitle
                val screenWidthDp = if (role == current) {
                    resources.configuration.screenWidthDp
                } else {
                    Screens.widthDp(activity, role)
                }
                if (screenWidthDp == null) {
                    // Cover screen never seen yet (or this phone only has one screen).
                    pref.isEnabled = false
                    pref.setSummaryProvider(null)
                    pref.summary = getString(R.string.cover_not_seen)
                    continue
                }
                pref.isEnabled = true
                pref.entries = pref.entryValues.map { value ->
                    val percent = value.toString().toInt()
                    val width = pageWidth(screenWidthDp, percent)
                    getString(R.string.ui_scale_entry, percent, width, layoutName(width))
                }.toTypedArray()
                pref.setSummaryProvider { p ->
                    val percent = (p as ListPreference).value?.toIntOrNull() ?: 100
                    val width = pageWidth(screenWidthDp, percent)
                    getString(R.string.ui_scale_summary, percent, width, layoutName(width))
                }
            }
        }

        private fun pageWidth(screenWidthDp: Int, percent: Int) = (screenWidthDp * 100f / percent).roundToInt()

        /** Mainsail's layout breakpoints (Vuetify mobileBreakpoint 768, lg 1264, xl 1904). */
        private fun layoutName(widthPx: Int) = getString(
            when {
                widthPx < 768 -> R.string.layout_mobile
                widthPx < 1264 -> R.string.layout_tablet
                widthPx < 1904 -> R.string.layout_desktop
                else -> R.string.layout_widescreen
            }
        )

        // --- Printers ---

        private fun rebuildPrinterList() {
            val category = findPreference<PreferenceCategory>("printers") ?: return
            category.removeAll()
            val ctx = preferenceManager.context
            val printers = Prefs.printers(ctx)
            printers.forEachIndexed { index, printer ->
                category.addPreference(Preference(ctx).apply {
                    key = "printer_${printer.id}"
                    title = printer.name
                    summary = printer.url
                    isPersistent = false
                    setOnPreferenceClickListener { showPrinterDialog(printer, index); true }
                })
            }
            category.addPreference(Preference(ctx).apply {
                key = "add_printer"
                title = getString(R.string.add_printer)
                isPersistent = false
                setIcon(R.drawable.ic_add)
                setOnPreferenceClickListener { showPrinterDialog(null, printers.size); true }
            })
        }

        private fun showPrinterDialog(existing: Printer?, index: Int) {
            val ctx = requireContext()
            val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_printer, null)
            val nameInput = view.findViewById<TextInputEditText>(R.id.nameInput)
            val urlInput = view.findViewById<TextInputEditText>(R.id.urlInput)
            val urlLayout = view.findViewById<TextInputLayout>(R.id.urlLayout)
            val moveUp = view.findViewById<View>(R.id.moveUp)
            nameInput.setText(existing?.name.orEmpty())
            urlInput.setText(existing?.url.orEmpty())

            val builder = MaterialAlertDialogBuilder(ctx)
                .setTitle(if (existing == null) R.string.add_printer else R.string.edit_printer)
                .setView(view)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
            if (existing != null) {
                builder.setNeutralButton(R.string.delete) { _, _ ->
                    Prefs.savePrinters(ctx, Prefs.printers(ctx).filter { it.id != existing.id })
                    rebuildPrinterList()
                }
            }
            val dialog = builder.create()
            if (existing != null && index > 0) {
                moveUp.visibility = View.VISIBLE
                moveUp.setOnClickListener {
                    val printers = Prefs.printers(ctx).toMutableList()
                    val i = printers.indexOfFirst { it.id == existing.id }
                    if (i > 0) {
                        printers.add(i - 1, printers.removeAt(i))
                        Prefs.savePrinters(ctx, printers)
                        rebuildPrinterList()
                    }
                    dialog.dismiss()
                }
            }
            dialog.setOnShowListener {
                // Set here rather than in the builder so a missing address keeps the dialog open.
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val url = urlInput.text?.toString()?.trim().orEmpty()
                    if (url.isEmpty()) {
                        urlLayout.error = getString(R.string.address_required)
                        return@setOnClickListener
                    }
                    val name = nameInput.text?.toString()?.trim()?.ifEmpty { null }
                        ?: getString(R.string.printer_default_name, index + 1)
                    val printers = Prefs.printers(ctx).toMutableList()
                    if (existing == null) {
                        printers.add(Prefs.newPrinter(name, url))
                    } else {
                        val i = printers.indexOfFirst { it.id == existing.id }
                        if (i >= 0) printers[i] = existing.copy(name = name, url = url)
                    }
                    Prefs.savePrinters(ctx, printers)
                    rebuildPrinterList()
                    dialog.dismiss()
                }
            }
            dialog.show()
        }
    }
}
