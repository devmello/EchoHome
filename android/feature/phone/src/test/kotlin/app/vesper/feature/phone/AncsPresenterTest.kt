package app.vesper.feature.phone

import app.vesper.core.model.ActivityKind
import app.vesper.core.model.DefaultIslandManager
import app.vesper.core.model.DefaultNotificationFeed
import app.vesper.core.model.IslandAction
import app.vesper.core.model.LiveContent
import app.vesper.feature.phone.ancs.Ancs
import app.vesper.feature.phone.ancs.AncsCategory
import app.vesper.feature.phone.ancs.AncsNotification
import app.vesper.feature.phone.ancs.AncsPresenter
import app.vesper.feature.phone.ancs.AncsSession.Change
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.time.ZoneOffset

class AncsPresenterTest {
    private val feed = DefaultNotificationFeed()
    private val island = DefaultIslandManager()
    private val performed = mutableListOf<Pair<Long, Int>>()
    private val p = AncsPresenter(feed, island, "AA:BB", { "Maya’s iPhone" }, { uid, a -> performed += uid to a }, now = { 1234L })

    init { // as PhoneController wires it
        CoroutineScope(Dispatchers.Unconfined).launch { feed.userDismissals.collect { p.onUserDismissed(it) } }
    }

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
        assertTrue(performed.isEmpty()) // our own removal isn't an Echo dismissal
        feed.dismiss("ancs:AA:BB:7", byUser = true) // a late tap on a banner already gone
        assertTrue(performed.isEmpty())
    }

    @Test fun dismissedOnTheEchoClearsOnThePhone() {
        p.onNotification(msg(), Change.POSTED)
        feed.dismiss("ancs:AA:BB:7", byUser = true) // the banner's Dismiss button
        assertEquals(listOf(7L to Ancs.ACTION_NEGATIVE), performed)
        feed.dismiss("ancs:AA:BB:7", byUser = true)
        assertEquals(1, performed.size)
    }

    @Test fun timeoutOrOtherNonUserDismissDoesntTouchThePhone() {
        p.onNotification(msg(), Change.POSTED)
        feed.dismiss("ancs:AA:BB:7") // the renderer's auto-hide (byUser = false)
        assertTrue(feed.banners.value.isEmpty())
        assertTrue(performed.isEmpty())
    }

    @Test fun ignoresOtherPhonesAndOtherSources() {
        p.onNotification(msg(), Change.POSTED)
        listOf("ancs:CC:DD:7", "android:7", "ancs:AA:BB:x", "ancs-call:AA:BB:7").forEach { feed.dismiss(it, byUser = true) }
        assertTrue(performed.isEmpty())
        feed.dismiss("ancs:AA:BB:7", byUser = true)
        assertEquals(listOf(7L to Ancs.ACTION_NEGATIVE), performed)
    }

    @Test fun neverPerformsForACallEvenIfItsIdIsDismissed() {
        p.onNotification(AncsNotification(3, AncsCategory.INCOMING_CALL, Ancs.FLAG_NEGATIVE_ACTION, title = "Maya", negativeLabel = "Clear"), Change.POSTED)
        listOf("ancs:AA:BB:3", "ancs-call:AA:BB:3").forEach { feed.dismiss(it, byUser = true) }
        assertTrue(performed.isEmpty())
        assertFalse(AncsPresenter.negativeClears(AncsNotification(3, AncsCategory.INCOMING_CALL, Ancs.FLAG_NEGATIVE_ACTION, negativeLabel = "Clear")))
    }

    @Test fun neverPerformsAnUnknownOrDestructiveNegativeAction() {
        p.onNotification(msg(uid = 1, neg = "Delete"), Change.POSTED)
        p.onNotification(msg(uid = 2, neg = "Archive"), Change.POSTED)
        p.onNotification(msg(uid = 3, flags = 0), Change.POSTED)
        listOf(1L, 2L, 3L).forEach { feed.dismiss("ancs:AA:BB:$it", byUser = true) }
        assertTrue(performed.isEmpty())
        assertTrue(AncsPresenter.negativeClears(msg(neg = "")))
        assertTrue(AncsPresenter.negativeClears(msg(neg = "Löschen")))
    }

    @Test fun clearOnDismissCanBeTurnedOff() {
        p.clearOnDismiss = false
        p.onNotification(msg(), Change.POSTED)
        feed.dismiss("ancs:AA:BB:7", byUser = true)
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
        assertTrue(feed.banners.value.isEmpty())
        assertTrue(island.activities.value.isEmpty())
        assertTrue(performed.isEmpty())
    }

    @Test fun otherBannersAreLeftAlone() {
        feed.post(app.vesper.core.model.Banner("android:x", "Clock", 0, null, "Timer", "Done"))
        p.onNotification(msg(), Change.POSTED)
        p.clearAll()
        assertEquals(listOf("android:x"), feed.banners.value.map { it.id })
    }

    @Test fun hiddenAppsDontBannerButCallsStillShow() {
        p.hiddenApps = AncsPresenter.parseAppList(" Messages , com.apple.mobilephone,")
        assertEquals(setOf("messages", "com.apple.mobilephone"), p.hiddenApps)
        p.onNotification(msg(), Change.POSTED)
        assertTrue(feed.banners.value.isEmpty())
        p.onNotification(AncsNotification(3, AncsCategory.INCOMING_CALL, 0, appId = "com.apple.mobilephone", title = "Maya"), Change.POSTED)
        assertEquals(1, island.activities.value.size)
        p.onNotification(msg(uid = 8).copy(appId = "net.whatsapp.WhatsApp", appName = null), Change.POSTED)
        assertEquals("WhatsApp", feed.banners.value.single().appName)
    }

    @Test fun negativeActionBecomesABannerButtonWithThePhonesLabel() {
        p.onNotification(msg(neg = "Clear"), Change.POSTED)
        val a = feed.banners.value.single().actions.single()
        assertEquals("Clear", a.label)
        assertEquals(IslandAction.Style.NORMAL, a.style)
        p.onNotification(msg(uid = 8, neg = "Delete"), Change.POSTED)
        val d = feed.banners.value.first { it.id.endsWith(":8") }.actions.single()
        assertEquals("Delete", d.label)
        assertEquals(IslandAction.Style.DESTRUCTIVE, d.style)
    }

    @Test fun noNegativeFlagMeansNoExtraButtons() {
        p.onNotification(msg(flags = 0), Change.POSTED)
        assertTrue(feed.banners.value.single().actions.isEmpty())
    }

    @Test fun tappingTheButtonPerformsOnceEvenWhenTheBannerThenCloses() {
        p.onNotification(msg(), Change.POSTED)
        val b = feed.banners.value.single()
        b.actions.single().onInvoke()
        feed.dismiss(b.id, byUser = true) // the renderer closes the banner after a tap
        assertEquals(listOf(7L to Ancs.ACTION_NEGATIVE), performed)
    }

    @Test fun positiveActionIsPrimaryAndPerformsPositive() {
        p.onNotification(msg(flags = Ancs.FLAG_POSITIVE_ACTION or Ancs.FLAG_NEGATIVE_ACTION).copy(positiveLabel = "Open"), Change.POSTED)
        val b = feed.banners.value.single()
        assertEquals("Open", b.primaryAction!!.label)
        b.primaryAction!!.onInvoke()
        feed.dismiss(b.id, byUser = true)
        assertEquals(listOf(7L to Ancs.ACTION_POSITIVE), performed)
    }

    private val call = AncsNotification(3, AncsCategory.INCOMING_CALL, Ancs.FLAG_POSITIVE_ACTION or Ancs.FLAG_NEGATIVE_ACTION,
        appId = "com.apple.mobilephone", title = "Maya", positiveLabel = "Answer", negativeLabel = "Decline")

    @Test fun callOffersAnswerOnlyWhenEnabled() {
        assertEquals(listOf("decline"), p.callActivity(call).actions.map { it.id })
        p.offerAnswer = true
        val acts = p.callActivity(call).actions
        assertEquals(listOf("answer", "decline"), acts.map { it.id })
        assertEquals(IslandAction.Style.ACCEPT, acts[0].style)
        acts[0].onInvoke()
        assertEquals(listOf(3L to Ancs.ACTION_POSITIVE), performed)
    }
}
