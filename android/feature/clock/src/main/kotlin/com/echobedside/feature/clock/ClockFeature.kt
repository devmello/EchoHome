package com.echobedside.feature.clock

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.widget.FrameLayout
import com.echobedside.core.design.EchoColors
import com.echobedside.core.model.EchoFeature
import com.echobedside.core.model.EchoScreen
import com.echobedside.core.model.EchoServices
import com.echobedside.core.model.FaceConfig
import com.echobedside.core.model.FaceSpec
import com.echobedside.core.model.FaceView
import com.echobedside.core.model.FeatureHost
import com.echobedside.core.model.ScreenSpec
import com.echobedside.core.model.observeWhileAttached

/**
 * `:feature:clock` (agent A): StandBy faces, edit mode, Night mode.
 * So far: the Digital face and a StandBy screen that shows the selected face.
 */
object ClockFeature : EchoFeature {
    override val id = "clock"

    const val SCREEN_STANDBY = "standby"
    const val KEY_FACE = "clock.face"

    override fun install(host: FeatureHost) {
        val s = host.services
        host.registerFace(FaceSpec("digital", "Digital", EchoColors.SWATCHES.take(8), supportsComplications = true, order = 0) {
            DigitalFace(it, s)
        })
        host.registerScreen(ScreenSpec(SCREEN_STANDBY, order = 0) { StandbyScreen(it, s) })
    }
}

/**
 * Shows the selected face full screen and forwards minute ticks to it. Long-press is reserved
 * for edit mode (Customize.dc.html); until that exists, `:app` uses it to open Android Settings.
 *
 * Debug: `--es screen standby --es face <id>` picks a face.
 */
@SuppressLint("ViewConstructor")
class StandbyScreen(context: Context, private val services: EchoServices) : FrameLayout(context), EchoScreen {
    private var face: FaceView? = null
    private var faceId: String? = null

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { face?.onTimeChanged() }
    }

    init {
        setBackgroundColor(Color.BLACK)
        observeWhileAttached(services.faces.faces) { showFace(selectedFaceId()) }
        observeWhileAttached(services.system.state) { st -> face?.setConfig(configFor(faceId, st.nightActive)) }
    }

    private fun selectedFaceId(): String = services.settings.getString(ClockFeature.KEY_FACE, "digital")

    private fun configFor(id: String?, night: Boolean): FaceConfig {
        val color = services.settings.getInt("clock.color.$id", Color.WHITE)
        val complications = services.settings.getBoolean("clock.complications.$id", true)
        return FaceConfig(color, complications, night)
    }

    fun showFace(id: String) {
        val spec = services.faces.byId(id) ?: services.faces.faces.value.firstOrNull() ?: return
        if (spec.id == faceId && face != null) return
        face?.onScreenHidden()
        removeAllViews()
        val f = spec.factory(context)
        f.setConfig(configFor(spec.id, services.system.state.value.nightActive))
        addView(f.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        face = f
        faceId = spec.id
        if (isAttachedToWindow) f.onScreenShown(null)
    }

    override fun onScreenShown(args: Bundle?) {
        args?.getString("face")?.let { services.settings.putString(ClockFeature.KEY_FACE, it); showFace(it) }
        face?.onTimeChanged()
        face?.onScreenShown(args)
    }

    override fun onScreenHidden() { face?.onScreenHidden() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        })
        face?.onTimeChanged()
    }

    override fun onDetachedFromWindow() {
        context.unregisterReceiver(timeReceiver)
        super.onDetachedFromWindow()
    }
}
