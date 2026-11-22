package com.echobedside.feature.clock

import com.echobedside.core.design.EchoColors
import com.echobedside.core.model.EchoFeature
import com.echobedside.core.model.FaceSpec
import com.echobedside.core.model.FeatureHost
import com.echobedside.core.model.ScreenSpec

/**
 * `:feature:clock` (agent A): StandBy faces, face switching + edit mode, Night mode.
 *
 * Faces (one design file each, design/NAME.dc.html): Digital, Analog, World, Solar, Float, Liquid Glass, Weather, Calendar.
 * StandBy: vertical swipe switches faces, long-press opens edit mode (Customize.dc.html).
 * Night: [NightController] writes `SystemState.nightActive` / `ambientLux` from the light sensor
 * and the Night mode setting; every face draws in #BE180E, StandBy dims it to 0.42.
 */
object ClockFeature : EchoFeature {
    override val id = "clock"

    const val SCREEN_STANDBY = "standby"
    const val KEY_FACE = "clock.face"

    /** Created in [install], one per process. */
    lateinit var night: NightController
        private set

    override fun install(host: FeatureHost) {
        val s = host.services
        night = NightController(host.context, s.system, s.scope)
        host.registerFace(FaceSpec("digital", "Digital", EchoColors.SWATCHES.take(8), supportsComplications = true, order = 0) { DigitalFace(it, s) })
        host.registerFace(FaceSpec("analog", "Analog", AnalogFace.COLORS, supportsComplications = true, order = 10) { AnalogFace(it, s) })
        host.registerFace(FaceSpec("world", "World", emptyList(), order = 20) { WorldFace(it, s) })
        host.registerFace(FaceSpec("solar", "Solar", SolarFace.COLORS, order = 30) { SolarFace(it, s) })
        host.registerFace(FaceSpec("float", "Float", FloatFace.COLORS, order = 40) { FloatFace(it, s) })
        host.registerFace(FaceSpec("glass", "Liquid Glass", GlassFace.COLORS, order = 50) { GlassFace(it, s) })
        host.registerFace(FaceSpec("weather", "Weather", emptyList(), order = 60) { WeatherFace(it, s) })
        host.registerFace(FaceSpec("calendar", "Calendar", CalendarFace.COLORS, order = 70) { CalendarFace(it, s) })
        host.registerScreen(ScreenSpec(SCREEN_STANDBY, order = 0) { StandbyScreen(it, s, night) })
    }
}
