package app.vesper

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.TextView

/**
 * The only screen while [app.vesper.feature.update.UpdateGuard] rolls a crash-looping update
 * back (no feature is loaded, so the broken code doesn't run again).
 */
internal class SafeModeView(context: Context, message: String) : TextView(context) {
    init {
        setBackgroundColor(Color.BLACK)
        setTextColor(Color.WHITE)
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(48, 48, 48, 48)
        text = message
    }
}
