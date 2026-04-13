package com.example.nightlibrary.setting

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.view.*
import android.widget.Toast
import com.example.nightlibrary.MainActivity
import com.example.nightlibrary.R
import com.example.nightlibrary.preferences.SecurityPreferenceManager

class FloatingBubbleView(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val bubbleView = LayoutInflater.from(context)
        .inflate(R.layout.layout_floating_bubble, null)

    private val params = WindowManager.LayoutParams(
        150,
        150,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    )

    private var initialX = 0
    private var initialY = 0
    private var touchX = 0f
    private var touchY = 0f

    init {
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 200

        val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                // Update preference to OFF
                val prefs = SecurityPreferenceManager(context)
                prefs.isFloatingLauncherEnabled = false
                
                // Stop the service which will remove this view
                context.stopService(Intent(context, FloatingLauncherService::class.java))
                
                Toast.makeText(context, "Quick Launcher Disabled", Toast.LENGTH_SHORT).show()
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                openVault()
                return true
            }
        })

        bubbleView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    try {
                        if (bubbleView.windowToken != null) {
                            windowManager.updateViewLayout(bubbleView, params)
                        }
                    } catch (e: Exception) {
                        // View might be detached
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    // Handled by GestureDetector for click/long press
                    true
                }

                else -> false
            }
        }
    }

    fun show() {
        try {
            windowManager.addView(bubbleView, params)
        } catch (e: Exception) {
            // Handle cases where view is already added or permission missing
        }
    }

    fun remove() {
        try {
            if (bubbleView.windowToken != null) {
                windowManager.removeView(bubbleView)
            }
        } catch (e: Exception) {
            // View might already be removed
        }
    }

    private fun openVault() {
        val intent = Intent(context, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.putExtra("from_floating", true)
        context.startActivity(intent)
    }
}
