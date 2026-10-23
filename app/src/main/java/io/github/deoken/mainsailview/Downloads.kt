package io.github.deoken.mainsailview

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.Executors

/** Something the user asked to save, waiting for them to pick where. */
sealed class PendingDownload(val fileName: String, val mimeType: String) {
    class Remote(val url: String, val userAgent: String?, fileName: String, mimeType: String) :
        PendingDownload(fileName, mimeType)

    class Bytes(val data: ByteArray, fileName: String, mimeType: String) :
        PendingDownload(fileName, mimeType)
}

object Downloads {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Writes [download] to the document the user picked, off the main thread. */
    fun save(context: Context, download: PendingDownload, target: Uri) {
        val app = context.applicationContext
        if (download is PendingDownload.Remote) toast(app, app.getString(R.string.downloading, download.fileName))
        executor.execute {
            try {
                val out = app.contentResolver.openOutputStream(target, "wt")
                    ?: throw IOException("Can't write to the chosen location")
                out.use {
                    when (download) {
                        is PendingDownload.Bytes -> it.write(download.data)
                        is PendingDownload.Remote -> fetch(download, it)
                    }
                }
                toast(app, app.getString(R.string.saved, download.fileName))
            } catch (e: Exception) {
                try {
                    DocumentsContract.deleteDocument(app.contentResolver, target)
                } catch (ignored: Exception) {
                }
                toast(app, app.getString(R.string.save_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun fetch(download: PendingDownload.Remote, out: java.io.OutputStream) {
        val conn = URL(download.url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            download.userAgent?.let { conn.setRequestProperty("User-Agent", it) }
            CookieManager.getInstance().getCookie(download.url)?.let { conn.setRequestProperty("Cookie", it) }
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.use { it.copyTo(out) }
        } finally {
            conn.disconnect()
        }
    }

    /** Picks a file name from Content-Disposition, else from the last part of the URL path. */
    fun fileName(url: String, contentDisposition: String?, mimeType: String?): String {
        contentDisposition?.let { cd ->
            Regex("filename\\*=(?:UTF-8|utf-8)''([^;]+)").find(cd)?.let {
                return URLDecoder.decode(it.groupValues[1].trim(), "UTF-8")
            }
            Regex("filename=\"?([^\";]+)\"?").find(cd)?.let { return it.groupValues[1].trim() }
        }
        if (!url.startsWith("data:") && !url.startsWith("blob:")) {
            Uri.parse(url).lastPathSegment?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val ext = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        return if (ext != null) "download.$ext" else "download"
    }

    /** MIME type for the save dialog; unknown types such as .gcode or .cfg fall back to binary. */
    fun mimeTypeFor(fileName: String, reported: String?): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: reported?.takeIf { it.isNotBlank() && it != "application/octet-stream" && ext.isEmpty() }
            ?: "application/octet-stream"
    }

    /** Decodes a data: URL into its bytes and MIME type. */
    fun decodeDataUrl(url: String): Pair<ByteArray, String>? {
        val comma = url.indexOf(',')
        if (comma < 0) return null
        val header = url.substring(5, comma)
        val payload = url.substring(comma + 1)
        val mime = header.substringBefore(';').ifBlank { "text/plain" }
        val bytes = if (header.endsWith(";base64")) {
            try {
                Base64.decode(payload, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                return null
            }
        } else {
            URLDecoder.decode(payload.replace("+", "%2B"), "UTF-8").toByteArray()
        }
        return bytes to mime
    }

    private fun toast(context: Context, text: String) {
        main.post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }
}
