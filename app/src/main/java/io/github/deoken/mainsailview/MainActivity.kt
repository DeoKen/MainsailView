package io.github.deoken.mainsailview

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.tabs.TabLayout

class MainActivity : AppCompatActivity(), WebViewHolder.Host {

    private lateinit var root: LinearLayout
    private lateinit var bar: View
    private lateinit var progress: LinearProgressIndicator
    private lateinit var container: FrameLayout
    private lateinit var emptyView: TextView
    private lateinit var printerTabs: TabLayout
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton

    private var webView: WebView? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingDownload: PendingDownload? = null
    private var updatingTabs = false
    private var shownPrinters: List<Printer> = emptyList()

    // Folding/unfolding can move the window to the other screen without any other callback.
    // Debounced: Samsung also reports every refresh-rate change as a display change.
    private val checkScreen = Runnable { WebViewHolder.updateScreen(this, force = false) }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            root.removeCallbacks(checkScreen)
            root.postDelayed(checkScreen, 300)
        }

        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
    }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val uris = if (result.resultCode != RESULT_OK || data == null) {
            null
        } else {
            val clip = data.clipData
            if (clip != null) Array(clip.itemCount) { clip.getItemAt(it).uri } else data.data?.let { arrayOf(it) }
        }
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }

    private val saveAs = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val download = pendingDownload
        pendingDownload = null
        val target = result.data?.data
        if (result.resultCode == RESULT_OK && target != null && download != null) Downloads.save(this, download, target)
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        bar = findViewById(R.id.bar)
        progress = findViewById(R.id.progress)
        container = findViewById(R.id.webContainer)
        emptyView = findViewById(R.id.emptyView)
        printerTabs = findViewById(R.id.printerTabs)
        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        btnBack.setOnClickListener { webView?.takeIf { it.canGoBack() }?.goBack() }
        btnForward.setOnClickListener { webView?.takeIf { it.canGoForward() }?.goForward() }
        findViewById<ImageButton>(R.id.btnReload).setOnClickListener { webView?.reload() }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { openSettings() }

        printerTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (updatingTabs) return
                val printer = tab.tag as? Printer ?: return
                Prefs.setSelectedPrinter(this@MainActivity, printer.id)
                loadSelectedPrinter()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        // Back walks page history; at the start of history it backgrounds the app
        // instead of closing it, so the connection stays up.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webView
                if (wv != null && wv.canGoBack()) wv.goBack() else moveTaskToBack(true)
            }
        })

        webView = WebViewHolder.attach(this, this, container)
        requestNotificationPermissionOnce()
        offerBatteryExemptionOnce()

        if (savedInstanceState == null && Prefs.printers(this).isEmpty()) openSettings()
    }

    override fun onResume() {
        super.onResume()
        // Settings may have changed while we were away.
        applyBarPosition()
        if (Prefs.keepScreenOn(this)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        updatePrinterTabs()
        loadSelectedPrinter()
        WebViewHolder.updateScreen(this)
    }

    override fun onStart() {
        super.onStart()
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, null)
    }

    override fun onStop() {
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        root.removeCallbacks(checkScreen)
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        WebViewHolder.updateScreen(this)
    }

    override fun onDestroy() {
        WebViewHolder.detach(this)
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        webView = null
        super.onDestroy()
    }

    private fun loadSelectedPrinter() {
        val url = Prefs.normalizeUrl(Prefs.selectedPrinter(this)?.url)
        emptyView.visibility = if (url == null) View.VISIBLE else View.GONE
        webView?.visibility = if (url == null) View.INVISIBLE else View.VISIBLE
        WebViewHolder.ensureLoaded(url)
        onNavigationChanged()
    }

    private fun updatePrinterTabs() {
        val printers = Prefs.printers(this)
        val selectedId = Prefs.selectedPrinter(this)?.id
        updatingTabs = true
        if (printers != shownPrinters) {
            shownPrinters = printers
            printerTabs.removeAllTabs()
            printers.forEach { printer ->
                val tab = printerTabs.newTab().setText(printer.name).setTag(printer)
                printerTabs.addTab(tab, false)
                tab.view.setOnLongClickListener { openSettings(); true }
            }
        }
        val index = printers.indexOfFirst { it.id == selectedId }
        if (index >= 0) printerTabs.selectTab(printerTabs.getTabAt(index))
        updatingTabs = false
    }

    private fun applyBarPosition() {
        val atBottom = Prefs.barAtBottom(this)
        if ((root.indexOfChild(bar) != 0) == atBottom) return
        root.removeView(bar)
        root.removeView(progress)
        if (atBottom) {
            root.addView(progress)
            root.addView(bar)
        } else {
            root.addView(bar, 0)
            root.addView(progress, 1)
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33 || Prefs.askedNotificationPermission(this)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        Prefs.setAskedNotificationPermission(this)
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun offerBatteryExemptionOnce() {
        if (Prefs.askedBatteryExemption(this) || Battery.isUnrestricted(this)) return
        Prefs.setAskedBatteryExemption(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.battery_title)
            .setMessage(R.string.battery_message)
            .setPositiveButton(R.string.battery_allow) { _, _ -> Battery.requestUnrestricted(this) }
            .setNegativeButton(R.string.not_now, null)
            .show()
    }

    private fun launchSaveAs(download: PendingDownload) {
        pendingDownload = download
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(download.mimeType)
            .putExtra(Intent.EXTRA_TITLE, download.fileName)
        try {
            saveAs.launch(intent)
        } catch (e: ActivityNotFoundException) {
            pendingDownload = null
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show()
        }
    }

    // --- WebViewHolder.Host ---

    override fun showFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        // Any file type: Android has no MIME types for .gcode/.cfg/.conf, so filtering on
        // the page's accept list would hide the very files Mainsail wants.
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
        return try {
            pickFiles.launch(intent)
            true
        } catch (e: ActivityNotFoundException) {
            fileCallback = null
            false
        }
    }

    override fun requestDownload(url: String, contentDisposition: String?, mimeType: String?, userAgent: String?) {
        val name = Downloads.fileName(url, contentDisposition, mimeType)
        launchSaveAs(PendingDownload.Remote(url, userAgent, name, Downloads.mimeTypeFor(name, mimeType)))
    }

    override fun requestSaveBytes(data: ByteArray, mimeType: String, fileName: String) {
        launchSaveAs(PendingDownload.Bytes(data, fileName, Downloads.mimeTypeFor(fileName, mimeType)))
    }

    override fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onNavigationChanged() {
        val wv = webView
        btnBack.isEnabled = wv?.canGoBack() == true
        btnForward.isEnabled = wv?.canGoForward() == true
        btnBack.alpha = if (btnBack.isEnabled) 1f else 0.38f
        btnForward.alpha = if (btnForward.isEnabled) 1f else 0.38f
    }

    override fun onProgress(progress: Int) {
        this.progress.visibility = if (progress in 1..99) View.VISIBLE else View.INVISIBLE
        this.progress.setProgressCompat(progress, true)
    }

    override fun onWebViewReset() {
        webView = WebViewHolder.attach(this, this, container)
        loadSelectedPrinter()
    }
}
