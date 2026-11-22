package app.vesper.feature.home.doorbell

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import app.vesper.core.model.ActivityKind
import app.vesper.core.model.Banner
import app.vesper.core.model.HaEntity
import app.vesper.core.model.HaException
import app.vesper.core.model.HomeAssistant
import app.vesper.core.model.IslandAction
import app.vesper.core.model.IslandManager
import app.vesper.core.model.LiveActivity
import app.vesper.core.model.LiveContent
import app.vesper.core.model.NotificationFeed
import app.vesper.core.model.SettingsStore
import app.vesper.feature.home.HomeSettings
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Optional Home Assistant doorbell (only installed when HA is configured).
 *
 * Watches one entity from HA's live state (no extra subscription): a `binary_sensor` turning on,
 * or an `event.*` entity (device class doorbell) getting a new timestamp. On a ring it posts a
 * critical banner ("Show camera") and a DOORBELL live activity with a `camera_proxy` snapshot,
 * refreshed every 3 s for 30 s, and Talk / Unlock / Dismiss actions. Agent B renders both.
 */
class DoorbellWatcher(
    private val ha: HomeAssistant,
    private val settings: SettingsStore,
    private val feed: NotificationFeed,
    private val island: IslandManager,
    private val scope: CoroutineScope,
    private val decode: (ByteArray) -> Bitmap? = ::decodeSnapshot,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val snapshotEveryMs: Long = 3000,
    private val liveForMs: Long = 120_000,
) {
    private var job: Job? = null
    private var snapJob: Job? = null
    private var lastRing = -1L

    fun start() {
        if (job != null) return
        job = scope.launch {
            var prev: HaEntity? = null
            ha.entities.map { m -> doorbellId(m)?.let { m[it] } }.distinctUntilChanged { a, b -> a === b }.collect { e ->
                if (e != null && isRing(prev, e)) ring(e)
                prev = e
            }
        }
    }

    fun stop() { job?.cancel(); job = null; snapJob?.cancel() }

    fun doorbellId(m: Map<String, HaEntity> = ha.entities.value): String? = HomeSettings.string(settings, HomeSettings.DOORBELL_ENTITY)
        ?: m.values.firstOrNull { it.domain == "event" && it.attributes.optString("device_class") == "doorbell" }?.entityId
        ?: m.keys.filter { (it.startsWith("binary_sensor.") || it.startsWith("event.")) && ("doorbell" in it || it.endsWith("_ding")) }.minOrNull()

    private fun cameraId(bell: HaEntity): String? {
        HomeSettings.string(settings, HomeSettings.DOORBELL_CAMERA)?.let { return it }
        val cams = ha.entities.value.keys.filter { it.startsWith("camera.") }.sorted()
        val stem = bell.entityId.substringAfter('.').removeSuffix("_ding").removeSuffix("_doorbell").removeSuffix("_button")
        return cams.firstOrNull { it.substringAfter('.').startsWith(stem) || stem.startsWith(it.substringAfter('.')) } ?: cams.firstOrNull()
    }

    private fun lockId(): String? = HomeSettings.string(settings, HomeSettings.DOORBELL_LOCK)
        ?: ha.entities.value.keys.filter { it.startsWith("lock.") }.minOrNull()

    /** "Front door ding" → "Front door". */
    fun placeName(bell: HaEntity): String = bell.friendlyName
        .replace(Regex("\\s+(ding|doorbell|button|ring|pressed)$", RegexOption.IGNORE_CASE), "").trim()
        .ifEmpty { "Front door" }

    /** Rings now: also used by the demo and the debug launch extra. */
    fun ring(bell: HaEntity) {
        val now = elapsed()
        if (lastRing >= 0 && now - lastRing < 10_000) return // HA can report a press twice
        lastRing = now
        val place = placeName(bell)
        val camera = cameraId(bell)
        val actions = ArrayList<IslandAction>()
        HomeSettings.string(settings, HomeSettings.DOORBELL_TALK)?.let { script ->
            actions += IslandAction("door.talk", "Talk", IslandAction.Style.ACCEPT) { call("script", "turn_on", script) }
        }
        lockId()?.let { lock -> actions += IslandAction("door.unlock", "Unlock") { call("lock", "unlock", lock); dismiss() } }
        actions += IslandAction("door.dismiss", "Dismiss") { dismiss() }

        island.publish(LiveActivity(ACTIVITY_ID, ActivityKind.DOORBELL, LiveContent.Doorbell(place, null), actions,
            alert = true, staleAfterElapsed = now + liveForMs))
        feed.post(Banner(
            id = BANNER_ID, appName = place.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } },
            appColor = 0xFFFF9F0A.toInt(), glyph = "doorbell",
            title = "Someone’s at the door", body = "Doorbell rang · tap for the live camera", critical = true,
            primaryAction = IslandAction("door.camera", "Show camera", IslandAction.Style.PRIMARY) { showCamera() },
        ))
        snapJob?.cancel()
        if (camera != null) snapJob = scope.launch {
            val until = elapsed() + 30_000
            while (elapsed() < until && island.activities.value.any { it.id == ACTIVITY_ID }) {
                try {
                    val bmp = decode(ha.fetchBytes("/api/camera_proxy/$camera"))
                    // The old frame isn't recycled: the island may still be drawing it. GC frees it.
                    if (bmp != null) island.update(ACTIVITY_ID) { a -> a.copy(content = LiveContent.Doorbell(place, bmp)) }
                } catch (_: HaException) { } catch (_: IOException) { }
                delay(snapshotEveryMs)
            }
        }
    }

    /** The banner's "Show camera": bring the expanded island (live thumbnail) back. */
    fun showCamera() {
        feed.dismiss(BANNER_ID)
        island.activities.value.firstOrNull { it.id == ACTIVITY_ID }?.let { island.publish(it.copy(alert = true)) }
    }

    fun dismiss() {
        snapJob?.cancel()
        feed.dismiss(BANNER_ID)
        island.end(ACTIVITY_ID)
    }

    private fun call(domain: String, service: String, id: String) {
        scope.launch { try { ha.callService(domain, service, id) } catch (_: HaException) { } }
    }

    companion object {
        const val ACTIVITY_ID = "home:doorbell"
        const val BANNER_ID = "home:doorbell"

        /** binary_sensor off → on; event entity: a new timestamp state after the first load. */
        fun isRing(prev: HaEntity?, e: HaEntity): Boolean = when (e.domain) {
            "binary_sensor" -> e.state == "on" && prev != null && prev.state != "on"
            "event" -> prev != null && e.state != prev.state && e.state != "unknown" && e.state != "unavailable"
            else -> false
        }

        /** Decodes at ≤ 480 px wide (the island thumbnail is 230 px). */
        fun decodeSnapshot(bytes: ByteArray): Bitmap? {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var s = 1
            while (o.outWidth / (s * 2) >= 480) s *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s; inPreferredConfig = Bitmap.Config.RGB_565 })
        }
    }
}
