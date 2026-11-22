package app.vesper.feature.phone

import app.vesper.feature.phone.ancs.Ancs
import app.vesper.feature.phone.ancs.AncsCategory
import app.vesper.feature.phone.ancs.AncsCommand
import app.vesper.feature.phone.ancs.AncsResponse
import app.vesper.feature.phone.ancs.DataSourceAssembler
import app.vesper.feature.phone.ancs.NotificationSourceEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** Byte sequences laid out as in Apple's ANCS spec (Notification Source, Control Point, Data Source). */
class AncsCodecTest {

    @Test fun parsesNotificationSource() {
        // Added, flags Important|PositiveAction|NegativeAction, IncomingCall, count 1, uid 0x01020304
        val e = NotificationSourceEvent.parse(hex("00 1A 01 01 04 03 02 01"))!!
        assertEquals(Ancs.EVENT_ADDED, e.eventId)
        assertEquals(AncsCategory.INCOMING_CALL, e.category)
        assertEquals(1, e.categoryCount)
        assertEquals(0x01020304L, e.uid)
        assertTrue(e.important && e.hasPositiveAction && e.hasNegativeAction)
        assertTrue(!e.silent && !e.preExisting)
    }

    @Test fun parsesFlagsAndUnsignedUid() {
        val e = NotificationSourceEvent.parse(hex("02 05 06 00 FF FF FF FF"))!!
        assertEquals(Ancs.EVENT_REMOVED, e.eventId)
        assertTrue(e.silent && e.preExisting)
        assertEquals(AncsCategory.EMAIL, e.category)
        assertEquals(0xFFFFFFFFL, e.uid)
    }

    @Test fun rejectsShortOrUnknownPackets() {
        assertNull(NotificationSourceEvent.parse(hex("00 00 00 00 01 00 00")))
        assertNull(NotificationSourceEvent.parse(hex("03 00 00 00 01 00 00 00")))
        // Unknown categories map to Other rather than failing.
        assertEquals(AncsCategory.OTHER, NotificationSourceEvent.parse(hex("00 00 2A 01 01 00 00 00"))!!.category)
    }

    @Test fun encodesGetNotificationAttributes() {
        val c = AncsCommand.GetNotificationAttributes(0x0000_0007L, listOf(
            Ancs.ATTR_APP_IDENTIFIER to 0, Ancs.ATTR_TITLE to 64, Ancs.ATTR_SUBTITLE to 64, Ancs.ATTR_MESSAGE to 240, Ancs.ATTR_DATE to 0,
            Ancs.ATTR_POSITIVE_ACTION_LABEL to 0, Ancs.ATTR_NEGATIVE_ACTION_LABEL to 0))
        // CommandID 0, uid LE, AppIdentifier, Title max 64 (LE16), Subtitle max 64, Message max 240, Date, labels
        assertArrayEquals(hex("00 07000000 00 01 4000 02 4000 03 F000 05 06 07"), c.encode())
        assertArrayEquals(c.encode(), AncsCommand.notificationAttributes(7).encode())
    }

    @Test fun encodesGetAppAttributesAndActions() {
        assertArrayEquals(hex("01") + "com.apple.MobileSMS".toByteArray() + hex("00 00"), AncsCommand.GetAppAttributes("com.apple.MobileSMS").encode())
        assertArrayEquals(hex("02 2A000000 01"), AncsCommand.PerformNotificationAction(42, Ancs.ACTION_NEGATIVE).encode())
        assertArrayEquals(hex("02 2A000000 00"), AncsCommand.PerformNotificationAction(42, Ancs.ACTION_POSITIVE).encode())
    }

    @Test fun encodeRoundTripsNotificationSource() {
        val e = NotificationSourceEvent(Ancs.EVENT_MODIFIED, Ancs.FLAG_SILENT, AncsCategory.SOCIAL, 3, 0x80000001L)
        assertEquals(e, NotificationSourceEvent.parse(e.encode()))
    }

    // --- Data Source reassembly ---

