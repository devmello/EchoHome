package app.vesper.feature.home

import app.vesper.core.model.HaEntity
import app.vesper.feature.home.ha.DemoHomeAssistant
import app.vesper.feature.home.ha.Registry
import app.vesper.feature.home.lamp.LampController
import app.vesper.feature.home.lamp.LampPresenter
import app.vesper.feature.home.lamp.LampPreset
import app.vesper.feature.home.lamp.LightCalls
import app.vesper.feature.home.lamp.LightColors
import app.vesper.feature.home.lamp.LightDetail
import app.vesper.feature.home.lamp.LightKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LampModelTest {
    private fun light(state: String, vararg attrs: Pair<String, Any>): HaEntity {
        val a = JSONObject().put("friendly_name", "Lamp")
        attrs.forEach { (k, v) -> a.put(k, v) }
        return HaEntity("light.lamp", state, a)
    }

    private fun modes(vararg m: String) = JSONArray().apply { m.forEach { put(it) } }

    @Test fun kindFromSupportedColorModes() {
        assertEquals(LightKind.ONOFF, LightDetail.from(light("on", "supported_color_modes" to modes("onoff"))).kind)
        assertEquals(LightKind.DIM, LightDetail.from(light("on", "supported_color_modes" to modes("brightness"))).kind)
        assertEquals(LightKind.DIM, LightDetail.from(light("on", "supported_color_modes" to modes("white"))).kind)
        assertEquals(LightKind.TEMP, LightDetail.from(light("on", "supported_color_modes" to modes("color_temp"))).kind)
        assertEquals(LightKind.COLOR, LightDetail.from(light("on", "supported_color_modes" to modes("color_temp", "xy"))).kind)
        assertEquals(LightKind.COLOR, LightDetail.from(light("on", "supported_color_modes" to modes("rgbww"))).kind)
        // legacy supported_features: 1 brightness, 2 colour temperature, 16 colour
        assertEquals(LightKind.DIM, LightDetail.from(light("on", "supported_features" to 1)).kind)
        assertEquals(LightKind.TEMP, LightDetail.from(light("on", "supported_features" to 3)).kind)
        assertEquals(LightKind.COLOR, LightDetail.from(light("on", "supported_features" to 19)).kind)
        assertEquals(LightKind.ONOFF, LightDetail.from(light("on")).kind)
    }

    @Test fun readsBrightnessKelvinAndColour() {
        val white = LightDetail.from(light("on", "supported_color_modes" to modes("color_temp", "hs"), "brightness" to 153,
            "color_mode" to "color_temp", "color_temp_kelvin" to 2700, "min_color_temp_kelvin" to 2000, "max_color_temp_kelvin" to 6500,
            "rgb_color" to JSONArray().put(255).put(167).put(87)))
        assertEquals(60, white.pct)
        assertEquals(2700, white.kelvin)
        assertNull(white.rgb) // HA fills rgb_color in every mode; color_mode says which one is live
        assertEquals(0.1556f, white.kelvinFraction!!, 0.001f)

        val colour = LightDetail.from(light("on", "supported_color_modes" to modes("color_temp", "hs"), "brightness" to 255,
            "color_mode" to "hs", "hs_color" to JSONArray().put(280.0).put(63.0), "color_temp_kelvin" to JSONObject.NULL))
        assertNull(colour.kelvin)
        assertEquals("Purple", LightColors.name(colour))

        // Older HA: mireds only.
        val mireds = LightDetail.from(light("on", "supported_color_modes" to modes("color_temp"), "brightness" to 3,
            "color_mode" to "color_temp", "color_temp" to 370, "min_mireds" to 153, "max_mireds" to 500))
        assertEquals(2703, mireds.kelvin)
        assertEquals(2000, mireds.minKelvin)
        assertEquals(6536, mireds.maxKelvin)
        assertEquals(1, mireds.pct) // never 0 % while on

        val off = LightDetail.from(light("off", "supported_color_modes" to modes("color_temp"), "brightness" to JSONObject.NULL, "color_mode" to JSONObject.NULL))
        assertEquals(0, off.pct)
        assertNull(off.kelvin)
        assertEquals("Off", LightColors.name(off))
    }

    @Test fun brightnessCalls() {
        val dim = LightDetail.from(light("on", "supported_color_modes" to modes("brightness"), "brightness" to 128))
        assertEquals("light.turn_off light.lamp ", LightCalls.brightness(dim, 0).toString())
        val on = LightCalls.brightness(dim, 35)
        assertEquals("turn_on", on.service)
        assertEquals(35, on.data!!.getInt("brightness_pct"))
        assertEquals(100, LightCalls.brightness(dim, 140).data!!.getInt("brightness_pct"))
        // an on/off light gets no brightness_pct (HA rejects it)
        val plain = LightDetail.from(light("off", "supported_color_modes" to modes("onoff")))
        assertNull(LightCalls.brightness(plain, 50).data)
    }

    @Test fun kelvinCallsAreClampedAndRounded() {
        val temp = LightDetail.from(light("on", "supported_color_modes" to modes("color_temp"), "brightness" to 128,
            "min_color_temp_kelvin" to 2200, "max_color_temp_kelvin" to 6500))
        assertEquals(2200, LightCalls.kelvin(temp, 1800)!!.data!!.getInt("color_temp_kelvin"))
        assertEquals(6500, LightCalls.kelvin(temp, 9000)!!.data!!.getInt("color_temp_kelvin"))
        assertEquals(3150, LightCalls.kelvin(temp, 3137)!!.data!!.getInt("color_temp_kelvin"))
        // colour lights take kelvin too (HA converts it); dim-only lights don't
        val colour = LightDetail.from(light("on", "supported_color_modes" to modes("hs")))
        assertEquals(4000, LightCalls.kelvin(colour, 4000)!!.data!!.getInt("color_temp_kelvin"))
        assertNull(LightCalls.kelvin(LightDetail.from(light("on", "supported_color_modes" to modes("brightness"))), 4000))
    }

    @Test fun colourCallsSendRgb() {
        val colour = LightDetail.from(light("on", "supported_color_modes" to modes("xy")))
        val c = LightCalls.color(colour, 0xBF5AF2)!!
        assertEquals("light", c.domain); assertEquals("turn_on", c.service)
        assertEquals("[191,90,242]", c.data!!.getJSONArray("rgb_color").toString())
        assertNull(LightCalls.color(LightDetail.from(light("on", "supported_color_modes" to modes("color_temp"))), 0xBF5AF2))
    }

    @Test fun presetsSetBrightnessAndAWhite() {
        val temp = LightDetail.from(light("on", "supported_color_modes" to modes("color_temp"), "min_color_temp_kelvin" to 2500, "max_color_temp_kelvin" to 6500))
        val night = LampPreset.ALL.first { it.id == "night" }
        val d = LightCalls.preset(temp, night).data!!
        assertEquals(5, d.getInt("brightness_pct"))
        assertEquals(2500, d.getInt("color_temp_kelvin")) // 2200 clamped to the light's warmest
        assertEquals("5% · 2500 K", night.detail(temp))
        val dim = LightDetail.from(light("on", "supported_color_modes" to modes("brightness")))
        assertFalse(LightCalls.preset(dim, night).data!!.has("color_temp_kelvin"))
        assertEquals("5%", night.detail(dim))
    }

    @Test fun expectedStateFollowsTheCall() {
        val e = light("on", "supported_color_modes" to modes("color_temp", "hs"), "brightness" to 153,
            "color_mode" to "color_temp", "color_temp_kelvin" to 2700)
        val l = LightDetail.from(e)
        val purple = LightDetail.from(LightCalls.expected(e, LightCalls.color(l, 0xBF5AF2)!!))
        assertEquals(0xBF5AF2, purple.rgb)
        assertNull(purple.kelvin)
        assertEquals(60, purple.pct)
        val cool = LightDetail.from(LightCalls.expected(e, LightCalls.kelvin(l, 6000)!!))
        assertEquals(6000, cool.kelvin)
        val off = LightCalls.expected(e, LightCalls.brightness(l, 0))
        assertEquals("off", off.state)
        assertEquals(0, LightDetail.from(off).pct)
        val back = LightDetail.from(LightCalls.expected(off, LightCalls.brightness(LightDetail.from(off), 40)))
        assertTrue(back.on); assertEquals(40, back.pct)
        // other domains just flip
        assertEquals("on", LightCalls.expected(HaEntity("switch.x", "off", JSONObject()), LightCalls.toggle(HaEntity("switch.x", "off", JSONObject()))).state)
    }

    @Test fun swatchesMatchByHue() {
        // HA reports colours back renormalised from hs: (191,90,242) → hs (280, 63) → (196, 94, 255)
        assertTrue(LightColors.sameColor(0xC45EFF, 0xBF5AF2))
        assertFalse(LightColors.sameColor(0x0A84FF, 0xBF5AF2))
        val (h, s) = LightColors.rgbToHs(LightColors.hsToRgb(210f, 0.96f))
        assertEquals(210f, h, 1f); assertEquals(0.96f, s, 0.01f)
        assertEquals("Warm white", LightColors.whiteName(2700))
        assertEquals(0xFFAA50, LightColors.kelvinDisplay(2700))
    }

    @Test fun presenterListsTheRoomsOtherLights() = runBlocking {
        val demo = DemoHomeAssistant(CoroutineScope(Dispatchers.Unconfined))
        val reg = Registry.load(demo)
        val ui = LampPresenter.present("light.bedside_lamp", demo.entities.value, reg)
        assertEquals("Bedside lamp", ui.light!!.name)
        assertEquals(LightKind.COLOR, ui.light!!.kind)
        assertEquals("Bedroom · On · 60%", ui.line)
        assertEquals(listOf("Ceiling", "Reading light", "Strip light"), ui.others.map { it.name })
        assertEquals(listOf("Off", "40%", "Purple · 80%"), ui.others.map { it.stateText })
        assertEquals("2 of 3 on", ui.othersLine)
        assertEquals(-1, ui.activePreset)
        assertEquals("light.bedside_lamp", LampPresenter.defaultLight(demo.entities.value, reg, "bedroom"))
        assertEquals("light.pendants", LampPresenter.defaultLight(demo.entities.value, reg, "kitchen"))
        assertEquals("Light not found", LampPresenter.present("light.nope", demo.entities.value, reg).line)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LampControllerTest {
    private fun TestScope.setup(settings: MemSettings = MemSettings()): Pair<DemoHomeAssistant, LampController> {
        val ha = DemoHomeAssistant(backgroundScope, latencyMs = 150)
        val c = LampController(ha, settings, backgroundScope)
        c.start()
        runCurrent()
        return ha to c
    }

    private fun LampController.light() = ui.value!!.light!!

    @Test fun picksTheSelectedRoomsLampWithoutAnArgument() = runTest {
        val (_, c) = setup(MemSettings(HomeSettings.SELECTED_ROOM to "living_room"))
        assertEquals("light.floor_lamp", c.light().entityId)
        assertEquals("Living Room · On · 35%", c.ui.value!!.line)
        c.show("light.bedside_lamp"); runCurrent()
        assertEquals("Bedside lamp", c.light().name)
    }

    @Test fun kelvinIsOptimisticThenConfirmed() = runTest {
        val (ha, c) = setup()
        c.show("light.bedside_lamp"); runCurrent()
        c.setKelvin(5000); runCurrent()
        assertEquals(5000, c.light().kelvin) // at once
        assertEquals(2700, ha.entities.value.getValue("light.bedside_lamp").attributes.getInt("color_temp_kelvin"))
        advanceTimeBy(200); runCurrent()
        assertEquals(5000, ha.entities.value.getValue("light.bedside_lamp").attributes.getInt("color_temp_kelvin"))
        assertEquals(5000, c.light().kelvin)
    }

    @Test fun colourAndPresetReachTheDemoHome() = runTest {
        val (ha, c) = setup()
        c.show("light.bedside_lamp"); runCurrent()
        c.setColor(0x0A84FF); advanceTimeBy(200); runCurrent()
        val e = ha.entities.value.getValue("light.bedside_lamp")
        assertEquals("hs", e.attributes.getString("color_mode"))
        assertEquals("Blue", LightColors.name(c.light()))
        c.applyPreset(0); advanceTimeBy(200); runCurrent() // Reading: 100 %, 4000 K
        assertEquals(100, c.light().pct)
        assertEquals(4000, c.light().kelvin)
        assertEquals(0, c.ui.value!!.activePreset)
        c.setBrightness(0); advanceTimeBy(200); runCurrent()
        assertEquals("off", ha.entities.value.getValue("light.bedside_lamp").state)
        assertEquals(-1, c.ui.value!!.activePreset)
    }

    @Test fun rollsBackWhenHaRefuses() = runTest {
        val (ha, c) = setup()
        c.show("light.bedside_lamp"); runCurrent()
        ha.failCalls = true
        c.setBrightness(20); runCurrent()
        assertEquals(20, c.light().pct)
        advanceTimeBy(200); runCurrent()
        assertEquals(60, c.light().pct)
        assertEquals("Couldn't change Bedside lamp", c.ui.value!!.error)
        advanceTimeBy(3600); runCurrent()
        assertNull(c.ui.value!!.error)
    }

    @Test fun roomListSwitchesToggleOtherLights() = runTest {
        val (ha, c) = setup()
        c.show("light.bedside_lamp"); runCurrent()
        c.toggleOther("light.ceiling"); runCurrent()
        assertTrue(c.ui.value!!.others.first { it.entityId == "light.ceiling" }.on)
        assertEquals("3 of 3 on", c.ui.value!!.othersLine)
        advanceTimeBy(200); runCurrent()
        assertEquals("on", ha.entities.value.getValue("light.ceiling").state)
    }
}
