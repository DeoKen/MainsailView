package io.github.deoken.mainsailview

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.webkit.WebView

/**
 * Double-tap-and-drag zoom (drag down to zoom in, up to zoom out), which WebView doesn't do
 * by itself. ScaleGestureDetector recognises the gesture ("quick scale"); it is then played
 * to the WebView as an ordinary two-finger pinch around the tapped point, so WebView zooms
 * exactly as it does for a real pinch. Real two-finger pinches and everything else pass
 * through untouched.
 */
class QuickZoom(private val webView: WebView) : View.OnTouchListener {

    private var current: MotionEvent? = null
    private var active = false
    private var downTime = 0L
    private var focusX = 0f
    private var focusY = 0f
    private var scale = 1f
    private val baseSpread = 60 * webView.resources.displayMetrics.density

    private val detector = ScaleGestureDetector(webView.context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            val ev = current ?: return false
            if (ev.pointerCount != 1) return false // a real pinch: WebView handles it itself
            begin(ev)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (!active) return false
            scale = (scale * detector.scaleFactor).coerceIn(0.05f, 20f)
            pinch(MotionEvent.ACTION_MOVE)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) = end()
    }).apply { isQuickScaleEnabled = true }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        current = event
        detector.onTouchEvent(event)
        current = null
        if (!active) return false
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) end()
        return true // swallow the finger's own events while it drives the zoom
    }

    private fun begin(ev: MotionEvent) {
        active = true
        scale = 1f
        focusX = ev.x
        focusY = ev.y
        // The WebView already saw this finger go down; cancel that before the pinch starts.
        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
        webView.onTouchEvent(cancel)
        cancel.recycle()
        downTime = SystemClock.uptimeMillis()
        pinch(MotionEvent.ACTION_DOWN)
        pinch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
    }

    private fun end() {
        if (!active) return
        active = false
        pinch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
        pinch(MotionEvent.ACTION_UP)
    }

    /** Sends one event of a two-finger pinch centred on the tap, fingers [scale] apart. */
    private fun pinch(action: Int) {
        val spread = baseSpread * scale
        val single = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP
        val count = if (single) 1 else 2
        val props = Array(count) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(count) { i ->
            MotionEvent.PointerCoords().apply {
                x = focusX + if (i == 0) -spread else spread
                y = focusY
                pressure = 1f
                size = 1f
            }
        }
        val event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, count, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        webView.onTouchEvent(event)
        event.recycle()
    }
}