    private val request = AncsCommand.GetNotificationAttributes(7, listOf(Ancs.ATTR_APP_IDENTIFIER to 0, Ancs.ATTR_TITLE to 64, Ancs.ATTR_MESSAGE to 240))

    /** A response: uid 7, AppIdentifier "com.apple.MobileSMS", Title "Maya", Message with multi-byte UTF-8. */
    private fun response(message: String = "Landing at 6:40 ✈️ — grab me?"): ByteArray {
        fun attr(id: Int, v: String): ByteArray { val b = v.toByteArray(); return byteArrayOf(id.toByte(), (b.size and 0xFF).toByte(), (b.size shr 8).toByte()) + b }
        return hex("00 07000000") + attr(0, "com.apple.MobileSMS") + attr(1, "Maya") + attr(3, message)
    }

    @Test fun reassemblesAcrossMtuFragments() {
        for (mtu in listOf(23, 27, 64, 185)) {
            val a = DataSourceAssembler()
            a.expect(request)
            val b = response()
            val frags = (b.indices step (mtu - 3)).map { b.copyOfRange(it, minOf(b.size, it + mtu - 3)) }
            val results = frags.map { a.feed(it) }
            results.dropLast(1).forEach { assertEquals("mtu $mtu", DataSourceAssembler.Result.Partial, it) }
            val done = results.last() as DataSourceAssembler.Result.Complete
            val r = done.response as AncsResponse.NotificationAttributes
            assertEquals(7L, r.uid)
            assertEquals("com.apple.MobileSMS", r.attributes[Ancs.ATTR_APP_IDENTIFIER])
            assertEquals("Maya", r.attributes[Ancs.ATTR_TITLE])
            assertEquals("Landing at 6:40 ✈️ — grab me?", r.attributes[Ancs.ATTR_MESSAGE])
            assertTrue(!a.waiting)
        }
    }

    @Test fun reassemblesByteByByteIncludingSplitUtf8() {
        val a = DataSourceAssembler()
        a.expect(request)
        val b = response("héllo wörld ✈️")
        var last: DataSourceAssembler.Result? = null
        for (i in b.indices) last = a.feed(byteArrayOf(b[i]))
        val r = (last as DataSourceAssembler.Result.Complete).response as AncsResponse.NotificationAttributes
        assertEquals("héllo wörld ✈️", r.attributes[Ancs.ATTR_MESSAGE])
    }

    @Test fun emptyAttributesComplete() {
        val a = DataSourceAssembler()
        a.expect(request)
        val r = a.feed(hex("00 07000000 00 0000 01 0000 03 0000")) as DataSourceAssembler.Result.Complete
        assertEquals(mapOf(0 to "", 1 to "", 3 to ""), (r.response as AncsResponse.NotificationAttributes).attributes)
    }

    @Test fun dropsResponsesForAnotherRequest() {
        val a = DataSourceAssembler()
        a.expect(request)
        assertTrue(a.feed(hex("00 08000000 00 0000")) is DataSourceAssembler.Result.Dropped)
        a.expect(request)
        assertTrue(a.feed(hex("01 41 00 00 0000")) is DataSourceAssembler.Result.Dropped)
        a.expect(null)
        assertTrue(a.feed(response()) is DataSourceAssembler.Result.Dropped)
    }

    @Test fun reassemblesAppAttributes() {
        val a = DataSourceAssembler()
        a.expect(AncsCommand.GetAppAttributes("net.whatsapp.WhatsApp"))
        val b = hex("01") + "net.whatsapp.WhatsApp".toByteArray() + hex("00 00 0800") + "WhatsApp".toByteArray()
        assertEquals(DataSourceAssembler.Result.Partial, a.feed(b.copyOfRange(0, 10)))
        assertEquals(DataSourceAssembler.Result.Partial, a.feed(b.copyOfRange(10, 23)))
        val r = a.feed(b.copyOfRange(23, b.size)) as DataSourceAssembler.Result.Complete
        assertEquals(AncsResponse.AppAttributes("net.whatsapp.WhatsApp", mapOf(0 to "WhatsApp")), r.response)
    }
}
