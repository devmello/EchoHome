package app.vesper.feature.system.device

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

/**
 * Reads the Echo's privacy hardware without root (measured on the device, Nov 22 2026; see
 * docs/status/system.md):
 *
 *  - **Mic button**: the `amazon-gating` platform driver exposes the latched hardware state
 *    at `/sys/devices/platform/amazon-gating/state` (world-readable; DT `state-gpios` = gpio-405,
 *    active-low). The button toggles that latch in hardware and also sends KEY_POWER on the
 *    `gating` input device, which Android swallows as the power key until it's remapped.
 *  - **Shutter** (`SW_CAMERA_LENS_COVER`, gpio-keys, gpio-499 active-low): apps get no switch
 *    events, so it's read from (1) WindowManager's policy dump (`mCameraLensCoverState`), which
 *    needs `android.permission.DUMP` granted once with `pm grant` (a development permission), or
 *    (2) debugfs `/sys/kernel/debug/gpio` (world-readable on this userdebug build, SELinux permissive).
 */
internal object HardwareProbes {
    private const val TAG = "VesperSystem"
    const val GATING_STATE = "/sys/devices/platform/amazon-gating/state"
    const val DEBUGFS_GPIO = "/sys/kernel/debug/gpio"
    /** Value of [GATING_STATE] while the mics are gated (the red-light state). To confirm on the device. */
    const val GATING_MUTED_VALUE = 1

    /** null when the file can't be read. */
    fun readGatingMuted(): Boolean? = try {
        File(GATING_STATE).readText().trim().toIntOrNull()?.let { it == GATING_MUTED_VALUE }
    } catch (_: Exception) { null }

    /** Lens covered, or null when no source works. Also reports which source answered. */
    fun readShutterClosed(ctx: Context): Pair<Boolean, String>? {
        if (ctx.checkSelfPermission("android.permission.DUMP") == PackageManager.PERMISSION_GRANTED) {
            val d = dumpService(ctx, "window", arrayOf("policy"))
            if (d != null) {
                parseLensCover(d)?.let { return it to "window-policy" }
                if (!loggedEmptyDump) { loggedEmptyDump = true; Log.i(TAG, "window policy dump: ${d.length} chars, no lens state: ${d.take(160).replace('\n', ' ')}") }
            }
        }
        try {
            parseGpioLine(File(DEBUGFS_GPIO).readText(), "Camera Lens Cover")?.let { high -> return (!high) to "debugfs" }
        } catch (_: Exception) {}
        return null
    }

    /** `mCameraLensCoverState=CAMERA_LENS_COVERED|UNCOVERED|CAMERA_LENS_COVER_ABSENT`. */
    fun parseLensCover(dump: String): Boolean? {
        val m = Regex("mCameraLensCoverState=(\\w+)").find(dump) ?: return null
        return when (m.groupValues[1]) {
            "CAMERA_LENS_COVERED" -> true
            "CAMERA_LENS_UNCOVERED" -> false
            else -> null
        }
    }

    /**
     * Debugfs line ` gpio-499 (                    |Camera Lens Cover   ) in  hi IRQ` → physical level
     * (true = hi). The lens-cover GPIO is active-low, so hi = uncovered.
     */
    fun parseGpioLine(text: String, label: String): Boolean? {
        for (line in text.lineSequence()) {
            val bar = line.indexOf('|')
            val close = line.indexOf(')', bar + 1)
            if (bar < 0 || close < 0) continue
            if (line.substring(bar + 1, close).trim() != label) continue
            val rest = line.substring(close + 1).trim().split(Regex("\\s+"))
            return when (rest.getOrNull(1)) { "hi" -> true; "lo" -> false; else -> null }
        }
        return null
    }

    private var dumpBroken = false
    private var loggedEmptyDump = false

    /** `dumpsys <service> <args>` in-process: needs DUMP; uses the hidden ServiceManager by reflection. */
    fun dumpService(ctx: Context, name: String, args: Array<String>): String? {
        if (dumpBroken) return null
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, name) as? IBinder ?: return null
            val f = File(ctx.cacheDir, "dump-$name.txt")
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE).use { pfd ->
                binder.dump(pfd.fileDescriptor, args)
            }
            f.readText()
        } catch (e: Throwable) {
            Log.w(TAG, "dump $name failed (${e.javaClass.simpleName}: ${e.message}); falling back", e)
            dumpBroken = true
            null
        }
    }
}
