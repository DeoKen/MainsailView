package io.github.deoken.mainsailview

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Button
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
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.progressindicator.LinearProgressIndicator

class MainActivity : AppCompatActivity(), WebViewHolder.Host {

    private lateinit var root: LinearLayout
    private lateinit var bar: View
    private lateinit var progress: LinearProgressIndicator
    private lateinit var container: FrameLayout
    private lateinit var emptyView: TextView
    private lateinit var printerGroup: MaterialButtonToggleGroup
    private lateinit var printerButtons: List<Button>
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton

    private var webView: WebView? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingDownload: PendingDownload? = null
    private var updatingToggle = false

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
        printerGroup = findViewById(R.id.printerGroup)
        printerButtons = listOf<Button>(findViewById(R.id.btnPrinter1), findViewById(R.id.btnPrinter2))
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

        printerGroup.addOnButtonCheckedListener { _, id, isChecked ->
            if (!isChecked || updatingToggle) return@addOnButtonCheckedListener
            val index = printerButtons.indexOfFirst { it.id == id }
            if (index >= 0 && index != Prefs.selectedPrinter(this)) {
                Prefs.setSelectedPrinter(this, index)
                loadSelectedPrinter()
            }
        }
        printerButtons.forEach { it.setOnLongClickListener { openSettings(); true } }

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

        if (savedInstanceState == null && (0 until Prefs.PRINTER_COUNT).none { Prefs.printerUrl(this, it) != null }) {
            openSettings()
        }
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
        updatePrinterButtons()
        loadSelectedPrinter()
    }

    override fun onDestroy() {
        WebViewHolder.detach(this)
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        webView = null
        super.onDestroy()
    }

    private fun loadSelectedPrinter() {
        val url = Prefs.printerUrl(this, Prefs.selectedPrinter(this))
        emptyView.visibility = if (url == null) View.VISIBLE else View.GONE
        webView?.visibility = if (url == null) View.INVISIBLE else View.VISIBLE
        WebViewHolder.ensureLoaded(url)
        onNavigationChanged()
    }

    private fun updatePrinterButtons() {
        updatingToggle = true
        printerButtons.forEachIndexed { i, button -> button.text = Prefs.printerName(this, i) }
        printerGroup.check(printerButtons[Prefs.selectedPrinter(this)].id)
        updatingToggle = false
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
