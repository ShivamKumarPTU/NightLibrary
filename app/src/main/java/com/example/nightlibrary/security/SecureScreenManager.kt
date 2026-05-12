package com.example.nightlibrary.security

import android.app.Activity
import android.os.Build
import android.view.WindowManager

object SecureScreenManager {

    fun enable(activity: Activity) {

        activity.window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )



        if (Build.VERSION.SDK_INT >= 34) {
            activity.setRecentsScreenshotEnabled(false)
        }


    }

    fun disable(activity: Activity) {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}