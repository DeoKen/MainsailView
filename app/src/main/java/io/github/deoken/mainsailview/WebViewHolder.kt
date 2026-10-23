package io.github.deoken.mainsailview

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Base64
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Holds the single WebView for the whole process so it outlives activity recreation.
 * It is created on the application context (through a MutableContextWrapper that points
 * at the current activity while one is attached) and never paused, which keeps
 * Mainsail's websocket connected while the app is in the background.
 */
@SuppressLint("SetJavaScriptEnabled")
object WebViewHolder {

    interface Host {
        fun showFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean
        fun requestDownload(url: String, contentDisposition: String?, mimeType: String?, userAgent: String?)
        fun requestSaveBytes(data: ByteArray, mimeType: String, fileName: String)
        fun openExternal(uri: Uri)
        fun onNavigationChanged()
        fun onProgress(progress: Int)
        fun onWebViewReset()
        fun showFullscreen(view: android.view.View, callback: WebChromeClient.CustomViewCallback)
        fun hideFullscreen()
        fun onFullscreenAspectChanged()
    }

    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var wrapper: MutableContextWrapper? = null
    private var host: Host? = null
    private var loadedUrl: String? = null
    private var clearHistoryOnLoad = false
    private var hasDocumentStartScript = false
    private var injectJs = ""

    // Connection recovery: a failed main page load, retries of it, and whether the last
    // check found Mainsail's websocket closed.
    private var pageFailed = false
    private var loadRetries = 0
    private var lostAtLastCheck = false
    private const val MAX_LOAD_RETRIES = 40 // every 3 s, so about 2 minutes

    // UI scale is done by the page itself (viewport width), so pinch-zoom works on top of it:
    // the page is laid out (WebView width / scale) CSS px wide and zoomed out to fit.
    @Volatile
    private var uiScale = 1f

    @Volatile
    private var viewWidthDp = 0f
    private var appliedScale = "" // "width:scale" last applied to the current page

    /** Shape of the element shown fullscreen (e.g. a webcam), for the PiP window. */
    var fullscreenWidth = 16
        private set
    var fullscreenHeight = 9
        private set

    val current: WebView? get() = webView

