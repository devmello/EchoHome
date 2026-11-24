package app.vesper.feature.clock

import app.vesper.core.design.VesperColors
import app.vesper.core.model.VesperFeature
import app.vesper.core.model.FaceSpec
import app.vesper.core.model.FeatureHost
import app.vesper.core.model.ScreenSpec

/**
 * `:feature:clock` (agent A): StandBy faces, face switching + edit mode, Night mode.
 *
 * Faces (one design file each, design/NAME.dc.html): Digital, Analog, World, Solar, Float, Liquid Glass, Weather, Calendar.
 * StandBy: vertical swipe switches faces, long-press opens edit mode (Customize.dc.html).
 * Widgets (pager order 5, right after StandBy): two widget stacks side by side ([WidgetsScreen]).
 * Night: [NightController] writes `SystemState.nightActive` / `ambientLux` from the light sensor
 * and the Night mode setting (`clock.night.*`); every face draws in #BE180E, StandBy dims it to 0.42.
 */
object ClockFeature : VesperFeature {
    override val id = "clock"

    const val SCREEN_STANDBY = "standby"
    const val SCREEN_WIDGETS = WidgetsScreen.ID
    const val KEY_FACE = ClockSettings.KEY_FACE

    /** Created in [install], one per process. */
    lateinit var night: NightController
        private set

    private val CLOCK_FACES = setOf("digital", "analog", "world", "solar", "float", "glass", "weather", "calendar")

    override fun install(host: FeatureHost) {
        val s = host.services
        night = NightController(host.context, s)
        host.registerFace(FaceSpec("digital", "Digital", VesperColors.SWATCHES.take(8), supportsComplications = true, order = 0) { DigitalFace(it, s) })
        host.registerFace(FaceSpec("analog", "Analog", AnalogFace.COLORS, supportsComplications = true, order = 10) { AnalogFace(it, s) })
        host.registerFace(FaceSpec("world", "World", emptyList(), order = 20) { WorldFace(it, s) })
        host.registerFace(FaceSpec("solar", "Solar", SolarFace.COLORS, order = 30) { SolarFace(it, s) })
        host.registerFace(FaceSpec("float", "Float", FloatFace.COLORS, order = 40) { FloatFace(it, s) })
        host.registerFace(FaceSpec("glass", "Liquid Glass", GlassFace.COLORS, order = 50) { GlassFace(it, s) })
        host.registerFace(FaceSpec("weather", "Weather", emptyList(), order = 60) { WeatherFace(it, s) })
        host.registerFace(FaceSpec("calendar", "Calendar", CalendarFace.COLORS, order = 70) { CalendarFace(it, s) })
        host.registerScreen(ScreenSpec(SCREEN_STANDBY, order = 0, title = "StandBy") { StandbyScreen(it, s, night) })
        // two Smart Stacks side by side, right after StandBy (WidgetsScreen.kt)
        host.registerScreen(ScreenSpec(SCREEN_WIDGETS, order = 5, title = "Widgets") { WidgetsScreen(it, s, night) })
        host.registerSettings(ClockSettings.section(s.faces.faces.value.filter { it.id in CLOCK_FACES }))
    }
}
