package com.scrollmeter.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

class NotchOverlayManager(private val context: Context) {

    private val windowManager: WindowManager by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }
    private var overlayView: LinearLayout? = null
    private var isAttached = false

    private var timeTextView: TextView? = null
    private var countTextView: TextView? = null
    private var statusDotView: View? = null

    companion object {
        private const val TAG = "NotchOverlay"
        const val PREF_KEY_NOTCH_OVERLAY = "pref_notch_overlay_enabled"
        const val PREFS_NAME = "scrollmeter_settings"

        private const val DEFAULT_OVERLAY_ALPHA = 0.72f // Translucent, non-distracting
        var isScrollMeterForeground: Boolean = false

        fun isOverlayEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(PREF_KEY_NOTCH_OVERLAY, true)
        }

        fun setOverlayEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(PREF_KEY_NOTCH_OVERLAY, enabled).apply()
        }
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        ).toInt()
    }

    private fun getStatusBarHeight(): Int {
        var result = dpToPx(28f)
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = context.resources.getDimensionPixelSize(resourceId)
        }
        return result
    }

    fun showOrUpdate(durationMs: Long, reelCount: Int, isInstagramActive: Boolean = true) {
        if (!isOverlayEnabled(context)) {
            hide()
            return
        }

        val timeStr = if (isInstagramActive) formatDuration(durationMs) else "Paused"
        val countStr = if (reelCount == 1) "1 reel" else "$reelCount reels"

        if (overlayView == null) {
            initOverlay()
        }

        if (timeTextView?.text != timeStr) {
            timeTextView?.text = timeStr
        }
        if (countTextView?.text != countStr) {
            countTextView?.text = countStr
        }

        // Update dot color: Emerald Green when in Instagram, Amber when outside
        val dotColor = if (isInstagramActive) "#10B981" else "#F59E0B"
        statusDotView?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(dotColor))
        }

        // Attach to WindowManager once if not already attached
        if (overlayView != null && !isAttached) {
            try {
                val params = createLayoutParams()
                windowManager.addView(overlayView, params)
                isAttached = true
                Log.i(TAG, "Notch overlay attached to WindowManager")
            } catch (e: Exception) {
                Log.w(TAG, "Primary window type failed: ${e.message}, trying fallback")
                try {
                    val fallbackParams = createFallbackLayoutParams()
                    windowManager.addView(overlayView, fallbackParams)
                    isAttached = true
                    Log.i(TAG, "Notch overlay attached using fallback")
                } catch (e2: Exception) {
                    Log.e(TAG, "All window types failed: ${e2.message}")
                    isAttached = false
                    return
                }
            }
        }

        // Ensure visible
        if (overlayView != null && isAttached) {
            overlayView?.visibility = View.VISIBLE
            overlayView?.alpha = 1.0f
        }
    }

    fun hide() {
        if (overlayView != null && isAttached) {
            overlayView?.animate()?.cancel()
            overlayView?.visibility = View.GONE
            overlayView?.alpha = 0f
        }
    }

    fun destroy() {
        try {
            overlayView?.animate()?.cancel()
            if (isAttached && overlayView != null) {
                windowManager.removeViewImmediate(overlayView)
            }
        } catch (_: Exception) {}
        isAttached = false
        overlayView = null
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = getStatusBarHeight() + dpToPx(3f)
        return params
    }

    private fun createFallbackLayoutParams(): WindowManager.LayoutParams {
        val fallbackType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            fallbackType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = getStatusBarHeight() + dpToPx(6f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return params
    }

    private fun initOverlay() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.VISIBLE
            alpha = 1.0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setPadding(dpToPx(14f), dpToPx(6f), dpToPx(14f), dpToPx(6f))

            // Sleek High-Contrast Glass Pill Background with bright purple accent border
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(24f).toFloat()
                setColor(Color.parseColor("#EE0B0E17")) // Deep dark glass (93% opaque)
                setStroke(dpToPx(1.5f), Color.parseColor("#A855F7")) // Vibrant purple neon border
            }
            background = bg
            elevation = dpToPx(6f).toFloat()

            // Tap to open ScrollMeter App
            setOnClickListener {
                try {
                    val intent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {}
            }
        }

        // Active Status Dot (Bright emerald green)
        val dot = View(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val dotBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#10B981"))
            }
            background = dotBg
            val lp = LinearLayout.LayoutParams(dpToPx(6f), dpToPx(6f)).apply {
                marginEnd = dpToPx(7f)
            }
            layoutParams = lp
        }
        statusDotView = dot
        root.addView(dot)

        // Live Watch Time
        timeTextView = TextView(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setTextColor(Color.parseColor("#FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            text = "0m"
        }
        root.addView(timeTextView)

        // Bullet divider
        val bullet = TextView(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            text = " • "
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        root.addView(bullet)

        // Reel Count
        countTextView = TextView(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setTextColor(Color.parseColor("#D8B4FE"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            text = "0 reels"
        }
        root.addView(countTextView)

        overlayView = root
    }

    private fun formatDuration(ms: Long): String {
        val totalSecs = (ms / 1000).coerceAtLeast(0)
        val mins = totalSecs / 60
        val hours = mins / 60
        return when {
            hours > 0 -> String.format(java.util.Locale.getDefault(), "%dh %02dm", hours, mins % 60)
            else -> "${mins}m"
        }
    }
}