    fun attach(context: Context, host: Host, container: ViewGroup): WebView {
        val wv = webView ?: create(context.applicationContext)
        wrapper?.baseContext = context
        this.host = host
        (wv.parent as? ViewGroup)?.removeView(wv)
        container.addView(wv, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return wv
    }

    fun detach(host: Host) {
        // A recreated activity attaches before the old one is destroyed; leave it alone.
        if (this.host !== host) return
        this.host = null
        val wv = webView ?: return
        wrapper?.baseContext = wv.context.applicationContext
        (wv.parent as? ViewGroup)?.removeView(wv)
    }

    /** Loads [url] unless it is already the page being shown. Null shows a blank page. */
    fun ensureLoaded(url: String?) {
        val wv = webView ?: return
        if (url == loadedUrl) return
        loadedUrl = url
        clearHistoryOnLoad = true // don't let Back walk into the other printer
        wv.loadUrl(url ?: "about:blank")
    }

    fun reload(reason: String) {
        Diag.log("Reloading the page: $reason")
        lostAtLastCheck = false
        webView?.reload()
    }

    /**
     * Reconnects Mainsail if it lost its connection to Moonraker (or reloads the page if
     * the page itself failed to load). [immediate] acts on the first sign of it, e.g. on
     * returning to the app; otherwise it must be seen on two checks in a row, so Mainsail's
     * own quick reconnect attempts get a chance first.
     */
    fun checkConnection(immediate: Boolean) {
        val wv = webView ?: return
        if (loadedUrl == null) return
        if (pageFailed) {
            reload("the page had failed to load")
            return
        }
        wv.evaluateJavascript("window.__mainsailView ? window.__mainsailView.connectionLost() : false") { result ->
            val lost = result == "true"
            if (lost && (immediate || lostAtLastCheck)) reconnect() else lostAtLastCheck = lost
        }
    }

    /** Mainsail's own "Try again" (no page reload); a full reload only if that's unavailable. */
    private fun reconnect() {
        lostAtLastCheck = false
        Diag.log("Mainsail is disconnected: pressing its Try again")
        webView?.evaluateJavascript("window.__mainsailView ? window.__mainsailView.reconnect() : false") { result ->
            if (result != "true") reload("Mainsail's Try again isn't available")
        }
    }

    /** Sets the UI scale for the screen the app is on (1 = Mainsail's normal layout). */
    fun setUiScale(scale: Float) {
        uiScale = scale
        applyScale(force = false)
    }

    /** Page width for the current UI scale, or 0 for Mainsail's own (device width). */
    private fun pageWidth(): Int =
        if (uiScale == 1f || viewWidthDp <= 0f) 0 else Math.round(viewWidthDp / uiScale)

    /**
     * Applies the UI scale to the page and fits it to the screen. Only when the width or
     * scale actually changed (or [force] for a new page), so a pinch-zoom isn't undone by
     * e.g. the keyboard opening.
     */
    private fun applyScale(force: Boolean) {
        val wv = webView ?: return
        if (viewWidthDp <= 0f) return
        val width = pageWidth()
        val key = "$width:$uiScale"
        if (!force && key == appliedScale) return
        appliedScale = key
        wv.evaluateJavascript("window.__mainsailView && window.__mainsailView.applyScale($width)") {
            // Zoom far out; it's clamped to the smallest zoom, which is "page fits the screen
            // width". (zoomBy rejects factors below 0.01, and 0.01f as a float is just under.)
            main.postDelayed({
                try {
                    webView?.zoomBy(0.02f)
                } catch (e: IllegalArgumentException) {
                    Diag.log("Couldn't fit the page to the screen: ${e.message}")
                }
            }, 150)
        }
    }

    /** Takes the focus out of the page's text box, if one has it (see MainActivity). */
    fun blurTextField() {
        webView?.evaluateJavascript("window.__mainsailView && window.__mainsailView.blurTextField()", null)
    }

    fun suspendCams() {
        webView?.evaluateJavascript("window.__mainsailView && window.__mainsailView.suspendCams()", null)
    }

    fun resumeCams() {
        webView?.evaluateJavascript("window.__mainsailView && window.__mainsailView.resumeCams()", null)
    }

    fun destroy() {
        RelaySockets.closeAll()
        val wv = webView ?: return
        webView = null
        wrapper = null
        loadedUrl = null
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.destroy()
    }

    private fun create(appContext: Context): WebView {
        injectJs = appContext.assets.open("inject.js").bufferedReader().use { it.readText() }
        val ctx = MutableContextWrapper(appContext)
        val wv = WebView(ctx)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = false
            // Pinch-to-zoom, without the old on-screen +/- buttons.
            builtInZoomControls = true
            displayZoomControls = false
            // Honour the page's viewport width (set by inject.js for UI scale) and start
            // zoomed out to fit it.
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        wv.addOnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left == oldRight - oldLeft) return@addOnLayoutChangeListener
            viewWidthDp = (right - left) / v.resources.displayMetrics.density
            applyScale(force = false)
        }
        // Keep the renderer at foreground priority even while hidden so Android doesn't
        // kill it (and with it the websocket) when memory gets tight.
        wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        CookieManager.getInstance().setAcceptCookie(true)
        wv.addJavascriptInterface(Bridge, "MainsailViewBridge")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            try {
                RelaySockets.install(wv)
            } catch (e: RuntimeException) {
                Diag.log("Couldn't set up the connection relay (${e.message}); using the page's own")
            }
        } else {
            Diag.log("This WebView can't relay the printer connection; using the page's own")
        }
        hasDocumentStartScript = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (hasDocumentStartScript) WebViewCompat.addDocumentStartJavaScript(wv, injectJs, setOf("*"))
        wv.webViewClient = client
        wv.webChromeClient = chromeClient
        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDownload(url, userAgent, contentDisposition, mimeType)
        }
        wrapper = ctx
        webView = wv
        return wv
    }

    private fun handleDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        when {
            url.startsWith("data:") -> Downloads.decodeDataUrl(url)?.let { (bytes, mime) ->
                host?.requestSaveBytes(bytes, mime, Downloads.fileName(url, contentDisposition, mime))
            }
            url.startsWith("blob:") -> webView?.evaluateJavascript(
                "window.__mainsailView && window.__mainsailView.saveUrl(${quote(url)}, ${quote(Downloads.fileName(url, contentDisposition, mimeType))})",
                null
            )
            else -> host?.requestDownload(url, contentDisposition, mimeType, userAgent)
        }
    }

    private fun quote(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    /** Moonraker serves every downloadable file from /server/files/. */
    private fun isMoonrakerFile(uri: Uri) = uri.path?.contains("/server/files/") == true

    private fun isPrinterPage(uri: Uri): Boolean {
        val printerHost = loadedUrl?.let { Uri.parse(it).host } ?: return false
        return uri.host.equals(printerHost, ignoreCase = true)
    }

    /** Where a link that would leave the current page should go. Returns true if handled. */
    private fun route(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        if (scheme == "data" || scheme == "blob" || scheme == "about" || scheme == "javascript") return false
        if ((scheme == "http" || scheme == "https") && isMoonrakerFile(uri)) {
            host?.requestDownload(uri.toString(), null, null, webView?.settings?.userAgentString)
            return true
        }
        if ((scheme == "http" || scheme == "https") && isPrinterPage(uri)) return false
        host?.openExternal(uri)
        return true
    }

    private object Bridge {
        @JavascriptInterface
        fun log(message: String) = Diag.log("Page: $message")

        /** Page width for UI scale (0 = Mainsail's own), read as the page starts. */
        @JavascriptInterface
        fun pageWidth(): Int = WebViewHolder.pageWidth()

        @JavascriptInterface
        fun fullscreenState(on: Boolean, width: Int, height: Int) {
            if (!on) return
            main.post {
                fullscreenWidth = width.coerceAtLeast(1)
                fullscreenHeight = height.coerceAtLeast(1)
                host?.onFullscreenAspectChanged()
            }
        }

        @JavascriptInterface
        fun saveBase64(data: String, mimeType: String?, fileName: String?) {
            val bytes = try {
                Base64.decode(data, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                return
            }
            val mime = mimeType?.ifBlank { null } ?: "application/octet-stream"
            val name = fileName?.ifBlank { null } ?: "download"
            main.post { host?.requestSaveBytes(bytes, mime, name) }
        }
    }

    private val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            return route(request.url)
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (url != "about:blank") Diag.log("Loading $url")
            RelaySockets.closeAll() // connections belong to the page being replaced
            appliedScale = ""
            pageFailed = false
            if (!hasDocumentStartScript) view.evaluateJavascript(injectJs, null)
            host?.onNavigationChanged()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (!pageFailed) loadRetries = 0
            applyScale(force = true)
            if (!hasDocumentStartScript) view.evaluateJavascript(injectJs, null)
            if (clearHistoryOnLoad && url != "about:blank") {
                clearHistoryOnLoad = false
                view.clearHistory()
            }
            host?.onNavigationChanged()
        }

        // The printer couldn't be reached (e.g. Wi-Fi still waking up after the phone was
        // locked): keep retrying while the app is open instead of leaving an error page.
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
            if (!request.isForMainFrame || view !== webView) return
            Diag.log("Page failed to load: ${error.description}")
            pageFailed = true
            if (App.instance.isInForeground && loadRetries < MAX_LOAD_RETRIES) {
                loadRetries++
                main.postDelayed({ if (pageFailed && App.instance.isInForeground) reload("retrying the failed load") }, 3_000)
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            host?.onNavigationChanged()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // The page's renderer crashed or was killed: throw this WebView away and
            // let the activity build and load a fresh one.
            if (view === webView) {
                Diag.log(
                    if (detail.didCrash()) "The page's process crashed"
                    else "Android killed the page's process (its priority was ${detail.rendererPriorityAtExit()}; 2 = important)"
                )
                destroy()
                host?.onWebViewReset()
            } else {
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
            }
            return true
        }
    }

    private val chromeClient = object : WebChromeClient() {
        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean = host?.showFileChooser(filePathCallback, fileChooserParams) ?: false

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            host?.onProgress(newProgress)
        }

        // Fullscreen API, e.g. the webcam video's own fullscreen button.
        override fun onShowCustomView(view: android.view.View, callback: CustomViewCallback) {
            val h = host
            if (h == null) callback.onCustomViewHidden() else h.showFullscreen(view, callback)
        }

        override fun onHideCustomView() {
            host?.hideFullscreen()
        }

        // window.open(): Mainsail uses it for file downloads and the odd external link.
        // Catch the target URL in a throwaway WebView and route it instead of opening a tab.
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val popup = WebView(view.context)
            var handled = false
            fun handle(url: String?) {
                if (handled || url.isNullOrEmpty() || url == "about:blank") return
                handled = true
                main.post { popup.stopLoading(); popup.destroy() }
                if (url.startsWith("data:") || url.startsWith("blob:")) {
                    handleDownload(url, null, null, null)
                    return
                }
                val uri = Uri.parse(url)
                if (!route(uri)) host?.openExternal(uri)
            }
            popup.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    handle(request.url.toString())
                    return true
                }

                override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                    handle(url)
                }
            }
            popup.setDownloadListener { url, _, _, _, _ -> handle(url) }
            main.postDelayed({ if (!handled) { handled = true; popup.destroy() } }, 10_000)
            (resultMsg.obj as WebView.WebViewTransport).webView = popup
            resultMsg.sendToTarget()
            return true
        }
    }
}
