package io.github.deoken.mainsailview

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * Hosts the WebView for UI scale: the WebView is laid out at (size / [contentScale]) and
 * then drawn scaled by [contentScale], so the page sees a wider (or narrower) screen and
 * still fits exactly. Android maps touches through the scale. Other children are normal.
 */
class ScaledFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var contentScale = 1f
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = (measuredWidth / contentScale).roundToInt()
        val height = (measuredHeight / contentScale).roundToInt()
        scaledChildren().forEach {
            it.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        scaledChildren().forEach {
            it.layout(0, 0, it.measuredWidth, it.measuredHeight)
            it.pivotX = 0f
            it.pivotY = 0f
            it.scaleX = contentScale
            it.scaleY = contentScale
        }
    }

    private fun scaledChildren(): List<View> = (0 until childCount).map { getChildAt(it) }.filter { it is WebView }
}
