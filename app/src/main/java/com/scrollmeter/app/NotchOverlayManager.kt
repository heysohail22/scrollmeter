package com.scrollmeter.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

class NotchOverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: LinearLayout? = null
    private var isAttached = false

    private var timeTextView: TextView? = null
    private var countTextView: TextView? = null

    companion object {
        const val PREF_KEY_NOTCH_OVERLAY = "pref_notch_overlay_enabled"
        const val PREFS_NAME = "scrollmeter_settings"

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

    fun showOrUpdate(durationMs: Long, reelCount: Int) {
        if (!isOverlayEnabled(context)) {
            hide()
            return
        }

        val timeStr = formatDuration(durationMs)
        val countStr = if (reelCount == 1) "1 reel" else "$reelCount reels"

        if (overlayView == null) {
            initOverlay()
        }

        timeTextView?.text = timeStr
        countTextView?.text = countStr

        if (overlayView != null && !isAttached) {
            try {
                val params = createLayoutParams()
                windowManager.addView(overlayView, params)
                isAttached = true
                overlayView?.apply {
                    alpha = 0f
                    scaleX = 0.85f
                    scaleY = 0.85f
                    visibility = View.VISIBLE
                    animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(220)
                        .start()
                }
            } catch (e: Exception) {
                isAttached = false
            }
        }
    }

    fun hide() {
        if (overlayView != null && isAttached) {
            overlayView?.animate()
                ?.alpha(0f)
                ?.scaleX(0.85f)
                ?.scaleY(0.85f)
                ?.setDuration(180)
                ?.withEndAction {
                    try {
                        if (isAttached && overlayView != null) {
                            windowManager.removeView(overlayView)
                            isAttached = false
                        }
                    } catch (_: Exception) {}
                }
                ?.start()
        }
    }

    fun destroy() {
        try {
            if (isAttached && overlayView != null) {
                windowManager.removeViewImmediate(overlayView)
            }
        } catch (_: Exception) {}
        isAttached = false
        overlayView = null
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
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
        params.y = getStatusBarHeight() + dpToPx(4f)
        return params
    }

    private fun initOverlay() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(13f), dpToPx(6f), dpToPx(13f), dpToPx(6f))

            // Premium Dark Pill Background with Glowing Purple Border
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(24f).toFloat()
                setColor(Color.parseColor("#0C0F1D")) // Deep sleek navy background
                setStroke(dpToPx(1.3f), Color.parseColor("#8B3DFF")) // Purple glowing border
            }
            background = bg
            elevation = dpToPx(8f).toFloat()

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

        // Active Status Dot (Green)
        val dot = View(context).apply {
            val dotBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#19E68C"))
            }
            background = dotBg
            val lp = LinearLayout.LayoutParams(dpToPx(6f), dpToPx(6f)).apply {
                marginEnd = dpToPx(7f)
            }
            layoutParams = lp
        }
        root.addView(dot)

        // Live Watch Time
        timeTextView = TextView(context).apply {
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            text = "0s"
        }
        root.addView(timeTextView)

        // Bullet divider
        val bullet = TextView(context).apply {
            text = "  •  "
            setTextColor(Color.parseColor("#737C96"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        root.addView(bullet)

        // Reel Count
        countTextView = TextView(context).apply {
            setTextColor(Color.parseColor("#A855F7"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            text = "0 reels"
        }
        root.addView(countTextView)

        overlayView = root
    }

    private fun formatDuration(ms: Long): String {
        val totalSecs = (ms / 1000).coerceAtLeast(0)
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        val hours = mins / 60
        return when {
            hours > 0 -> String.format("%dh %02dm", hours, mins % 60)
            mins > 0 -> String.format("%dm %02ds", mins, secs)
            else -> "${secs}s"
        }
    }
}
