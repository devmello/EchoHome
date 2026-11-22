package com.echobedside.feature.phone

import android.os.Handler
import android.os.SystemClock
import com.echobedside.core.model.ActivityKind
import com.echobedside.core.model.EchoServices
import com.echobedside.core.model.LiveActivity
import com.echobedside.core.model.LiveContent
import com.echobedside.core.model.MediaControls
import com.echobedside.core.model.MediaSourceKind
import com.echobedside.core.model.MediaState
import com.echobedside.feature.phone.ancs.AncsCategory
import com.echobedside.feature.phone.ancs.AncsPresenter
import com.echobedside.feature.phone.ancs.AncsSession
import com.echobedside.feature.phone.ancs.FakeIPhone
import com.echobedside.feature.phone.audio.AvrcpMapping

/**
 * Debug-only demos (sample data, "Maya’s iPhone" is made up), driven through the real ANCS
 * session + presenter with a [FakeIPhone] answering byte-exact ANCS packets:
 *
 *   adb shell am start -n com.echobedside/.MainActivity --es screen phone-pairing --es phone_demo <kind>
 *
 * kind: banner | mail | missed | call | media | connected | pairing | paired
 */
class PhoneDemo(private val services: EchoServices, private val handler: Handler) {
    private val phone = FakeIPhone(mtu = 23)
    private val device = "Maya’s iPhone"
    private val presenter = AncsPresenter(services.notifications, services.island, "demo", { device }, ::perform)
    private val session: AncsSession = AncsSession(object : AncsSession.Io {
        override fun writeControlPoint(bytes: ByteArray) {
            // Like the radio: the write is acked, then the Data Source fragments arrive.
            handler.postDelayed({
                val (status, frags) = phone.write(bytes)
                session().onWriteResult(status)
                frags.forEachIndexed { i, f -> handler.postDelayed({ session().onDataSource(f) }, 8L * (i + 1)) }
            }, 15)
        }
        override fun armTimeout(ms: Long) {}
        override fun disarmTimeout() {}
    }, presenter::onNotification)

    private fun session(): AncsSession = session
    private var nextUid = 100L

    private fun perform(uid: Long, action: Int) {
        session.performAction(uid, action)
        // The phone removes what was acted on (Decline / Clear).
        handler.postDelayed({ if (phone.notes.containsKey(uid)) session.onNotificationSource(phone.remove(uid)) }, 300)
    }

    fun run(kind: String) {
        when (kind) {
            "banner" -> add(FakeIPhone.Note(nextUid++, AncsCategory.SOCIAL, "com.apple.MobileSMS", "Maya", message = "Landing at 6:40. Can you grab me from arrivals?"))
            "mail" -> add(FakeIPhone.Note(nextUid++, AncsCategory.EMAIL, "com.apple.mobilemail", "Sam Rivera", subtitle = "Saturday hike",
                message = "Trailhead at 8, I’ll bring coffee. Weather looks clear all morning."))
            "missed" -> add(FakeIPhone.Note(nextUid++, AncsCategory.MISSED_CALL, "com.apple.mobilephone", "Maya", message = "Missed Call", positive = "Call Back"))
            "call" -> {
                val n = FakeIPhone.Note(nextUid++, AncsCategory.INCOMING_CALL, "com.apple.mobilephone", "Maya", message = "Incoming Call",
                    positive = "Answer", negative = "Decline")
                add(n)
                handler.postDelayed({ if (phone.notes.containsKey(n.uid)) session.onNotificationSource(phone.remove(n.uid)) }, 30_000)
            }
            "media" -> {
                services.media.publish(AvrcpMapping.SOURCE_ID, MediaState("Slow Tide", "Harbor Lights", "Night Swim", null, 214_000, 61_000,
                    SystemClock.elapsedRealtime(), true, MediaSourceKind.BLUETOOTH, device, 0.5f, AvrcpMapping.route(device)), demoControls)
                connected(audio = true)
            }
            "connected" -> connected(audio = false)
        }
    }

    private fun add(n: FakeIPhone.Note) { session.onNotificationSource(phone.add(n)) }

    private fun connected(audio: Boolean) {
        services.island.publish(LiveActivity(PhoneController.ACTIVITY_ID, ActivityKind.BLUETOOTH_CONNECTED,
            LiveContent.BluetoothConnected(device, audio = audio, notifications = true), staleAfterElapsed = SystemClock.elapsedRealtime() + PhoneController.ANNOUNCE_MS))
    }

    private val demoControls = object : MediaControls {
        override fun play() = update { it.copy(playing = true, positionMs = it.positionNow(), positionSampledAt = SystemClock.elapsedRealtime()) }
        override fun pause() = update { it.copy(playing = false, positionMs = it.positionNow(), positionSampledAt = SystemClock.elapsedRealtime()) }
        override fun next() = update { it.copy(title = "Paper Lanterns", positionMs = 0, positionSampledAt = SystemClock.elapsedRealtime()) }
        override fun previous() = update { it.copy(positionMs = 0, positionSampledAt = SystemClock.elapsedRealtime()) }
        override fun seekTo(positionMs: Long) {}
        override fun setVolume(volume: Float) = update { it.copy(volume = volume) }
        override val canSeek: Boolean get() = false
        private fun update(f: (MediaState) -> MediaState) {
            val cur = services.media.sessions.value.firstOrNull { it.sourceId == AvrcpMapping.SOURCE_ID } ?: return
            services.media.publish(AvrcpMapping.SOURCE_ID, f(cur.state), this)
        }
    }

    companion object {
        fun pairingState(withPrompt: Boolean, canConfirm: Boolean = false) = PhoneState(
            bluetoothOn = true, advertising = true, advertiseOk = true, discoverable = true, pairingMode = true,
            pairing = if (withPrompt) PairingPrompt("Maya’s iPhone", "demo", "482913", 2, canConfirm) else null,
            phones = listOf(PhoneInfo("demo", "Maya’s iPhone", true, notificationsConnected = !withPrompt, audioConnected = !withPrompt)),
            status = if (withPrompt) "Confirm the code on your iPhone" else "Connected to Maya’s iPhone",
        )
    }
}
