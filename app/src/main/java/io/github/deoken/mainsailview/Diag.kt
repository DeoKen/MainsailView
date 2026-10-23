package io.github.deoken.mainsailview

import android.content.Context
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small on-device connection log (Settings → Connection log): app lifecycle, page loads
 * and crashes, and Mainsail's websocket opening/closing, so a disconnect can be traced to
 * its real cause. Kept in the app's private storage, capped to the last few hundred lines.
 */
object Diag {
    private const val MAX_BYTES = 64_000L
    private const val KEEP_LINES = 400

    private val lock = Any()
    private var file: File? = null
    private val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun init(context: Context) {
        file = File(context.filesDir, "connection.log")
    }

    fun log(message: String) = synchronized(lock) {
        val f = file ?: return@synchronized
        try {
            f.appendText("${time.format(Date())}  $message\n")
            if (f.length() > MAX_BYTES) f.writeText(f.readLines().takeLast(KEEP_LINES).joinToString("\n", postfix = "\n"))
        } catch (e: IOException) {
            // Logging must never break the app.
        }
    }

    fun read(): String = synchronized(lock) {
        try {
            file?.takeIf { it.exists() }?.readText().orEmpty()
        } catch (e: IOException) {
            ""
        }
    }

    fun clear() = synchronized(lock) {
        file?.delete()
        Unit
    }
}
