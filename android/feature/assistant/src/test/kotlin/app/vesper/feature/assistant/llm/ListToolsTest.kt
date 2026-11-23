package app.vesper.feature.assistant.llm

import app.vesper.core.model.InMemoryListStore
import app.vesper.core.model.InMemoryReminderStore
import app.vesper.core.model.Profile
import app.vesper.core.model.Reminder
import app.vesper.core.model.Secrets
import app.vesper.core.model.SwitchReason
import app.vesper.core.model.VesperConfig
import app.vesper.core.model.VesperServices
import app.vesper.feature.assistant.EngineTest
import app.vesper.feature.assistant.pipeline.ResultCard
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ListToolsTest {
    init { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() = Dispatchers.resetMain()

    private val zone = ZoneId.of("UTC")
    private var now = LocalDateTime.parse("2026-11-23T10:00").atZone(zone).toInstant().toEpochMilli()
    private val services = VesperServices(VesperConfig(debug = true, demoData = false, secrets = Secrets()), EngineTest.FakeSettings()).apply {
        reminders = InMemoryReminderStore { now }
        lists = InMemoryListStore { now }
    }
    private val tools = VesperTools({ services }, main = Dispatchers.Unconfined, zone = { zone }, elapsed = { 0L }, wall = { now })

    private fun run(name: String, input: JSONObject) = runBlocking {
        val spec = tools.specs().first { it.name == name }
        assertNull("schema: $input", spec.validate(input))
        tools.run(name, input)
    }

    @Test fun specsAreThere() {
        val names = tools.specs().map { it.name }
        for (n in listOf("add_reminder", "list_reminders", "update_reminder", "complete_reminder", "cancel_reminder", "list_add", "list_remove", "list_read", "list_clear"))
            assertTrue(n, n in names)
        assertEquals(names.size, names.toSet().size)
    }

    @Test fun addReminderWithEachKindOfTime() {
        val a = run("add_reminder", JSONObject().put("text", "Remind me to take out the trash").put("at", "2026-11-23T19:00"))
        assertFalse(a.content.toString(), a.isError)
        assertEquals("take out the trash", a.content.getJSONObject("reminder").getString("text"))
        assertEquals("2026-11-23T19:00", a.content.getJSONObject("reminder").getString("at"))
        assertEquals(ResultCard.Action("take out the trash", "7:00 PM", ResultCard.Icon.REMINDER), a.card)

        val b = run("add_reminder", JSONObject().put("text", "Check the oven").put("in_minutes", 20))
        assertEquals("2026-11-23T10:20", b.content.getJSONObject("reminder").getString("at"))
        val c = run("add_reminder", JSONObject().put("text", "Call the dentist").put("when", "tomorrow morning"))
        assertEquals("2026-11-24T08:00", c.content.getJSONObject("reminder").getString("at"))
        val d = run("add_reminder", JSONObject().put("text", "Stretch").put("at", "09:00"))
        assertEquals("2026-11-24T09:00", d.content.getJSONObject("reminder").getString("at"))
        assertEquals(4, services.reminders.reminders.value.size)
    }

    @Test fun reminderTimeErrors() {
        assertEquals("NEEDS_TIME", run("add_reminder", JSONObject().put("text", "x")).content.getString("error"))
        assertEquals("PAST_TIME", run("add_reminder", JSONObject().put("text", "x").put("at", "2026-11-23T08:00")).content.getString("error"))
        assertEquals("BAD_TIME", run("add_reminder", JSONObject().put("text", "x").put("at", "whenever")).content.getString("error"))
        assertEquals("BAD_TIME", run("add_reminder", JSONObject().put("text", "x").put("when", "soon")).content.getString("error"))
        assertTrue(services.reminders.reminders.value.isEmpty())
    }

    @Test fun belongsToTheActivePerson() {
        services.profiles.upsert(Profile("maya", "Maya", 0xFFFF375F.toInt()))
        services.profiles.switchTo("maya", SwitchReason.MANUAL)
        run("add_reminder", JSONObject().put("text", "Mine").put("in_minutes", 5))
        run("add_reminder", JSONObject().put("text", "Ours").put("in_minutes", 5).put("for_everyone", true))
        val byText = services.reminders.reminders.value.associateBy { it.text }
        assertEquals("maya", byText["Mine"]!!.profileId)
        assertEquals("Maya", run("list_reminders", JSONObject()).content.getJSONArray("reminders").getJSONObject(0).optString("for"))
        assertNull(byText["Ours"]!!.profileId)
    }

    @Test fun listUpdateCompleteCancel() {
        val id = run("add_reminder", JSONObject().put("text", "Trash").put("in_minutes", 30)).content.getJSONObject("reminder").getString("reminder_id")
        run("add_reminder", JSONObject().put("text", "Dentist").put("in_minutes", 90))
        val l = run("list_reminders", JSONObject())
        assertEquals(2, l.content.getJSONArray("reminders").length())
        assertEquals("2 open · next 10:30 AM", (l.card as ResultCard.Action).state)

        val u = run("update_reminder", JSONObject().put("reminder_id", id).put("in_minutes", 60).put("new_text", "Take out the trash"))
        assertEquals("2026-11-23T11:00", u.content.getJSONObject("reminder").getString("at"))
        assertEquals("NOTHING_TO_CHANGE", run("update_reminder", JSONObject().put("reminder_id", id)).content.getString("error"))
        assertEquals("NOT_FOUND", run("update_reminder", JSONObject().put("reminder_id", "nope").put("in_minutes", 5)).content.getString("error"))

        // by text, and ambiguity needs a choice
        assertEquals("NOT_FOUND", run("complete_reminder", JSONObject()).content.getString("error"))
        assertFalse(run("complete_reminder", JSONObject().put("text", "trash")).isError)
        assertEquals(Reminder.State.DONE, services.reminders.reminders.value.first { it.id == id }.state)
        val c = run("cancel_reminder", JSONObject().put("text", "Dentist"))
        assertEquals("Cancelled", (c.card as ResultCard.Action).state)
        assertEquals(listOf(id), services.reminders.reminders.value.map { it.id })
    }

    @Test fun shoppingList() {
        val a = run("list_add", JSONObject().put("items", JSONArray(listOf("milk", "eggs", "2 lemons"))))
        assertEquals(3, a.content.getJSONArray("added").length())
        assertEquals(ResultCard.Action("Shopping list", "Added 3 items", ResultCard.Icon.LIST), a.card)
        val b = run("list_add", JSONObject().put("items", JSONArray(listOf("Milk"))).put("list", "groceries"))
        assertEquals("Already on it", (b.card as ResultCard.Action).state)
        assertEquals("Milk", b.content.getJSONArray("already_on_list").let { it.getString(0) }.replaceFirstChar(Char::uppercase))

        val r = run("list_read", JSONObject())
        assertEquals(listOf("milk", "eggs", "2 lemons"), r.content.getJSONArray("items").let { arr -> (0 until arr.length()).map(arr::getString) })

        val rm = run("list_remove", JSONObject().put("items", JSONArray(listOf("egg"))))
        assertEquals("eggs", rm.content.getJSONArray("removed").getString(0))
        assertEquals("NOT_FOUND", run("list_remove", JSONObject().put("items", JSONArray(listOf("caviar")))).content.getString("error"))

        // clearing needs confirmation
        assertEquals("NEEDS_CONFIRMATION", run("list_clear", JSONObject().put("confirmed", false)).content.getString("error"))
        assertEquals(2, services.lists.items("shopping").size)
        assertEquals(2, run("list_clear", JSONObject().put("confirmed", true)).content.getInt("cleared"))
        assertTrue(services.lists.items("shopping").isEmpty())
    }

    @Test fun otherLists() {
        run("list_add", JSONObject().put("items", JSONArray(listOf("Pay taxes"))).put("list", "to-do list"))
        assertEquals(listOf("Pay taxes"), services.lists.items("todo").map { it.text })
        val r = run("list_read", JSONObject().put("list", "shopping"))
        assertEquals(0, r.content.getJSONArray("items").length())
        assertEquals("todo", r.content.getJSONArray("other_lists").getString(0))
    }
}
