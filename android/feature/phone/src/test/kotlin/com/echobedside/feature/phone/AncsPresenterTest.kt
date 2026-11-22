package com.echobedside.feature.phone

import com.echobedside.core.model.ActivityKind
import com.echobedside.core.model.DefaultIslandManager
import com.echobedside.core.model.DefaultNotificationFeed
import com.echobedside.core.model.IslandAction
import com.echobedside.core.model.LiveContent
import com.echobedside.feature.phone.ancs.Ancs
import com.echobedside.feature.phone.ancs.AncsCategory
import com.echobedside.feature.phone.ancs.AncsNotification
import com.echobedside.feature.phone.ancs.AncsPresenter
import com.echobedside.feature.phone.ancs.AncsSession.Change
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class AncsPresenterTest {
    private val feed = DefaultNotificationFeed()
    private val island = DefaultIslandManager()
    private val performed = mutableListOf<Pair<Long, Int>>()
    private val p = AncsPresenter(feed, island, "AA:BB", { "Maya’s iPhone" }, { uid, a -> performed += uid to a }, now = { 1234L })

    private val clearFlags = Ancs.FLAG_NEGATIVE_ACTION
    private fun msg(uid: Long = 7, flags: Int = clearFlags, neg: String = "Clear") = AncsNotification(uid, AncsCategory.SOCIAL, flags,
        appId = "com.apple.MobileSMS", appName = "Messages", title = "Maya", message = "Landing at 6:40", date = "20261122T064000", negativeLabel = neg)

    @Test fun messageBecomesABannerTaggedWithThePhone() {
        p.onNotification(msg(), Change.POSTED)
        val b = feed.banners.value.single()
        assertEquals("ancs:AA:BB:7", b.id)
        assertEquals("Messages", b.appName)
        assertEquals(0xFF30D158.toInt(), b.appColor)
        assertEquals("message", b.glyph)
        assertEquals("Maya", b.title)
        assertEquals("Landing at 6:40", b.body)
        assertEquals("com.apple.MobileSMS", b.sourcePackage)
        assertEquals("Maya’s iPhone", b.sourceDevice)
        assertFalse(b.critical)
        assertNull(b.primaryAction)
    }

    @Test fun subtitleAndMessageJoinAndUnknownAppsFallBack() {
        p.onNotification(AncsNotification(9, AncsCategory.EMAIL, 0, appId = "com.example.MailPro", title = "Sam", subtitle = "Saturday hike", message = "Trailhead at 8"), Change.POSTED)
        val b = feed.banners.value.single()
        assertEquals("Saturday hike · Trailhead at 8", b.body)
        assertEquals("MailPro", b.appName) // no DisplayName from the phone: from the bundle id
        assertEquals("mail", b.glyph)
    }

    @Test fun phoneDisplayNameWinsOverTheCatalog() {
        p.onNotification(msg().copy(appId = "net.whatsapp.WhatsApp", appName = "WhatsApp Business"), Change.POSTED)
        assertEquals("WhatsApp Business", feed.banners.value.single().appName)
        assertEquals(0xFF25D366.toInt(), feed.banners.value.single().appColor)
    }

    @Test fun dateIsParsedInTheLocalZone() {
        assertEquals(java.time.LocalDateTime.of(2026, 11, 22, 6, 40).toInstant(ZoneOffset.UTC).toEpochMilli(),
            AncsPresenter.parseDate("20261122T064000", ZoneOffset.UTC))
        assertNull(AncsPresenter.parseDate("garbage"))
        p.onNotification(msg().copy(date = ""), Change.POSTED)
        assertEquals(1234L, feed.banners.value.single().postedAtMillis)
    }

    @Test fun silentNotificationsDontBannerByDefault() {
        p.onNotification(msg(flags = Ancs.FLAG_SILENT), Change.POSTED)
        assertTrue(feed.banners.value.isEmpty())
        p.showSilent = true
        p.onNotification(msg(flags = Ancs.FLAG_SILENT), Change.POSTED)
        assertEquals(1, feed.banners.value.size)
    }

    @Test fun knownPreExistingDoesNothing() {
        p.onNotification(msg(flags = Ancs.FLAG_PRE_EXISTING), Change.KNOWN)
        assertTrue(feed.banners.value.isEmpty())
    }

    @Test fun removedOnThePhoneDismissesHere() {
        p.onNotification(msg(), Change.POSTED)
        p.onNotification(msg(), Change.REMOVED)
        assertTrue(feed.banners.value.isEmpty())
        p.onFeedChanged()
        assertTrue(performed.isEmpty()) // our own removal isn't an Echo dismissal
    }

    @Test fun dismissedOnTheEchoClearsOnThePhone() {
        p.onNotification(msg(), Change.POSTED)
        feed.dismiss("ancs:AA:BB:7") // the banner's Dismiss button
        p.onFeedChanged()
        assertEquals(listOf(7L to Ancs.ACTION_NEGATIVE), performed)
        p.onFeedChanged()
        assertEquals(1, performed.size)
    }

    @Test fun neverPerformsAnUnknownOrDestructiveNegativeAction() {
        p.onNotification(msg(uid = 1, neg = "Delete"), Change.POSTED)
        p.onNotification(msg(uid = 2, neg = "Archive"), Change.POSTED)
        p.onNotification(msg(uid = 3, flags = 0), Change.POSTED)
        listOf(1L, 2L, 3L).forEach { feed.dismiss("ancs:AA:BB:$it") }
        p.onFeedChanged()
        assertTrue(performed.isEmpty())
        assertTrue(AncsPresenter.negativeClears(msg(neg = "")))
        assertTrue(AncsPresenter.negativeClears(msg(neg = "Löschen")))
    }

    @Test fun clearOnDismissCanBeTurnedOff() {
        p.clearOnDismiss = false
        p.onNotification(msg(), Change.POSTED)
        feed.dismiss("ancs:AA:BB:7"); p.onFeedChanged()
        assertTrue(performed.isEmpty())
    }

    @Test fun incomingCallIsAnIslandCallWithDeclineOnly() {
        val call = AncsNotification(3, AncsCategory.INCOMING_CALL, Ancs.FLAG_POSITIVE_ACTION or Ancs.FLAG_NEGATIVE_ACTION,
            appId = "com.apple.mobilephone", title = "Maya", message = "Incoming Call", positiveLabel = "Answer", negativeLabel = "Decline")
        p.onNotification(call, Change.POSTED)
        assertTrue(feed.banners.value.isEmpty())
        val a = island.activities.value.single()
        assertEquals(ActivityKind.CALL, a.kind)
        assertTrue(a.alert)
        assertEquals(LiveContent.Call("Maya", "Answer on iPhone", video = false), a.content)
        assertEquals(listOf("decline"), a.actions.map { it.id })
        assertEquals(IslandAction.Style.DESTRUCTIVE, a.actions.single().style)
        a.actions.single().onInvoke()
        assertEquals(listOf(3L to Ancs.ACTION_NEGATIVE), performed)
        p.onNotification(call, Change.REMOVED)
        assertTrue(island.activities.value.isEmpty())
    }

    @Test fun callWithoutCallerShowsUnknown() {
        p.onNotification(AncsNotification(4, AncsCategory.INCOMING_CALL, 0), Change.POSTED)
        assertEquals("Unknown caller", (island.activities.value.single().content as LiveContent.Call).caller)
        assertTrue(island.activities.value.single().actions.isEmpty())
    }

    @Test fun missedCallIsABannerWithCallBack() {
        p.onNotification(AncsNotification(5, AncsCategory.MISSED_CALL, Ancs.FLAG_POSITIVE_ACTION or Ancs.FLAG_NEGATIVE_ACTION,
            appId = "com.apple.mobilephone", title = "Maya", positiveLabel = "Call Back", negativeLabel = "Clear"), Change.POSTED)
        val b = feed.banners.value.single()
        assertEquals("Phone", b.appName)
        assertEquals("call", b.glyph)
        assertEquals("Missed call", b.body)
        assertEquals("Call Back", b.primaryAction!!.label)
        assertEquals(IslandAction.Style.PRIMARY, b.primaryAction!!.style)
        b.primaryAction!!.onInvoke()
        assertEquals(listOf(5L to Ancs.ACTION_POSITIVE), performed)
        assertTrue(island.activities.value.isEmpty())
    }

    @Test fun clearAllTakesEverythingDownWithoutTouchingThePhone() {
        p.onNotification(msg(), Change.POSTED)
        p.onNotification(AncsNotification(3, AncsCategory.INCOMING_CALL, 0, title = "Maya"), Change.POSTED)
        p.clearAll()
        p.onFeedChanged()
        assertTrue(feed.banners.value.isEmpty())
        assertTrue(island.activities.value.isEmpty())
        assertTrue(performed.isEmpty())
    }

    @Test fun otherBannersAreLeftAlone() {
        feed.post(com.echobedside.core.model.Banner("android:x", "Clock", 0, null, "Timer", "Done"))
        p.onNotification(msg(), Change.POSTED)
        p.clearAll()
        assertEquals(listOf("android:x"), feed.banners.value.map { it.id })
    }
}
