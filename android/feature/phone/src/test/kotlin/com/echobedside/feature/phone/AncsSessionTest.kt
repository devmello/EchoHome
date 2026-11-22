package com.echobedside.feature.phone

import com.echobedside.feature.phone.ancs.Ancs
import com.echobedside.feature.phone.ancs.AncsCategory
import com.echobedside.feature.phone.ancs.AncsNotification
import com.echobedside.feature.phone.ancs.AncsSession
import com.echobedside.feature.phone.ancs.AncsSession.Change
import com.echobedside.feature.phone.ancs.FakeIPhone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The consumer state machine against a byte-exact fake iPhone. */
class AncsSessionTest {

    /** Wires a session to a [FakeIPhone] through an explicit "radio" queue so tests control ordering. */
    class Rig(mtu: Int = 23, val ackAfterResponse: Boolean = false) {
        val phone = FakeIPhone(mtu)
        val events = mutableListOf<Pair<AncsNotification, Change>>()
        val writes = mutableListOf<ByteArray>()
        var timeoutArmed = false
        private val radio = ArrayDeque<() -> Unit>()
        /** If false, writes are recorded but the phone never answers (for timeout tests). */
        var phoneAnswers = true
        var concurrentWrites = 0
        private var outstanding = 0

        val session: AncsSession = AncsSession(object : AncsSession.Io {
            override fun writeControlPoint(bytes: ByteArray) {
                writes += bytes
                outstanding++
                if (outstanding > 1) concurrentWrites++
                if (!phoneAnswers) return
                val (status, frags) = phone.write(bytes)
                val ack = { outstanding--; session.onWriteResult(status) }
                val data = frags.map { f -> { session.onDataSource(f) } }
                if (ackAfterResponse) { data.forEach { radio.addLast(it) }; radio.addLast(ack) } else { radio.addLast(ack); data.forEach { radio.addLast(it) } }
            }
            override fun armTimeout(ms: Long) { timeoutArmed = true }
            override fun disarmTimeout() { timeoutArmed = false }
        }) { n, c -> events += n to c }

        fun ns(bytes: ByteArray) { session.onNotificationSource(bytes); pump() }
        fun pump() { while (radio.isNotEmpty()) radio.removeFirst().invoke() }
    }

    private val maya = FakeIPhone.Note(7, AncsCategory.SOCIAL, "com.apple.MobileSMS", "Maya", message = "Landing at 6:40. Can you grab me from arrivals?")

    @Test fun addedFetchesAttributesThenAppNameOnce() {
        val r = Rig()
        r.ns(r.phone.add(maya))
        assertEquals(1, r.events.size)
        val (n, c) = r.events[0]
        assertEquals(Change.POSTED, c)
        assertEquals("Maya", n.title)
        assertEquals("Landing at 6:40. Can you grab me from arrivals?", n.message)
        assertEquals("com.apple.MobileSMS", n.appId)
        assertEquals("Messages", n.appName)
        assertEquals("Clear", n.negativeLabel)
        assertTrue(n.hasNegativeAction && !n.hasPositiveAction)
        assertEquals(listOf(Ancs.CMD_GET_NOTIFICATION_ATTRIBUTES, Ancs.CMD_GET_APP_ATTRIBUTES), r.writes.map { it[0].toInt() })

        // Second notification from the same app: no second app request.
        r.ns(r.phone.add(maya.copy(uid = 8, message = "At the curb")))
        assertEquals(3, r.writes.size)
        assertEquals("Messages", r.events.last().first.appName)
        assertTrue(r.session.idle)
    }

    @Test fun ackArrivingAfterTheResponseStillSerialisesWrites() {
        val r = Rig(ackAfterResponse = true)
        r.ns(r.phone.add(maya))
        r.ns(r.phone.add(maya.copy(uid = 9)))
        assertEquals(0, r.concurrentWrites)
        assertEquals(2, r.events.size)
    }

