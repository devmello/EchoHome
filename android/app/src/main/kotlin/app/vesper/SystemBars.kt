package app.vesper

import android.content.Context
import android.util.Log

/**
 * Turns off what Android shows when a top-edge swipe reveals its bars over us (the swipe that
 * opens Control Center): the status bar's clock and icons, the notification shade, and the
 * Back / Home / Recents buttons. Only while EchoHome is in front: [restore] gives other apps (the
 * stock launcher, Android Settings) their bars back.
 *
 * Needs `android.permission.STATUS_BAR` (privileged; allow-listed for the /system copy). Without it
 * (an update over an older /system copy that didn't request it) this logs once and does nothing.
 * The bar's own translucent strip can still flash at the bottom edge: Android has no app-level
 * switch for that.
 */
internal class SystemBars(context: Context) {
    private val sbm: Any? = context.getSystemService("statusbar")
    private var failed = false

    fun hide() = set(DISABLE, DISABLE2)
    fun restore() = set(0, 0)

    private fun set(flags: Int, flags2: Int) {
        if (failed || sbm == null) return
        try {
            sbm.javaClass.getMethod("disable", Int::class.javaPrimitiveType).invoke(sbm, flags)
            sbm.javaClass.getMethod("disable2", Int::class.javaPrimitiveType).invoke(sbm, flags2)
        } catch (t: Throwable) {
            failed = true
            Log.w(TAG, "can't hide the system bars (STATUS_BAR not granted?): ${(t.cause ?: t).javaClass.simpleName}")
        }
    }

    companion object {
        const val TAG = "VesperSystem"
        // StatusBarManager.DISABLE_* / DISABLE2_* (hidden constants, Android 11)
        private const val DISABLE_EXPAND = 0x00010000
        private const val DISABLE_NOTIFICATION_ICONS = 0x00020000
        private const val DISABLE_SYSTEM_INFO = 0x00100000
        private const val DISABLE_HOME = 0x00200000
        private const val DISABLE_BACK = 0x00400000
        private const val DISABLE_CLOCK = 0x00800000
        private const val DISABLE_RECENT = 0x01000000
        private const val DISABLE_SEARCH = 0x02000000
        private const val DISABLE2_QUICK_SETTINGS = 1
        private const val DISABLE2_SYSTEM_ICONS = 2
        private const val DISABLE2_NOTIFICATION_SHADE = 4
        const val DISABLE = DISABLE_EXPAND or DISABLE_NOTIFICATION_ICONS or DISABLE_SYSTEM_INFO or DISABLE_HOME or
            DISABLE_BACK or DISABLE_CLOCK or DISABLE_RECENT or DISABLE_SEARCH
        const val DISABLE2 = DISABLE2_QUICK_SETTINGS or DISABLE2_SYSTEM_ICONS or DISABLE2_NOTIFICATION_SHADE
    }
}
