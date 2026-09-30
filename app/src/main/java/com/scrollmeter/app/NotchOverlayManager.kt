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

    companion object {
        private const val TAG = "NotchOverlay"
        const val PREF_KEY_NOTCH_OVERLAY = "pref_notch_overlay_enabled"
        const val PREFS_NAME = "scrollmeter_settings"

        private const val DEFAULT_OVERLAY_ALPHA = 0.72f // Translucent, non-distracting

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
        if (!isOverlayEnabled(context) || InstagramAccessibilityService.isAppInForeground) {
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

        // Smoothly show view without re-adding to WindowManager
        if (overlayView != null && isAttached) {
            if (overlayView?.visibility != View.VISIBLE) {
                overlayView?.animate()?.cancel()
                overlayView?.alpha = 0f
                overlayView?.visibility = View.VISIBLE
                overlayView?.animate()
                    ?.alpha(DEFAULT_OVERLAY_ALPHA)
                    ?.setDuration(200)
                    ?.start()
            }
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
        val canDraw = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(context)
        val overlayType = if (canDraw) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
        params.y = getStatusBarHeight() + dpToPx(3f)
        return params
    }

    private fun createFallbackLayoutParams(): WindowManager.LayoutParams {
        val fallbackType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
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
        params.y = getStatusBarHeight() + dpToPx(3f)
        return params
    }

    private fun initOverlay() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE // Initially GONE until first genuine show
            alpha = DEFAULT_OVERLAY_ALPHA
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setPadding(dpToPx(11f), dpToPx(4.5f), dpToPx(11f), dpToPx(4.5f))

            // Sleek Translucent Glass Pill Background with subtle border
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(20f).toFloat()
                setColor(Color.parseColor("#B80A0D18")) // Translucent dark glass (approx 72% opacity)
                setStroke(dpToPx(1f), Color.parseColor("#448B3DFF")) // Soft, subtle accent border
            }
            background = bg
            elevation = dpToPx(2f).toFloat() // Soft minimal elevation, no harsh shadow

            // Tap to open ScrollMeter App
            setOnClickListener {
                try {
                    InstagramAccessibilityService.onAppForegroundedDirect()
                    val intent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {}
            }
        }

        // Active Status Dot (Green, slightly softer)
        val dot = View(context).apply {
            val dotBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#34D399")) // Softer emerald green
            }
            background = dotBg
            val lp = LinearLayout.LayoutParams(dpToPx(5f), dpToPx(5f)).apply {
                marginEnd = dpToPx(6f)
            }
            layoutParams = lp
        }
        root.addView(dot)

        // Live Watch Time
        timeTextView = TextView(context).apply {
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            text = "0s"
        }
        root.addView(timeTextView)

        // Bullet divider
        val bullet = TextView(context).apply {
            text = " • "
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        }
        root.addView(bullet)

        // Reel Count
        countTextView = TextView(context).apply {
            setTextColor(Color.parseColor("#C084FC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
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
