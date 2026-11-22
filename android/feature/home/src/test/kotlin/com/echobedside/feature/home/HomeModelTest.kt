package com.echobedside.feature.home

import com.echobedside.core.model.HaEntity
import com.echobedside.feature.home.ha.DemoHomeAssistant
import com.echobedside.feature.home.ha.Registry
import com.echobedside.feature.home.home.HomePresenter
import com.echobedside.feature.home.home.LampTile
import com.echobedside.feature.home.home.RoomMapper
import com.echobedside.feature.home.home.SecureTile
import com.echobedside.feature.home.home.Slot
import com.echobedside.feature.home.home.ThermostatTile
import com.echobedside.feature.home.home.TileIcon
import com.echobedside.feature.home.home.ToggleTile
import com.echobedside.feature.home.ui.SvgPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeModelTest {
    private val demo = DemoHomeAssistant(CoroutineScope(Dispatchers.Unconfined))
    private fun registry() = runBlocking { Registry.load(demo) }
    private fun e(id: String, state: String, vararg a: Pair<String, Any>) = HaEntity(id, state, JSONObject().apply { a.forEach { (k, v) -> put(k, v) } })

    @Test fun registryUsesEntityThenDeviceAreaAndExcludesConfigEntities() {
        val r = Registry.parse(
            JSONArray("""[{"area_id":"bedroom","name":"Bedroom"}]"""),
            JSONArray("""[{"id":"dev1","area_id":"bedroom"},{"id":"dev2","area_id":null}]"""),
            JSONArray("""[{"entity_id":"light.a","device_id":"dev1","area_id":null},
                {"entity_id":"light.b","device_id":"dev1","area_id":"kitchen"},
                {"entity_id":"button.restart","device_id":"dev1","entity_category":"config"},
                {"entity_id":"light.hidden","area_id":"bedroom","hidden_by":"user"}]"""))
        assertEquals("bedroom", r.areaOf("light.a"))
        assertEquals("kitchen", r.areaOf("light.b"))
        assertTrue("button.restart" in r.excluded && "light.hidden" in r.excluded)
        assertFalse("light.a" in r.excluded)
    }

    @Test fun groupsAreasIntoRoomsWithHouseWideFallbacks() {
        val rooms = RoomMapper.rooms(demo.entities.value, registry())
        assertEquals(listOf("bedroom", "living_room", "kitchen"), rooms.map { it.id }) // hallway has only the thermostat
        val bed = rooms[0]
        assertEquals("light.bedside_lamp", bed.lamp) // the dimmable one; the ceiling light is on/off only
        assertEquals(setOf("light.ceiling", "fan.air_purifier"), bed.toggles.toSet())
        assertEquals("climate.thermostat", rooms[1].climate) // living room borrows the house thermostat
        assertEquals(setOf("lock.front_door", "cover.garage_door"), rooms[2].secure.toSet())
        assertEquals(4, bed.scenes.size)
        assertEquals("Living", rooms[1].label)
    }

    @Test fun settingsOverridesAndOrder() {
        val rooms = RoomMapper.rooms(demo.entities.value, registry(),
            override = { area, slot -> if (area == "kitchen" && slot == Slot.TOGGLES) listOf("switch.coffee_maker") else null },
            order = listOf("kitchen"))
        assertEquals("kitchen", rooms[0].id)
        assertEquals(listOf("switch.coffee_maker"), rooms[0].toggles)
    }

    @Test fun entityToTileMapping() {
        val t = ThermostatTile.from(demo.entities.value.getValue("climate.thermostat"))
        assertEquals("Heating to 68°", t.line)
        assertEquals("Heat", t.modeLabel)
        assertEquals(18f / 40f, t.fraction, 0.001f) // design: 415 * (68 − 50) / 40 of 415
        assertEquals("Holding 68°", ThermostatTile.from(e("climate.x", "heat", "temperature" to 68, "current_temperature" to 70, "hvac_action" to "idle")).line)
        assertEquals("Off", ThermostatTile.from(e("climate.x", "off", "current_temperature" to 70)).line)
        // heat_cool uses the low end of the range while heating
        val range = ThermostatTile.from(e("climate.x", "heat_cool", "target_temp_low" to 66, "target_temp_high" to 74, "hvac_action" to "heating"))
        assertEquals("target_temp_low", range.targetKey); assertEquals(66.0, range.target!!, 0.0)

        assertEquals(60, LampTile.from(demo.entities.value.getValue("light.bedside_lamp")).pct)
        assertEquals(0, LampTile.from(e("light.x", "off")).pct)

        val fan = ToggleTile.from(demo.entities.value.getValue("fan.air_purifier"))
        assertEquals("Auto · quiet", fan.stateText); assertEquals(TileIcon.FAN, fan.icon); assertTrue(fan.on)
        assertEquals("Off", ToggleTile.from(demo.entities.value.getValue("light.ceiling")).stateText)

        val lock = SecureTile.from(demo.entities.value.getValue("lock.front_door"))
        assertFalse(lock.on); assertEquals("Locked", lock.stateText); assertEquals(TileIcon.LOCK, lock.icon)
        val open = SecureTile.from(e("lock.front_door", "unlocked", "friendly_name" to "Front door"))
        assertTrue(open.on); assertEquals(TileIcon.UNLOCK, open.icon)
        assertEquals("Open", SecureTile.from(e("cover.g", "open", "device_class" to "garage")).stateText)
    }

    @Test fun presenterPicksRoomActiveSceneAndLine() {
        val ents = demo.entities.value
        val rooms = RoomMapper.rooms(ents, registry(), override = DemoHomeAssistant::demoDefaults)
        val ui = HomePresenter.present(rooms, "bedroom", ents)
        assertEquals("Bedroom", ui.title)
        assertEquals("66° inside · Doors locked", ui.line)
        assertEquals(listOf("Good Morning", "Movie", "Sleep", "Away"), ui.scenes.map { it.name })
        assertEquals("Sleep", ui.scenes.single { it.active }.name) // most recently activated
        val unlocked = ents + ("lock.front_door" to e("lock.front_door", "unlocked", "friendly_name" to "Front door"))
        assertEquals("66° inside · Front door unlocked", HomePresenter.present(rooms, "bedroom", unlocked).line)
        assertEquals("Living Room", HomePresenter.present(rooms, "living_room", ents).title)
        assertEquals("Bedroom", HomePresenter.present(rooms, "gone", ents).title) // unknown room → first
        assertNull(HomePresenter.present(emptyList(), null, ents).selected)
    }

    @Test fun svgPathParsesDesignIcons() {
        val segs = SvgPath.parse("M5 12h14M12 5v14")
        assertEquals(listOf(SvgPath.Seg.Move(5f, 12f), SvgPath.Seg.Line(19f, 12f), SvgPath.Seg.Move(12f, 5f), SvgPath.Seg.Line(12f, 19f)), segs)
        // Arcs end exactly at their endpoint; relative + implicit repeats.
        val lock = SvgPath.parse("M7 10V7a5 5 0 0 1 10 0v3")
        val arcEnd = lock.filterIsInstance<SvgPath.Seg.Cubic>().last()
        assertEquals(17f, arcEnd.x, 0.001f); assertEquals(7f, arcEnd.y, 0.001f)
        assertEquals(SvgPath.Seg.Line(17f, 10f), lock.last())
        assertEquals(3, SvgPath.parse("M0 0 L1 1 2 2").size)
        assertTrue(SvgPath.parse("M196 262 C 215 240, 228 228, 250 210 S 400 120, 470 70").size == 3)
    }
}
