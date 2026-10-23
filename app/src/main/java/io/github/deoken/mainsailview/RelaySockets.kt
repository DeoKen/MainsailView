package io.github.deoken.mainsailview

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Carries Mainsail's printer websocket in the app's own process instead of the page's.
 *
 * The phone can freeze the page's process while the app is in the background (seen in the
 * connection log), and a frozen page can't answer Moonraker's pings, so Moonraker closes the
 * connection ("ping timed out"). Here the connection lives in the app process, which the
 * keep-alive service protects: pings are answered while the page is frozen and messages are
 * queued for it until it runs again. Messages pass through unchanged; nothing is read.
 *
 * The page side is `RelaySocket` in inject.js. Connections close when the page reloads or
 * navigates, when the WebView is destroyed, and with the app process.
 */
object RelaySockets {
    const val JS_OBJECT = "MainsailViewSocket"

    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // a websocket waits for messages indefinitely
        .build()

    private val sockets = mutableMapOf<Int, Relay>()
    private var reply: JavaScriptReplyProxy? = null

    /** Makes `window.MainsailViewSocket` available to every page in [webView]. */
    fun install(webView: WebView) {
        WebViewCompat.addWebMessageListener(webView, JS_OBJECT, setOf("*")) { _, message, sourceOrigin, isMainFrame, replyProxy ->
            if (isMainFrame) onPageMessage(message, sourceOrigin, replyProxy)
        }
    }

    /** Closes every relayed connection (page reload/navigation, WebView destroyed). */
    fun closeAll() {
        sockets.values.forEach { it.ws.cancel() }
        sockets.clear()
        reply = null
    }

    private fun onPageMessage(message: WebMessageCompat, sourceOrigin: Uri, replyProxy: JavaScriptReplyProxy) {
        val json = try {
            JSONObject(message.data ?: return)
        } catch (e: Exception) {
            return
        }
        reply = replyProxy
        val id = json.optInt("id")
        when (json.optString("t")) {
            "open" -> open(id, json.optString("url"), sourceOrigin)
            "send" -> sockets[id]?.ws?.send(json.optString("data"))
            "close" -> sockets[id]?.let { relay ->
                val code = json.optInt("code", 1000).takeIf { it == 1000 || it in 3000..4999 } ?: 1000
                relay.ws.close(code, json.optString("reason").ifEmpty { null })
            }
        }
    }

    private fun open(id: Int, url: String, origin: Uri) {
        sockets.remove(id)?.ws?.cancel()
        val request = try {
            Request.Builder().url(url.replaceFirst("ws://", "http://").replaceFirst("wss://", "https://"))
                .header("Origin", origin.toString().trimEnd('/'))
                .build()
        } catch (e: IllegalArgumentException) {
            toPage(JSONObject().put("t", "close").put("id", id).put("code", 1006).put("reason", "").put("clean", false))
            return
        }
        val relay = Relay(id)
        relay.ws = client.newWebSocket(request, relay)
        sockets[id] = relay
    }

    private fun toPage(json: JSONObject) {
        try {
            reply?.postMessage(json.toString())
        } catch (e: Exception) {
            // The page went away; closeAll() follows.
        }
    }

    private class Relay(val id: Int) : WebSocketListener() {
        lateinit var ws: WebSocket
        private var closed = false

        // OkHttp calls back on its own thread; hand everything to the main thread, in order,
        // and drop events from a connection that was already replaced or closed.
        private fun post(block: () -> Unit) {
            main.post { if (sockets[id] === this) block() }
        }

        private fun finish(code: Int, reason: String, clean: Boolean) {
            post {
                if (!closed) {
                    closed = true
                    sockets.remove(id)
                    toPage(JSONObject().put("t", "close").put("id", id).put("code", code).put("reason", reason).put("clean", clean))
                }
            }
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            post { toPage(JSONObject().put("t", "open").put("id", id)) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            post { toPage(JSONObject().put("t", "msg").put("id", id).put("data", text)) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code.takeIf { it == 1000 || it in 3000..4999 } ?: 1000, null)
            Diag.log("Printer connection closed by the printer (code $code${if (reason.isNotEmpty()) ", $reason" else ""})")
            finish(code, reason, clean = true)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            finish(code, reason, clean = true)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Diag.log("Printer connection failed: ${t.javaClass.simpleName}${t.message?.let { ": $it" } ?: ""}")
            finish(1006, "", clean = false)
        }
    }
}
