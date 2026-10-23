package io.github.deoken.mainsailview

import android.Manifest
import android.app.PictureInPictureParams
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
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
import androidx.core.view.WindowInsetsControllerCompat
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
    private lateinit var btnFullscreen: ImageButton

    private var webView: WebView? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingDownload: PendingDownload? = null
    private var updatingTabs = false
    private var shownPrinters: List<Printer> = emptyList()

    // Fullscreen hides the toolbar and system bars; swiping in a system bar "peeks" the
    // toolbar for a few seconds so it can be turned off again.
    private var peeking = false
    private var barsHiddenAt = 0L
    private val endPeek = Runnable {
        peeking = false
        applyFullscreen()
    }

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
        btnFullscreen = findViewById(R.id.btnFullscreen)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val fullscreen = Prefs.fullscreen(this) && !isInPictureInPictureMode
            val settling = android.os.SystemClock.uptimeMillis() - barsHiddenAt < 1000 // bars still animating away
            if (fullscreen && !peeking && !settling && insets.isVisible(WindowInsetsCompat.Type.statusBars())) {
                // The user swiped the system bars in: show the toolbar for a moment too.
                peeking = true
                bar.visibility = View.VISIBLE
                root.removeCallbacks(endPeek)
                root.postDelayed(endPeek, 4000)
            }
            val types = if (fullscreen && !peeking) {
                WindowInsetsCompat.Type.displayCutout()
            } else {
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            }
            val bars = if (fullscreen) insets.getInsetsIgnoringVisibility(types) else insets.getInsets(types)
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        btnBack.setOnClickListener { webView?.takeIf { it.canGoBack() }?.goBack() }
        btnForward.setOnClickListener { webView?.takeIf { it.canGoForward() }?.goForward() }
        findViewById<ImageButton>(R.id.btnReload).setOnClickListener { webView?.reload() }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { openSettings() }
        btnFullscreen.setOnClickListener {
            val on = !Prefs.fullscreen(this)
            Prefs.setFullscreen(this, on)
            peeking = false
            root.removeCallbacks(endPeek)
            applyFullscreen()
            if (on) Toast.makeText(this, R.string.fullscreen_hint, Toast.LENGTH_SHORT).show()
        }

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
        selectPrinterFromIntent(intent)
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
        applyFullscreen()
        updatePrinterTabs()
        loadSelectedPrinter()
        WebViewHolder.updateScreen(this)
    }

    // singleTask: a launcher shortcut tapped while the app is open arrives here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        selectPrinterFromIntent(intent)
    }

    private fun selectPrinterFromIntent(intent: Intent?) {
        val id = intent?.getStringExtra(Shortcuts.EXTRA_PRINTER_ID) ?: return
        intent.removeExtra(Shortcuts.EXTRA_PRINTER_ID)
        if (Prefs.printers(this).any { it.id == id }) Prefs.setSelectedPrinter(this, id)
    }

    private fun applyFullscreen() {
        if (isInPictureInPictureMode) return
        val fullscreen = Prefs.fullscreen(this)
        btnFullscreen.setImageResource(if (fullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
        bar.visibility = if (fullscreen && !peeking) View.GONE else View.VISIBLE
        val controller = WindowInsetsControllerCompat(window, root)
        if (fullscreen) {
            // Default behaviour: a swipe brings the bars back for real (reported in insets),
            // which is what lets the toolbar peek; endPeek hides them again.
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            controller.hide(WindowInsetsCompat.Type.systemBars())
            barsHiddenAt = android.os.SystemClock.uptimeMillis()
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        ViewCompat.requestApplyInsets(root)
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

    // --- Picture-in-picture (only while Mainsail's Webcam page is open) ---

    private fun pipParams(): PictureInPictureParams {
        // Android only accepts aspect ratios between about 1:2.39 and 2.39:1.
        val ratio = (WebViewHolder.camWidth.toFloat() / WebViewHolder.camHeight).coerceIn(0.42f, 2.39f)
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
        if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(WebViewHolder.onCamPage)
        return builder.build()
    }

    override fun onCamStateChanged() {
        setPictureInPictureParams(pipParams())
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters PiP by itself through setAutoEnterEnabled.
        if (Build.VERSION.SDK_INT < 31 && WebViewHolder.onCamPage) {
            try {
                enterPictureInPictureMode(pipParams())
            } catch (e: IllegalStateException) {
                // PiP turned off for this app in system settings.
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            bar.visibility = View.GONE
            progress.visibility = View.GONE
        } else {
            applyFullscreen()
        }
        WebViewHolder.setPip(isInPictureInPictureMode)
        App.instance.notePip(isInPictureInPictureMode)
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
        if (isInPictureInPictureMode) return
        this.progress.visibility = if (progress in 1..99) View.VISIBLE else View.INVISIBLE
        this.progress.setProgressCompat(progress, true)
    }

    override fun onWebViewReset() {
        webView = WebViewHolder.attach(this, this, container)
        loadSelectedPrinter()
    }
}
