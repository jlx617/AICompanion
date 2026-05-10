package com.ai.companion.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

class FloatingWindow(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var floatingView: View? = null
    private var textView: TextView? = null
    private var isShowing = false
    private var onTapCallback: (() -> Unit)? = null
    private var longPressHandler: android.os.Handler? = null
    private var longPressRunnable: Runnable? = null

    private val layoutParams: WindowManager.LayoutParams
        get() {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            return WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 16
                y = 200
            }
        }

    fun show(suggestion: String) {
        if (isShowing) {
            updateText(suggestion)
            return
        }

        val container = FrameLayout(context).apply {
            setBackgroundResource(android.R.drawable.toast_frame)
            setBackgroundColor(0xE63399FF.toInt())
            setPadding(48, 32, 48, 32)
        }

        val tv = TextView(context).apply {
            text = suggestion
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setLineSpacing(4f, 1f)
        }

        container.addView(tv)
        textView = tv

        // Make the floating window draggable
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        val params = layoutParams

        longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
        longPressRunnable = Runnable { dismiss() }

        container.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    longPressHandler?.postDelayed(longPressRunnable!!, 500)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val diffX = Math.abs(event.rawX - initialTouchX)
                    val diffY = Math.abs(event.rawY - initialTouchY)
                    if (diffX > 10 || diffY > 10) {
                        longPressHandler?.removeCallbacks(longPressRunnable!!)
                    }
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(container, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    longPressHandler?.removeCallbacks(longPressRunnable!!)
                    val diffX = Math.abs(event.rawX - initialTouchX)
                    val diffY = Math.abs(event.rawY - initialTouchY)
                    if (diffX < 10 && diffY < 10) {
                        // It's a tap, not a drag - invoke the tap callback
                        onTapCallback?.invoke()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(container, params)
            floatingView = container
            isShowing = true
        } catch (e: Exception) {
            // WindowManager may throw if permission is not granted
        }
    }

    fun updateText(suggestion: String) {
        textView?.text = suggestion
    }

    fun setOnTapCallback(callback: () -> Unit) {
        onTapCallback = callback
    }

    fun dismiss() {
        if (!isShowing) return
        longPressHandler?.removeCallbacks(longPressRunnable!!)
        try {
            floatingView?.let {
                windowManager.removeView(it)
            }
        } catch (e: Exception) {
            // View may already be removed
        }
        floatingView = null
        textView = null
        isShowing = false
    }

    val showing: Boolean
        get() = isShowing
}
