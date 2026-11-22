package com.echobedside

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.echobedside.clock.DigitalClockView
import com.echobedside.clock.Fonts

/**
 * The HOME activity. For now it shows the Digital StandBy face full screen.
 * Plain platform Views (no AndroidX / Compose) to keep RAM and GPU cost low on the Echo.
 */
class MainActivity : Activity() {

    private lateinit var clock: DigitalClockView

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = clock.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A bedside clock stays on while it's in front. (Night mode will dim it, not turn it off.)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setDecorFitsSystemWindows(false)

        Fonts.init(this)
        clock = DigitalClockView(this)
        // Temporary escape hatch until edit mode (long-press) exists: open Android Settings.
        clock.isHapticFeedbackEnabled = true
        clock.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }
        setContentView(clock)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        })
        clock.refresh()
    }

    override fun onStop() {
        unregisterReceiver(timeReceiver)
        super.onStop()
    }

    /** We're the launcher: Back has nowhere to go. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {}
}
