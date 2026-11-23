package app.vesper.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListTextTest {
    private var n = 0
    private fun add(items: List<ListItem>, vararg t: String) = ListText.add(items, t.toList(), 0L) { "i${n++}" }

    @Test fun deDuplicatesCaseInsensitively() {
        val (a, r1) = add(emptyList(), "Milk", "Eggs")
        assertEquals(listOf("Milk", "Eggs"), a.map { it.text })
        assertEquals(2, r1.added.size)
        val (b, r2) = add(a, "milk", "EGGS", "bread")
        assertEquals(listOf("Milk", "Eggs", "bread"), b.map { it.text })
        assertEquals(listOf("Milk", "Eggs"), r2.duplicates.map { it.text })
        assertEquals(listOf("bread"), r2.added.map { it.text })
    }

    @Test fun pluralsAndQuantitiesAreTheSameItem() {
        val (a, _) = add(emptyList(), "lemons", "tomato", "eggs", "cookies")
        val (b, r) = add(a, "2 lemons", "Tomatoes", "a dozen eggs", "cookie")
        assertEquals(listOf("2 lemons", "tomato", "a dozen eggs", "cookies"), b.map { it.text })
        assertEquals(listOf("2 lemons", "a dozen eggs"), r.updated.map { it.text })
        assertEquals(listOf("tomato", "cookies"), r.duplicates.map { it.text })
        assertTrue(r.added.isEmpty())
        // the plain name doesn't drop the quantity
        val (c, r3) = add(b, "lemons")
        assertEquals("2 lemons", c[0].text)
        assertEquals(1, r3.duplicates.size)
    }

    @Test fun splitsOnCommasAndCleans() {
        assertEquals(listOf("milk", "eggs", "2 lemons"), ListText.split(listOf("milk, eggs", " 2 lemons. ")))
        val (a, _) = add(emptyList(), "milk,milk", "")
        assertEquals(listOf("milk"), a.map { it.text })
    }

    @Test fun reAddingACheckedItemUnchecksIt() {
        val (a, _) = add(emptyList(), "Milk")
        val checked = a.map { it.copy(checked = true) }
        val (b, r) = add(checked, "milk")
        assertFalse(b[0].checked)
        assertEquals(1, r.duplicates.size)
    }

    @Test fun removeMatchesByKey() {
        val (a, _) = add(emptyList(), "2 Eggs", "Milk", "Basil")
        val (b, gone) = ListText.remove(a, listOf("eggs", "basil leaves"))
        assertEquals(listOf("2 Eggs"), gone.map { it.text })
        assertEquals(listOf("Milk", "Basil"), b.map { it.text })
    }

    @Test fun keys() {
        assertEquals("egg", ListText.key("a dozen Eggs"))
        assertEquals("lemon", ListText.key("2 lemons"))
        assertEquals("glass", ListText.key("glasses"))
        assertEquals("box", ListText.key("boxes"))
        assertEquals("one", ListText.key("one"))
        assertEquals("oat milk", ListText.key("Oat milk!"))
    }

    @Test fun listNames() {
        assertEquals(ListNames.SHOPPING, ListNames.normalize(null))
        assertEquals(ListNames.SHOPPING, ListNames.normalize("Groceries"))
        assertEquals(ListNames.SHOPPING, ListNames.normalize("my shopping list"))
        assertEquals(ListNames.TODO, ListNames.normalize("the to-do list"))
        assertEquals("packing", ListNames.normalize("Packing list"))
        assertEquals("To-do", ListNames.title(ListNames.TODO))
    }

    @Test fun inMemoryStoresWork() {
        val lists = InMemoryListStore { 0L }
        lists.add("groceries", listOf("milk", "Milk", "eggs"))
        assertEquals(listOf("milk", "eggs"), lists.items("shopping").map { it.text })
        lists.setChecked("shopping", lists.items("shopping")[0].id, true)
        assertEquals(1, lists.clear("shopping", onlyChecked = true))
        assertEquals(listOf("eggs"), lists.items("shopping").map { it.text })

        var t = 1000L
        val rem = InMemoryReminderStore { t }
        val r = rem.add("Trash", 5000L)
        rem.snooze(r.id, 10)
        assertEquals(1000L + 600_000L, rem.reminders.value[0].dueAtMillis)
        rem.complete(r.id)
        assertFalse(rem.reminders.value[0].open)
    }
}