    @Test fun preExistingIsTrackedButNotFetched() {
        val r = Rig()
        r.ns(r.phone.add(maya, preExisting = true))
        assertEquals(Change.KNOWN, r.events.single().second)
        assertTrue(r.writes.isEmpty())
        // Removing it still reports REMOVED so any UI can clean up.
        r.ns(r.phone.remove(7))
        assertEquals(Change.REMOVED, r.events.last().second)
    }

    @Test fun preExistingIncomingCallIsStillShown() {
        val r = Rig()
        val call = FakeIPhone.Note(3, AncsCategory.INCOMING_CALL, "com.apple.mobilephone", "Maya", positive = "Answer", negative = "Decline")
        r.ns(r.phone.add(call, preExisting = true))
        assertEquals(Change.POSTED, r.events.single().second)
        assertEquals("Decline", r.events.single().first.negativeLabel)
    }

    @Test fun modifiedRefetches() {
        val r = Rig()
        r.ns(r.phone.add(maya))
        r.ns(r.phone.modify(maya.copy(message = "Actually 7:10")))
        assertEquals(Change.MODIFIED, r.events.last().second)
        assertEquals("Actually 7:10", r.events.last().first.message)
    }

    @Test fun removedBeforeTheReplyCancelsTheFetch() {
        val r = Rig()
        r.phoneAnswers = false
        r.session.onNotificationSource(r.phone.add(maya))
        r.session.onNotificationSource(r.phone.add(maya.copy(uid = 8)))
        // uid 8 is queued behind 7; the phone removes it.
        r.session.onNotificationSource(r.phone.remove(8))
        assertEquals(Change.REMOVED, r.events.single().second)
        r.session.onTimeout() // 7 never answered
        assertTrue(r.session.idle)
        assertEquals(1, r.writes.size)
    }

    @Test fun errorStatusMovesOn() {
        val r = Rig()
        // A notification the phone no longer has: the fake answers 0xA2 Invalid Parameter.
        r.session.onNotificationSource(com.echobedside.feature.phone.ancs.NotificationSourceEvent(Ancs.EVENT_ADDED, 0, AncsCategory.SOCIAL, 1, 55).encode())
        r.pump()
        assertTrue(r.events.isEmpty())
        assertTrue(r.session.idle)
        r.ns(r.phone.add(maya))
        assertEquals("Maya", r.events.single().first.title)
    }

    @Test fun performActionWritesTheCommand() {
        val r = Rig()
        r.ns(r.phone.add(maya))
        r.session.performAction(7, Ancs.ACTION_NEGATIVE)
        r.pump()
        assertEquals(listOf(7L to Ancs.ACTION_NEGATIVE), r.phone.actions)
        assertTrue(r.session.idle)
        // Unknown uids are ignored.
        r.session.performAction(999, Ancs.ACTION_NEGATIVE)
        assertEquals(1, r.phone.actions.size)
    }

    @Test fun silentAndImportantFlagsPassThrough() {
        val r = Rig()
        r.ns(r.phone.add(maya.copy(extraFlags = Ancs.FLAG_SILENT or Ancs.FLAG_IMPORTANT)))
        val n = r.events.single().first
        assertTrue(n.silent && n.important)
    }

    @Test fun longMessagesAreTruncatedToTheRequestedLength() {
        val r = Rig(mtu = 185)
        r.ns(r.phone.add(maya.copy(message = "é".repeat(400))))
        val m = r.events.single().first.message
        assertTrue(m.toByteArray().size <= 240)
        assertTrue(m.all { it == 'é' })
    }

    @Test fun resetForgetsTheConnection() {
        val r = Rig()
        r.phoneAnswers = false
        r.session.onNotificationSource(r.phone.add(maya))
        r.session.reset()
        assertTrue(r.session.idle)
        assertTrue(r.session.notifications.isEmpty())
        assertTrue(!r.timeoutArmed)
    }

    @Test fun appNameCacheSurvivesReset() {
        val r = Rig()
        r.session.rememberAppName("com.apple.MobileSMS", "Messages")
        r.ns(r.phone.add(maya))
        assertEquals(1, r.writes.size)
        assertEquals("Messages", r.events.single().first.appName)
    }
}
