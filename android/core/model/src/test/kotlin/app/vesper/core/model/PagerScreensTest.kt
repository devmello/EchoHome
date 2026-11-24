package app.vesper.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PagerScreensTest {
    private fun spec(id: String, order: Int, inPager: Boolean = true) =
        ScreenSpec(id, order, inPager, title = id.replaceFirstChar { it.uppercase() }) { error("never built in tests") }

    /** Today's pager, in registration order (not sorted), plus screens that aren't pages. */
    private val today = listOf(
        spec("photos", 70), spec("standby", 0), spec("widgets", 5), spec("nowplaying", 10), spec("sleep", 15),
        spec("radio", 16), spec("alarms", 25), spec("timers", 30), spec("lists", 35), spec("commute", 40),
        spec("stocks", 50), spec("alarm", 1000, inPager = false), spec("radar", 55, inPager = false),
    )
    private val defaultIds = listOf("standby", "widgets", "nowplaying", "sleep", "radio", "alarms", "timers", "lists", "commute", "stocks", "photos")

    private fun pages(stored: String, screens: List<ScreenSpec> = today) = PagerScreens.pages(screens, stored).map { it.id }

    @Test fun defaultsAreTodaysOrderAndSet() {
        assertEquals(defaultIds, pages(""))
        assertEquals(defaultIds, pages("  , ,"))
    }

    @Test fun optionsLeaveOutStandByAndOffPagerScreens() {
        val o = PagerScreens.options(today)
        assertEquals(defaultIds.drop(1), o.map { it.first })
        assertEquals("Widgets", o.first().second)
    }

    @Test fun hidesAndReorders() {
        assertEquals(listOf("standby", "photos", "widgets", "alarms"),
            pages("photos,widgets,-nowplaying,-sleep,-radio,alarms,-timers,-lists,-commute,-stocks"))
    }

    @Test fun standByIsAlwaysFirstAndCantBeHidden() {
        assertEquals("standby", pages("photos,-standby,widgets").first())
        assertEquals(listOf("standby", "photos", "widgets"), pages("photos,-standby,widgets").take(3))
    }

    @Test fun everythingHiddenLeavesStandBy() {
        val all = defaultIds.drop(1).joinToString(",") { "-$it" }
        assertEquals(listOf("standby"), pages(all))
    }

    @Test fun unknownIdsAreIgnored() {
        assertEquals(defaultIds, pages("gone,-old,widgets,nowplaying"))
        // a screen that isn't a page (opened only by navigation) never becomes one
        assertEquals(defaultIds, pages("alarm,radar"))
    }

    @Test fun firstOfDuplicatesWins() {
        assertEquals(listOf("standby", "widgets", "stocks"), pages("widgets,-widgets,-nowplaying,-sleep,-radio,-alarms,-timers,-lists,-commute,stocks,-photos,photos"))
    }

    @Test fun newScreenFromALaterVersionAppearsAtItsDefaultPlace() {
        val stored = "photos,widgets,-nowplaying,sleep,radio,alarms,timers,lists,commute,stocks" // saved before "weather" existed
        val later = today + spec("weather", 12) // default place: after nowplaying
        assertEquals(listOf("standby", "photos", "widgets", "weather", "sleep", "radio", "alarms", "timers", "lists", "commute", "stocks"),
            pages(stored, later))
    }

    @Test fun newScreensAtTheStartGoFirstInDefaultOrder() {
        val later = today + spec("a", 1) + spec("b", 2)
        assertEquals(listOf("standby", "a", "b", "photos", "widgets"), pages("photos,widgets", later).take(5))
    }

    @Test fun unconfiguredScreenStaysOutAndKeepsItsPlaceForLater() {
        // saved with Home Assistant set up, then HA was removed, then set up again
        val stored = "home,widgets,nowplaying,sleep,radio,alarms,timers,lists,commute,stocks,-photos"
        assertEquals(listOf("standby", "widgets", "nowplaying"), pages(stored).take(3)) // no Home Assistant: no "home"
        val withHa = today + spec("home", 20)
        assertEquals(listOf("standby", "home", "widgets"), pages(stored, withHa).take(3))
        assertEquals(false, "photos" in pages(stored, withHa))
        // never saved: Home takes its default place
        assertEquals(listOf("standby", "widgets", "nowplaying", "sleep", "radio", "home", "alarms"), pages("", withHa).take(7))
    }

    @Test fun noStandByRegistered() {
        assertEquals(listOf("widgets", "nowplaying"), pages("", listOf(spec("nowplaying", 10), spec("widgets", 5))))
        assertEquals(emptyList<String>(), pages("", emptyList()))
    }

    @Test fun orderValueRoundTrip() {
        val items = OrderValue.parse(" a , -b,,- c ,-")
        assertEquals(listOf(OrderValue.Item("a", true), OrderValue.Item("b", false), OrderValue.Item("c", false)), items)
        assertEquals("a,-b,-c", OrderValue.format(items))
        assertEquals(listOf(OrderValue.Item("x", true), OrderValue.Item("y", true)), OrderValue.resolve(listOf("x", "y"), ""))
    }
}
