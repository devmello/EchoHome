package app.vesper.feature.phone

import android.os.Handler
import android.os.SystemClock
import app.vesper.core.model.ActivityKind
import app.vesper.core.model.VesperServices
import app.vesper.core.model.LiveActivity
import app.vesper.core.model.LiveContent
import app.vesper.core.model.MediaControls
import app.vesper.core.model.MediaSourceKind
import app.vesper.core.model.MediaState
import app.vesper.feature.phone.ams.Ams
import app.vesper.feature.phone.ams.AmsMediaSource
import app.vesper.feature.phone.ams.AmsSession
import app.vesper.feature.phone.ams.ArtworkLookup
import app.vesper.feature.phone.ams.FakeAmsPhone
import app.vesper.feature.phone.ancs.AncsCategory
import app.vesper.feature.phone.ancs.AncsPresenter
import app.vesper.feature.phone.ancs.AncsSession
import app.vesper.feature.phone.ancs.FakeIPhone
import app.vesper.feature.phone.audio.AvrcpMapping
import kotlinx.coroutines.launch

/**
 * Debug-only demos (sample data, "Maya’s iPhone" is made up), driven through the real ANCS
 * session + presenter with a [FakeIPhone] answering byte-exact ANCS packets:
 *
 *   adb shell am start -n app.vesper/.MainActivity --es screen phone-pairing --es phone_demo <kind>
 *
 * kind: banner | mail | mail-actions | battery | battery-low | missed | call | media | ams | ams-podcast | connected | pairing | paired
 *
 * `ams`: the real [AmsSession] + [AmsMediaSource] fed by a [FakeAmsPhone] at MTU 23 (truncated
 * values fetched back through fake Entity Attribute reads), then Now Playing opens. The album art
 * comes from the real iTunes lookup when that setting is on (sample song).
 */
class PhoneDemo(private val services: VesperServices, private val handler: Handler) {
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
            // Fake battery levels through the real BatteryPolicy + island path.
            "battery" -> PhoneFeature.controller?.debugBattery(device, listOf(72))
            "battery-low" -> PhoneFeature.controller?.debugBattery(device, listOf(40, 20, 19, 10, 9), stepMs = 7_000L)
            "mail-actions" -> add(FakeIPhone.Note(nextUid++, AncsCategory.EMAIL, "com.apple.mobilemail", "Sam Rivera", subtitle = "Saturday hike",
                message = "Trailhead at 8.", positive = "Open", negative = "Delete"))
            "ams" -> ams(podcast = false)
            "ams-podcast" -> ams(podcast = true)
        }
    }

    // --- AMS ---

    private val amsPhone = FakeAmsPhone(mtu = 23)
    private var amsSource: AmsMediaSource? = null

    private fun ams(podcast: Boolean) {
        val src = amsSource ?: AmsMediaSource({ services.media }, { device }, { false },
            ArtworkLookup(services.scope, { services.settings.getBoolean(PhoneController.KEY_ART_LOOKUP, true) }), {}).also { s ->
            amsSource = s
            services.scope.launch { services.media.sessions.collect { s.onSessions(it) } }
        }
        val session = src.attach(object : AmsSession.Io {
            // Like the radio: write, then the read answers a few ms later.
            override fun readAttribute(entity: Int, attribute: Int) {
                amsPhone.writeAttribute(Ams.attributeRequest(entity, attribute))
                handler.postDelayed({ src.session?.onAttributeRead(amsPhone.readAttribute()) }, 20)
            }
            override fun command(id: Int) { handler.postDelayed({ amsCommand(id) }, 30) }
        }, SystemClock::elapsedRealtime)
        val stream = if (podcast) {
            amsPhone.commands = setOf(Ams.CMD_TOGGLE_PLAY_PAUSE, Ams.CMD_SKIP_FORWARD, Ams.CMD_SKIP_BACKWARD, Ams.CMD_VOLUME_UP, Ams.CMD_VOLUME_DOWN)
            amsPhone.track("Episode 212: Night Owls and the Science of Sleep", "The Slow Science Show", "", 3725.5) +
                amsPhone.player("Podcasts", Ams.STATE_PLAYING, 1803.25, 0.4f)
        } else {
            amsPhone.track("Holocene", "Bon Iver", "Bon Iver, Bon Iver", 336.933) + amsPhone.player("Spotify", Ams.STATE_PLAYING, 61.25, 0.56f)
        }
        session.onCommands(amsPhone.commandList())
        stream.forEachIndexed { i, b -> handler.postDelayed({ src.session?.onEntityUpdate(b) }, 10L * (i + 1)) }
        handler.postDelayed({ services.navigator.show("nowplaying") }, 300)
    }

    /** The fake iPhone acts on a Remote Command and reports back, like iOS. */
    private fun amsCommand(id: Int) {
        val s = amsSource?.session ?: return
        val st = s.state
        val pos = (st.elapsedMs + if (st.playing) SystemClock.elapsedRealtime() - st.sampledAt else 0) / 1000.0
        when (id) {
            Ams.CMD_PLAY, Ams.CMD_PAUSE, Ams.CMD_TOGGLE_PLAY_PAUSE -> {
                val play = if (id == Ams.CMD_TOGGLE_PLAY_PAUSE) !st.playing else id == Ams.CMD_PLAY
                s.onEntityUpdate(amsPhone.playback(if (play) Ams.STATE_PLAYING else Ams.STATE_PAUSED, pos))
            }
            Ams.CMD_NEXT_TRACK -> (amsPhone.track("Towers", "Bon Iver", "Bon Iver, Bon Iver", 188.4) + amsPhone.playback(Ams.STATE_PLAYING, 0.0)).forEach(s::onEntityUpdate)
            Ams.CMD_PREVIOUS_TRACK -> s.onEntityUpdate(amsPhone.playback(Ams.STATE_PLAYING, 0.0))
            Ams.CMD_VOLUME_UP, Ams.CMD_VOLUME_DOWN -> {
                val v = ((st.volume ?: 0.5f) + if (id == Ams.CMD_VOLUME_UP) 1f / 16 else -1f / 16).coerceIn(0f, 1f)
                s.onEntityUpdate(amsPhone.update(Ams.ENTITY_PLAYER, Ams.PLAYER_VOLUME, "%.4f".format(java.util.Locale.US, v)))
            }
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
