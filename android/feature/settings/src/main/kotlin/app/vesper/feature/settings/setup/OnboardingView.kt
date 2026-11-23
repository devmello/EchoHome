package app.vesper.feature.settings.setup

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.text.format.DateFormat
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import app.vesper.core.design.GlassPainter
import app.vesper.core.design.Motion
import app.vesper.core.design.Night
import app.vesper.core.design.SpecText
import app.vesper.core.design.VesperColors
import app.vesper.core.design.withAlpha
import app.vesper.core.model.DeviceName
import app.vesper.core.model.FaceConfig
import app.vesper.core.model.FaceSpec
import app.vesper.core.model.FaceView
import app.vesper.core.model.HomeLocation
import app.vesper.core.model.Profile
import app.vesper.core.model.VesperScreen
import app.vesper.core.model.VesperServices
import app.vesper.core.model.observeWhileAttached
import app.vesper.feature.settings.WebAddress
import app.vesper.feature.settings.WebSettings
import app.vesper.feature.settings.setup.Glyph.Kind
import app.vesper.feature.settings.ui.Draw
import app.vesper.feature.settings.ui.Qr
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Screen "onboarding": the paged first-run setup (see [SetupFeature], [SetupFlow]).
 *
 * A glass sheet over a pre-blurred wallpaper, one step per page, page dots and Back / Next in a
 * bar under it. Everything is drawn on this view's canvas, except the clock step's live face
 * preview (a real [FaceView], scaled down, created only while that step is on screen).
 * Every choice is written to the setting the owning feature already reads, the moment it's
 * tapped, so the rest of the app follows live. Nothing animates at rest: a step change runs one
 * 0.45 s spring, the first Welcome a 0.9 s rise.
 *
 * Layout (device px, 960×480): sheet 48,40 → 912,400 (radius 40); left column x 88, 320 wide;
 * right panel x 448 → 872; bar y 412 → 456.
 */
@SuppressLint("ViewConstructor")
internal class OnboardingView(
    context: Context,
    private val services: VesperServices,
    private val web: WebSettings?,
) : FrameLayout(context), VesperScreen {

    private val flow = SetupFlow()
    private val settings get() = services.settings

    // --- paint ---
    private val sheetGlass = GlassPainter()
    private val barGlass = GlassPainter()
    private val tintGlass = GlassPainter().apply { tint = VesperColors.PRIMARY }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private val heroPre = SpecText(26f, 600, -0.01f)
    private val hero = SpecText(92f, 800, -0.035f)
    private val heroBody = SpecText(20f, 500, -0.005f)
    private val title = SpecText(36f, 800, -0.02f)
    private val doneTitle = SpecText(40f, 800, -0.02f)
    private val body = SpecText(17f, 500)
    private val big = SpecText(26f, 700, -0.015f)
    private val nameText = SpecText(30f, 800, -0.02f)
    private val label = SpecText(15f, 700)
    private val small = SpecText(14f, 600)
    private val chip = SpecText(16f, 700)
    private val button = SpecText(17f, 700)
    private val cardTitle = SpecText(19f, 700, -0.01f)
    private val cardBody = SpecText(15f, 500)
    private val initial = SpecText(38f, 800)

    // --- state ---
    private var shown = false
    private var night = false
    private var art: SetupWallpaper.Art? = null
    private var artLoading = false
    private var wifiOn = false
    private var wifiConnected = false
    private var wifiName: String? = null
    private var address: WebAddress? = null
    private var qr: Qr? = null
    private var paired = 0
    private var location: HomeLocation = services.location.value
    private var profiles: List<Profile> = emptyList()
    private var faces: List<FaceSpec> = emptyList()
    private var faceIndex = 0
    /** [faceIndex] starts at the stored face, once. */
    private var faceChosen = false
    private var timeNote: String? = null

    // people: the person being added in this run
    private var draftName = ""
    private var draftColor = PALETTE[0]
    private var draftId: String? = null
    private val created = linkedSetOf<String>()
    private var pendingEnrol = false

    // --- motion ---
    private var p = 1f
    private var dir = 1
    private var intro = false
    private var wallFrom = 0f
    private var wallX = 0f
    private var welcomeInvolved = false
    private var anim: ValueAnimator? = null

    // --- hits (recorded while drawing, untranslated) ---
    private class Hit(val r: RectF = RectF(), var action: (() -> Unit)? = null)
    private val hits = ArrayList<Hit>()
    private var hitCount = 0

    private val main = Handler(Looper.getMainLooper())

    private val previewBox = PreviewBox(context)
    private var preview: FaceView? = null
    private var previewId: String? = null

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.BLACK)
        addView(previewBox, LayoutParams(PREVIEW_W.toInt(), PREVIEW_H.toInt(), Gravity.TOP or Gravity.START).apply {
            leftMargin = RIGHT_L.toInt(); topMargin = CONTENT_T.toInt()
        })
        previewBox.visibility = GONE
        previewBox.onTap = { pickFace(faceIndex + 1) }

        observeWhileAttached(services.system.state) { s ->
            if (s.nightActive != night) { night = s.nightActive; syncPreview(); invalidate() }
        }
        observeWhileAttached(services.location) { location = it; if (flow.step == SetupStep.LOCATION) invalidate() }
        observeWhileAttached(services.profiles.profiles) { profiles = it; if (flow.step == SetupStep.PEOPLE) invalidate() }
        observeWhileAttached(services.faces.faces) { l ->
            faces = l
            if (flow.step == SetupStep.CLOCK) { syncPreview(); invalidate() }
        }
        web?.let { w ->
            observeWhileAttached(w.address) { a ->
                if (a != address) { address = a; qr = a?.let { runCatching { Qr.encode(it.qrUrl) }.getOrNull() } }
                if (flow.step == SetupStep.PHONE) invalidate()
            }
            observeWhileAttached(w.pairing.browsers) { b -> paired = b.size; if (flow.step == SetupStep.PHONE || flow.step == SetupStep.WELCOME) invalidate() }
        }
    }

    // --- screen lifecycle ---

    override fun onScreenShown(args: Bundle?) {
        val wasShown = shown
        shown = true
        active = WeakReference(this)
        loadArt()
        refreshWifi()
        registerReceivers()
        val target = SetupStep.parse(args?.getString("step"))
        when {
            target == SetupStep.WELCOME -> { flow.restart(); clearDraft(); startIntro() }
            target != null -> jump(target)
            // Back from face enrolment or another app (Wi-Fi panel): carry on where we were.
            wasShown || flow.index > 0 -> Unit
            else -> startIntro()
        }
        if (pendingEnrol && hasCamera()) { pendingEnrol = false; openEnrol() }
        syncPreview()
        invalidate()
    }

    override fun onScreenHidden() {
        shown = false
        if (active?.get() === this) active = null
        anim?.cancel(); anim = null; p = 1f
        unregisterReceivers()
        syncPreview()
        // Free the wallpaper (~1.2 MB) while another screen is in front; it's redrawn in ~15 ms.
        art?.let { a -> art = null; sheetGlass.backdrop = null; barGlass.backdrop = null; tintGlass.backdrop = null; a.recycle() }
    }

    private fun loadArt() {
        if (art != null || artLoading) return
        artLoading = true
        Thread({
            val a = try { SetupWallpaper.render() } catch (e: Throwable) { Log.w(TAG, "wallpaper: ${e.javaClass.simpleName}"); null }
            main.post {
                artLoading = false
                if (a == null) return@post
                if (!shown) { a.recycle(); return@post }
                art = a
                invalidate()
            }
        }, "setup-wallpaper").start()
    }

    // --- navigation ---

    private fun engaged(step: SetupStep = flow.step): Boolean = when (step) {
        SetupStep.WIFI -> wifiConnected
        SetupStep.PHONE -> paired > 0
        SetupStep.PEOPLE -> draftName.isNotEmpty() || created.isNotEmpty() || profiles.any { it.id != Profile.SHARED_ID }
        else -> true
    }

    private fun goNext() {
        if (flow.isLast) { SetupFeature.finish(services); return }
        val from = flow.step
        // A name without a face is still a person (faces can be added later from Profiles).
        if (from == SetupStep.PEOPLE) saveDraft()
        if (flow.next(engaged())) transition(from, +1)
    }

    fun goBack() {
        val from = flow.step
        if (flow.back()) transition(from, -1)
    }

    private fun jump(to: SetupStep) {
        val from = flow.step
        val d = if (flow.steps.indexOf(to) >= flow.index) 1 else -1
        if (flow.go(to)) transition(from, d) else invalidate()
    }

    private fun startIntro() {
        intro = true
        wallX = 0f; wallFrom = 0f
        run(INTRO_MS)
    }

    private fun transition(from: SetupStep, d: Int) {
        intro = false
        dir = d
        welcomeInvolved = from == SetupStep.WELCOME || flow.step == SetupStep.WELCOME
        wallFrom = wallX
        syncPreview()
        run(STEP_MS)
    }

    private fun run(ms: Long) {
        anim?.cancel()
        if (!shown) { p = 1f; wallX = targetWall(); invalidate(); return }
        p = 0f
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = null
            addUpdateListener { a ->
                p = a.animatedValue as Float
                wallX = wallFrom + (targetWall() - wallFrom) * Motion.EASE.getInterpolation(p)
                applyPreviewMotion()
                invalidate()
            }
            start()
        }
    }

    private fun targetWall() = flow.index * SetupWallpaper.PARALLAX

    /** Content motion for the current frame: eased slide offset and fade. */
    private val slide get() = (1f - Motion.SPRING_SOFT.getInterpolation(p)) * 44f * dir
    private val fade get() = Motion.EASE.getInterpolation(min(1f, p * 1.5f))

    private fun applyPreviewMotion() {
        previewBox.alpha = fade
        previewBox.translationX = slide
    }

    // --- drawing ---

    private fun col(c: Int) = Night.map(c, night)
    private fun white(a: Float = 1f) = col(withAlpha(Color.WHITE, a))

    override fun onDraw(c: Canvas) {
        hitCount = 0
        val a = art?.takeIf { !night }
        sheetGlass.night = night; barGlass.night = night; tintGlass.night = night
        sheetGlass.backdrop = a?.backdrop; barGlass.backdrop = a?.backdrop; tintGlass.backdrop = a?.backdrop
        if (a != null) {
            bmpPaint.alpha = if (intro) (255 * fade).toInt() else 255
            c.drawBitmap(a.bitmap, -wallX, 0f, bmpPaint)
        }

        val step = flow.step
        if (step == SetupStep.WELCOME) drawWelcome(c)
        else {
            sheetGlass.alpha = if (welcomeInvolved) fade else 1f
            sheetGlass.draw(c, SHEET_L, SHEET_T, SHEET_R, SHEET_B, 40f, wallX, 0f)
            val f = fade
            val layered = f < 0.99f
            if (layered) c.saveLayerAlpha(SHEET_L, SHEET_T, SHEET_R, SHEET_B, (255 * f).toInt())
            c.save()
            c.translate(slide, 0f)
            when (step) {
                SetupStep.WIFI -> drawWifi(c)
                SetupStep.NAME -> drawName(c)
                SetupStep.LOCATION -> drawLocation(c)
                SetupStep.PHONE -> drawPhone(c)
                SetupStep.PEOPLE -> drawPeople(c)
                SetupStep.CLOCK -> drawClock(c)
                SetupStep.DONE -> drawDone(c)
                SetupStep.WELCOME -> Unit
            }
            c.restore()
            if (layered) c.restore()
        }
        drawBar(c)
    }

    private fun drawWelcome(c: Canvas) {
        val e = Motion.SPRING_SOFT.getInterpolation(p)
        val rise = if (intro) (1f - e) * 30f else 0f
        val f = fade
        val ox = if (intro) 0f else slide
        c.save()
        c.translate(ox, rise)
        val pre = "Welcome to"
        heroPre.draw(c, pre, 480f - heroPre.width(pre) / 2f, heroPre.baselineIn(104f, 34f), white(0.78f * f))
        val name = "EchoHome"
        hero.draw(c, name, 480f - hero.width(name) / 2f, hero.baselineIn(138f, 112f), white(f))
        val lines = listOf("Let’s get this Echo ready. It takes about two minutes,", "and you can skip any step.")
        var y = 270f
        for (l in lines) { heroBody.draw(c, l, 480f - heroBody.width(l) / 2f, heroBody.baselineIn(y, 28f), white(0.72f * f)); y += 28f }
        c.restore()
    }

    /** Tile + title + body in the left column; returns the y under the body. */
    private fun drawLeft(c: Canvas, kind: Kind, tile: Int, titleText: String, bodyText: String): Float {
        val x = COL_L
        var y = CONTENT_T
        if (night) { stroke.color = white(0.5f); stroke.strokeWidth = 1.5f; rect.set(x, y, x + 44f, y + 44f); c.drawRoundRect(rect, 12f, 12f, stroke) }
        else roundRect(c, x, y, x + 44f, y + 44f, 12f, tile)
        Glyph.draw(c, kind, x + 22f, y + 22f, 26f, white())
        y += 44f + 18f
        for (l in Draw.wrap(title, titleText, COL_W)) { title.draw(c, l, x, title.baselineIn(y, 42f), white()); y += 42f }
        y += 8f
        for (l in Draw.wrap(body, bodyText, COL_W)) { body.draw(c, l, x, body.baselineIn(y, 24f), white(0.72f)); y += 24f }
        return y
    }

    private fun drawWifi(c: Canvas) {
        drawLeft(c, Kind.WIFI, VesperColors.BLUE, "Wi-Fi",
            "EchoHome uses Wi-Fi for weather, calendars, AirPlay and the settings page on your phone.")
        val l = RIGHT_L; val t = CONTENT_T; val r = RIGHT_R; val b = t + 116f
        roundRect(c, l, t, r, b, 26f, white(0.08f))
        val ccx = l + 58f; val ccy = t + 58f
        fill.color = if (wifiConnected) col(VesperColors.BLUE) else white(0.14f)
        c.drawCircle(ccx, ccy, 32f, fill)
        Glyph.draw(c, Kind.WIFI, ccx, ccy + 1f, 30f, white())
        val name = when {
            !wifiOn -> "Wi-Fi is off"
            wifiConnected -> wifiName ?: "Connected"
            else -> "Not connected"
        }
        val tx = l + 110f
        big.draw(c, ellipsize(big, name, r - 24f - tx), tx, big.baselineIn(t + 26f, 34f), white())
        val status = when {
            wifiConnected && wifiName != null -> "Connected"
            wifiConnected -> "Connected to the internet"
            !wifiOn -> "Turn it on to connect"
            else -> "Pick a network to connect"
        }
        small.draw(c, status, tx, small.baselineIn(t + 64f, 22f), if (wifiConnected) col(VesperColors.GREEN) else white(0.6f))
        val label = if (wifiConnected) "Change network" else "Choose network"
        fillButton(c, button, label, l, b + 22f, 48f, 24f) { openWifiPanel() }
        small.draw(c, "Opens Android’s Wi-Fi panel.", l, small.baselineIn(b + 22f + 48f + 14f, 20f), white(0.5f))
    }

    private fun drawName(c: Canvas) {
        drawLeft(c, Kind.NAME, VesperColors.ORANGE, "Name this Echo",
            "AirPlay and Bluetooth show this name. You can change it from your phone later.")
        val current = DeviceName.get(settings)
        small.draw(c, "Shows up as", RIGHT_L, small.baselineIn(CONTENT_T, 20f), white(0.55f))
        nameText.draw(c, ellipsize(nameText, current, RIGHT_R - RIGHT_L), RIGHT_L, nameText.baselineIn(CONTENT_T + 22f, 40f), white())
        val cw = (RIGHT_R - RIGHT_L - 2 * 12f) / 3f
        val names = SetupChoices.NAMES
        val custom = current !in names
        for (i in 0 until names.size + 1) {
            val row = i / 3; val colI = i % 3
            val x = RIGHT_L + colI * (cw + 12f); val y = CHIPS_T + row * 60f
            if (i < names.size) {
                val n = names[i]
                chipBox(c, n, x, y, cw, 48f, selected = n == current) { setName(n) }
            } else {
                chipBox(c, if (custom) ellipsize(chip, current, cw - 28f) else "Other…", x, y, cw, 48f, selected = custom) {
                    askText("Name this Echo", if (custom) current else "", "e.g. Guest Room") { setName(it) }
                }
            }
        }
    }

    private fun drawLocation(c: Canvas) {
        drawLeft(c, Kind.LOCATION, VesperColors.GREEN, "Location",
            "For weather, sunrise and travel times. EchoHome finds it from your internet connection.")
        val l = RIGHT_L; val t = CONTENT_T; val r = RIGHT_R; val b = t + 100f
        roundRect(c, l, t, r, b, 26f, white(0.08f))
        val ccx = l + 50f; val ccy = t + 50f
        fill.color = col(VesperColors.GREEN); c.drawCircle(ccx, ccy, 28f, fill)
        Glyph.draw(c, Kind.LOCATION, ccx, ccy, 28f, white())
        val tx = l + 96f
        big.draw(c, ellipsize(big, location.name, r - 24f - tx), tx, big.baselineIn(t + 20f, 34f), white())
        val src = when (location.source) {
            HomeLocation.Source.AUTO -> "Found from your internet connection"
            HomeLocation.Source.MANUAL -> "Set from your phone"
            HomeLocation.Source.DEMO -> "Sample location (demo data)"
            HomeLocation.Source.FALLBACK -> "Not found yet. Set it from your phone."
        }
        small.draw(c, ellipsize(small, src, r - 24f - tx), tx, small.baselineIn(t + 56f, 22f), white(0.6f))

        val f = SetupChoices.fahrenheit(text(SetupChoices.KEY_UNIT), Locale.getDefault().country)
        var y = b + 24f
        label.draw(c, "Temperature", l, label.baselineIn(y, 40f), white())
        segmented(c, r - 176f, y, 176f, 40f, listOf("°F", "°C"), if (f) 0 else 1) { i ->
            settings.putString(SetupChoices.KEY_UNIT, if (i == 0) "F" else "C")
        }
        y += 56f
        label.draw(c, "Clock", l, label.baselineIn(y, 40f), white())
        val is24 = DateFormat.is24HourFormat(context)
        segmented(c, r - 236f, y, 236f, 40f, listOf("12-hour", "24-hour"), if (is24) 1 else 0) { i -> set24(i == 1) }
        y += 56f
        small.draw(c, timeNote ?: "To pick the place yourself, use your phone.", l, small.baselineIn(y, 20f), white(0.5f))
    }

    private fun drawPhone(c: Canvas) {
        var y = drawLeft(c, Kind.PHONE, 0xFF5E5CE6.toInt(), "Your phone",
            "Scan the code with your phone’s camera, then tap Pair. Calendars, the assistant’s Anthropic API key and every other setting are there.")
        val a = address
        y += 16f
        if (a != null) {
            val s = a.display
            val w = button.width(s) + 36f
            roundRect(c, COL_L, y, COL_L + w, y + 44f, 22f, white(0.12f))
            button.draw(c, s, COL_L + 18f, button.baselineIn(y, 44f), white())
        } else if (web == null) {
            small.draw(c, "The settings page isn’t running.", COL_L, small.baselineIn(y, 20f), white(0.6f))
        }
        // QR plate: plain fill on the sheet (no glass on glass).
        val ps = 236f
        val pl = RIGHT_L + (RIGHT_R - RIGHT_L - ps) / 2f
        val pt = CONTENT_T
        val q = qr
        if (q != null && a != null) {
            roundRect(c, pl, pt, pl + ps, pt + ps, 24f, if (night) Night.COLOR else Color.WHITE)
            val module = Math.floor((ps / (q.size + 8)).toDouble()).toFloat()
            val side = module * q.size
            Draw.qr(c, q, pl + (ps - side) / 2f, pt + (ps - side) / 2f, side, Color.BLACK)
        } else {
            roundRect(c, pl, pt, pl + ps, pt + ps, 24f, white(0.08f))
            Draw.centered(c, label, "No Wi-Fi yet", pl + ps / 2f, label.baselineIn(pt + ps / 2f - 12f, 24f), white(0.6f))
        }
        val status = when (paired) { 0 -> "No phone paired yet"; 1 -> "1 phone paired"; else -> "$paired phones paired" }
        val sw = small.width(status) + if (paired > 0) 16f else 0f
        var sx = pl + ps / 2f - sw / 2f
        val sy = pt + ps + 18f
        if (paired > 0) { fill.color = col(VesperColors.GREEN); c.drawCircle(sx + 4f, sy + 10f, 4f, fill); sx += 16f }
        small.draw(c, status, sx, small.baselineIn(sy, 20f), if (paired > 0) white(0.85f) else white(0.55f))
    }

    private fun drawPeople(c: Canvas) {
        drawLeft(c, Kind.PERSON, VesperColors.PINK, if (created.isEmpty()) "Add yourself" else "People",
            "Each person gets their own clock colour, alarm and calendar. The Echo knows who’s there by face. No images are saved.")
        val l = RIGHT_L; val t = CONTENT_T
        val saved = draftId?.let { id -> profiles.firstOrNull { it.id == id } }
        // avatar
        val ax = l + 46f; val ay = t + 46f
        avatar(c, ax, ay, 44f, draftColor, draftName)
        // name field
        val fl = l + 112f; val fr = RIGHT_R
        roundRect(c, fl, t + 18f, fr, t + 74f, 28f, white(0.1f))
        if (draftName.isEmpty()) big.draw(c, "Your name", fl + 22f, big.baselineIn(t + 18f, 56f), white(0.42f))
        else big.draw(c, ellipsize(big, draftName, fr - fl - 44f), fl + 22f, big.baselineIn(t + 18f, 56f), white())
        hit(fl, t + 18f, fr, t + 74f) { askText(if (created.isEmpty()) "Your name" else "Name", draftName, "Name") { setDraftName(it) } }
        // colours (plain fills: no glass on glass)
        val sy = t + 120f
        val step = (RIGHT_R - l - 36f) / (PALETTE.size - 1)
        for ((i, color) in PALETTE.withIndex()) {
            val cx = l + 18f + i * step
            if (color == draftColor) { stroke.color = white(0.9f); stroke.strokeWidth = 2.5f; c.drawCircle(cx, sy + 18f, 22f, stroke) }
            fill.color = col(color); c.drawCircle(cx, sy + 18f, 16f, fill)
            hit(cx - 24f, sy - 6f, cx + 24f, sy + 42f) { setDraftColor(color) }
        }
        // face
        val by = t + 172f
        val canAdd = draftName.isNotEmpty()
        val faceLabel = when {
            saved?.enrolled == true -> "Add face again"
            created.isEmpty() || draftId == created.first() -> "Add my face"
            else -> "Add face"
        }
        val br = fillButton(c, button, faceLabel, l, by, 48f, 24f, enabled = canAdd) { startEnrol() }
        val faceStatus = when {
            saved == null -> null
            saved.enrolled -> "Face added"
            else -> "No face yet"
        }
        if (faceStatus != null) {
            val fx = br.right + 18f
            if (saved?.enrolled == true) Glyph.draw(c, Kind.CHECK, fx + 10f, by + 24f, 20f, col(VesperColors.GREEN))
            small.draw(c, faceStatus, fx + if (saved?.enrolled == true) 26f else 0f, small.baselineIn(by, 48f),
                if (saved?.enrolled == true) col(VesperColors.GREEN) else white(0.55f))
        }
        val note = when {
            services.system.state.value.cameraBlocked -> "The camera is off. Open the shutter to add a face."
            !hasCamera() -> "The Echo will ask to use its camera."
            else -> "Takes about 30 seconds. Look at the screen."
        }
        small.draw(c, note, l, small.baselineIn(by + 58f, 20f), white(0.5f))
        if (saved != null) {
            fillButton(c, small, "Add someone else", l, by + 92f, 36f, 16f) { newDraft() }
        }
    }

    private fun drawClock(c: Canvas) {
        drawLeft(c, Kind.CLOCK, VesperColors.PURPLE, "Clock and Night",
            "Pick what StandBy shows. Night mode turns it dim red.")
        val y = NIGHT_T
        label.draw(c, "Night mode", COL_L, label.baselineIn(y, 20f), white())
        val mode = SetupChoices.Night.of(text(SetupChoices.KEY_NIGHT_MODE) ?: "auto")
        val modes = SetupChoices.Night.entries
        segmented(c, COL_L, y + 28f, COL_W, 40f, listOf("Automatic", "Schedule", "Off"), modes.indexOf(mode)) { i ->
            settings.putString(SetupChoices.KEY_NIGHT_MODE, modes[i].stored)
        }
        val detail = when (mode) {
            SetupChoices.Night.AUTO -> "Dim red when the room gets dark."
            SetupChoices.Night.SCHEDULE -> "Dim red from " + SetupChoices.scheduleText(
                text(SetupChoices.KEY_NIGHT_START) ?: "22:00", text(SetupChoices.KEY_NIGHT_END) ?: "07:00",
                DateFormat.is24HourFormat(context)) + "."
            SetupChoices.Night.OFF -> "Always full colour."
        }
        small.draw(c, detail, COL_L, small.baselineIn(y + 78f, 20f), white(0.55f))

        // The live preview is the child view; behind it a plain plate in case a face is see-through.
        roundRect(c, RIGHT_L, CONTENT_T, RIGHT_R, CONTENT_T + PREVIEW_H, 22f, Color.BLACK)
        if (preview == null) Draw.centered(c, label, if (faces.isEmpty()) "No faces" else "", (RIGHT_L + RIGHT_R) / 2f,
            label.baselineIn(CONTENT_T + PREVIEW_H / 2f - 12f, 24f), white(0.5f))
        val ry = CONTENT_T + PREVIEW_H + 14f
        circleButton(c, Kind.LEFT, RIGHT_L + 22f, ry + 22f) { pickFace(faceIndex - 1) }
        circleButton(c, Kind.RIGHT, RIGHT_R - 22f, ry + 22f) { pickFace(faceIndex + 1) }
        val f = faces.getOrNull(faceIndex)
        if (f != null) {
            Draw.centered(c, cardTitle, f.name, (RIGHT_L + RIGHT_R) / 2f, cardTitle.baselineIn(ry, 26f), white())
            val n = "${faceIndex + 1} of ${faces.size}"
            Draw.centered(c, small, n, (RIGHT_L + RIGHT_R) / 2f, small.baselineIn(ry + 26f, 18f), white(0.5f))
        }
    }

    private fun drawDone(c: Canvas) {
        Draw.centered(c, doneTitle, "You’re all set", 480f, doneTitle.baselineIn(CONTENT_T - 6f, 48f), white())
        Draw.centered(c, body, "Three things to know:", 480f, body.baselineIn(CONTENT_T + 46f, 24f), white(0.72f))
        val wake = bool(SetupChoices.KEY_WAKE_WORD, false)
        val key = services.config.secrets.anthropicConfigured
        val cards = listOf(
            Triple(Kind.SWIPE, "Swipe sideways", "For widgets, weather, music and the other screens."),
            Triple(Kind.PULL, "Pull down", "From the top edge, for Control Center: Wi-Fi, volume and Night mode."),
            when {
                !key -> Triple(Kind.SPARKLE, "Ask the assistant", "Add your Anthropic API key from your phone first.")
                wake -> Triple(Kind.SPARKLE, "Say “Hey Nova”", "Ask about the weather, set a timer or play music.")
                else -> Triple(Kind.SPARKLE, "Ask the assistant", "Swipe to it and tap to talk. “Hey Nova” can be turned on from your phone.")
            },
        )
        val gap = 20f
        val w = (SHEET_R - SHEET_L - 80f - 2 * gap) / 3f
        val t = CONTENT_T + 82f
        val b = SHEET_B - 40f
        val tints = listOf(VesperColors.BLUE, VesperColors.ORANGE, VesperColors.PURPLE)
        for ((i, card) in cards.withIndex()) {
            val l = SHEET_L + 40f + i * (w + gap)
            roundRect(c, l, t, l + w, b, 28f, white(0.08f))
            val gx = l + 22f + 24f; val gy = t + 22f + 24f
            fill.color = col(tints[i]); c.drawCircle(gx, gy, 24f, fill)
            Glyph.draw(c, card.first, gx, gy, 24f, white())
            var y = t + 84f
            cardTitle.draw(c, ellipsize(cardTitle, card.second, w - 44f), l + 22f, cardTitle.baselineIn(y, 26f), white())
            y += 30f
            for (line in Draw.wrap(cardBody, card.third, w - 44f).take(3)) { cardBody.draw(c, line, l + 22f, cardBody.baselineIn(y, 21f), white(0.68f)); y += 21f }
        }
    }

    private fun drawBar(c: Canvas) {
        val t = BAR_T; val h = 44f
        val step = flow.step
        // left: Back (glass), or a quiet "Skip setup" on Welcome
        if (step == SetupStep.WELCOME) {
            val s = "Skip setup"
            val w = button.width(s) + 40f
            button.draw(c, s, SHEET_L + 20f, button.baselineIn(t, h), white(0.7f))
            hit(SHEET_L - 8f, t - 8f, SHEET_L + w + 8f, t + h + 8f) { SetupFeature.finish(services) }
        } else {
            val s = "Back"
            val w = 16f + 22f + 6f + button.width(s) + 20f
            barGlass.alpha = 1f
            barGlass.draw(c, SHEET_L, t, SHEET_L + w, t + h, -1f, wallX, 0f)
            Glyph.draw(c, Kind.LEFT, SHEET_L + 16f + 11f, t + h / 2f, 22f, white())
            button.draw(c, s, SHEET_L + 16f + 22f + 6f, button.baselineIn(t, h), white())
            hit(SHEET_L - 8f, t - 8f, SHEET_L + w + 8f, t + h + 8f) { goBack() }
        }
        // centre: page dots in a glass capsule (as in Customize.dc.html)
        val n = flow.steps.size
        val dot = 8f; val gap = 8f; val wide = 22f
        val dotsW = (n - 1) * dot + wide + (n - 1) * gap
        val cw = dotsW + 32f; val ch = 28f
        val cl = 480f - cw / 2f; val ct = t + (h - ch) / 2f
        barGlass.draw(c, cl, ct, cl + cw, ct + ch, -1f, wallX, 0f)
        var x = cl + 16f
        val prev = flow.index - dir
        for (i in 0 until n) {
            val grow = when (i) {
                flow.index -> Motion.EASE.getInterpolation(p)
                prev -> if (intro) 0f else 1f - Motion.EASE.getInterpolation(p)
                else -> 0f
            }
            val dw = dot + (wide - dot) * grow
            fill.color = white(0.35f + 0.6f * grow)
            rect.set(x, ct + (ch - dot) / 2f, x + dw, ct + (ch + dot) / 2f)
            c.drawRoundRect(rect, dot / 2f, dot / 2f, fill)
            x += dw + gap
        }
        // right: the one tinted action
        val s = flow.rightLabel(engaged())
        val w = button.width(s) + 2 * 26f
        val r = SHEET_R
        if (night) roundRect(c, r - w, t, r, t + h, h / 2f, withAlpha(Night.COLOR, 0.35f))
        else tintGlass.draw(c, r - w, t, r, t + h, -1f, wallX, 0f)
        button.draw(c, s, r - w + 26f, button.baselineIn(t, h), white())
        hit(r - w - 8f, t - 8f, r + 8f, t + h + 8f) { goNext() }
    }

    // --- small drawing helpers (plain fills; glass only for the sheet and the bar) ---

    private fun roundRect(c: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) {
        fill.color = color; rect.set(l, t, r, b); c.drawRoundRect(rect, radius, radius, fill)
    }

    private val lastButton = RectF()

    private fun fillButton(c: Canvas, text: SpecText, s: String, l: Float, t: Float, h: Float, padX: Float,
                           enabled: Boolean = true, action: () -> Unit): RectF {
        val w = text.width(s) + 2 * padX
        roundRect(c, l, t, l + w, t + h, h / 2f, white(if (enabled) 0.16f else 0.07f))
        text.draw(c, s, l + padX, text.baselineIn(t, h), white(if (enabled) 1f else 0.35f))
        if (enabled) hit(l - 6f, t - 6f, l + w + 6f, t + h + 6f, action)
        lastButton.set(l, t, l + w, t + h)
        return lastButton
    }

    private fun chipBox(c: Canvas, s: String, l: Float, t: Float, w: Float, h: Float, selected: Boolean, action: () -> Unit) {
        roundRect(c, l, t, l + w, t + h, h / 2f, if (selected) white() else white(0.1f))
        val fg = if (selected) Color.BLACK else white()
        Draw.centered(c, chip, s, l + w / 2f, chip.baselineIn(t, h), fg)
        hit(l, t, l + w, t + h, action)
    }

    private fun segmented(c: Canvas, l: Float, t: Float, w: Float, h: Float, labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
        roundRect(c, l, t, l + w, t + h, h / 2f, white(0.1f))
        val sw = w / labels.size
        for ((i, s) in labels.withIndex()) {
            val sl = l + i * sw
            if (i == selected) roundRect(c, sl + 3f, t + 3f, sl + sw - 3f, t + h - 3f, (h - 6f) / 2f, white())
            Draw.centered(c, label, s, sl + sw / 2f, label.baselineIn(t, h), if (i == selected) Color.BLACK else white(0.85f))
            hit(sl, t - 4f, sl + sw, t + h + 4f) { if (i != selected) { onPick(i); invalidate() } }
        }
    }

    private fun circleButton(c: Canvas, kind: Kind, cx: Float, cy: Float, action: () -> Unit) {
        fill.color = white(0.14f); c.drawCircle(cx, cy, 22f, fill)
        Glyph.draw(c, kind, cx, cy, 20f, white())
        hit(cx - 30f, cy - 30f, cx + 30f, cy + 30f, action)
    }

    /** As Profiles.dc.html: gradient(160deg, colour lightened 25 %, colour) + initial. */
    private fun avatar(c: Canvas, cx: Float, cy: Float, r: Float, color: Int, name: String) {
        val ang = Math.toRadians(160.0)
        val dx = sin(ang).toFloat() * r; val dy = -cos(ang).toFloat() * r
        val base = col(color)
        avatarPaint.shader = if (night) null else LinearGradient(cx - dx, cy - dy, cx + dx, cy + dy, lighten(base, 0.25f), base, Shader.TileMode.CLAMP)
        avatarPaint.color = base
        c.drawCircle(cx, cy, r, avatarPaint)
        if (name.isEmpty()) Glyph.draw(c, Kind.PERSON, cx, cy, 40f, white())
        else {
            val s = name.take(1).uppercase()
            initial.draw(c, s, cx - initial.width(s) / 2f, initial.baselineIn(cy - r, 2 * r), white())
        }
    }

    private fun ellipsize(t: SpecText, s: String, maxW: Float): String {
        if (t.width(s) <= maxW) return s
        var e = s
        while (e.length > 1 && t.width("$e…") > maxW) e = e.dropLast(1)
        return e.trimEnd() + "…"
    }

    private fun hit(l: Float, t: Float, r: Float, b: Float, action: () -> Unit) {
        val h = if (hitCount < hits.size) hits[hitCount] else Hit().also { hits += it }
        h.r.set(l, t, r, b); h.action = action
        hitCount++
    }

    // --- touch ---

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // Last recorded wins (drawn on top).
            for (i in hitCount - 1 downTo 0) {
                val h = hits[i]
                if (h.r.contains(e.x, e.y)) { h.action?.invoke(); invalidate(); return true }
            }
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (abs(vx) < 700f || abs(vx) < abs(vy) * 1.5f) return false
            if (vx < 0 && !flow.isLast) goNext() else if (vx > 0) goBack()
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean { gestures.onTouchEvent(e); return true }

    // --- actions ---

    private fun setName(raw: String) {
        val n = SetupChoices.cleanName(raw) ?: return
        settings.putString(DeviceName.KEY, n)
        invalidate()
    }

    private fun set24(on: Boolean) {
        timeNote = try {
            Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, if (on) "24" else "12")
            null
        } catch (e: Exception) {
            Log.w(TAG, "12/24 h: ${e.javaClass.simpleName}")
            "Couldn’t change it. Use Android’s Date & time."
        }
        invalidate()
    }

    private fun openWifiPanel() {
        try { context.startActivity(Intent(Settings.Panel.ACTION_WIFI)) } catch (e: Exception) {
            try { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }

    private fun setDraftName(raw: String) {
        val n = raw.filter { !it.isISOControl() }.trim().take(20)
        if (n.isEmpty()) return
        draftName = n
        draftId?.let { id -> profiles.firstOrNull { it.id == id }?.let { services.profiles.upsert(it.copy(name = n)) } }
        invalidate()
    }

    private fun setDraftColor(color: Int) {
        draftColor = color
        draftId?.let { id -> profiles.firstOrNull { it.id == id }?.let { services.profiles.upsert(it.copy(color = color)) } }
        invalidate()
    }

    /** "Run setup again": a clean People step, and the clock step starts at the stored face. */
    private fun clearDraft() {
        draftId = null; draftName = ""; draftColor = PALETTE[0]
        created.clear(); pendingEnrol = false; faceChosen = false; timeNote = null
    }

    private fun newDraft() {
        draftId = null; draftName = ""
        val used = profiles.map { it.color }.toSet()
        draftColor = PALETTE.firstOrNull { it !in used } ?: PALETTE[profiles.size % PALETTE.size]
        invalidate()
    }

    /** Saves the person being added, once (later edits update it). */
    private fun saveDraft() {
        if (draftName.isEmpty() || draftId != null) return
        val id = draftName.lowercase().replace(Regex("[^a-z0-9]"), "").ifEmpty { "person" } + "-" + (System.currentTimeMillis() % 100000)
        services.profiles.upsert(Profile(id, draftName, draftColor, faceId = text(SetupChoices.KEY_FACE) ?: "digital"))
        draftId = id
        created += id
    }

    /** Saves the person, then opens face enrolment; it comes back here when done. */
    private fun startEnrol() {
        if (draftName.isEmpty()) return
        saveDraft()
        if (!hasCamera()) {
            pendingEnrol = true
            (context as? Activity)?.requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
            return
        }
        openEnrol()
    }

    private fun openEnrol() {
        val id = draftId ?: return
        services.navigator.show(SCREEN_ENROL, Bundle().apply { putString("profile", id); putString("return", SetupFeature.SCREEN) })
    }

    private fun hasCamera() = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun pickFace(i: Int) {
        if (faces.isEmpty()) return
        faceIndex = ((i % faces.size) + faces.size) % faces.size
        faceChosen = true
        val id = faces[faceIndex].id
        settings.putString(SetupChoices.KEY_FACE, id)
        // People added in this run follow the face picked here.
        for (pid in created) profiles.firstOrNull { it.id == pid }?.let { if (it.faceId != id) services.profiles.upsert(it.copy(faceId = id)) }
        syncPreview()
        invalidate()
    }

    private fun askText(titleText: String, initialText: String, hint: String, onOk: (String) -> Unit) {
        val input = EditText(context).apply {
            setText(initialText); setSelection(text.length)
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(SetupChoices.NAME_MAX))
        }
        val box = FrameLayout(context).apply { setPadding(48, 16, 48, 0); addView(input) }
        AlertDialog.Builder(context)
            .setTitle(titleText)
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("OK") { _, _ -> onOk(input.text.toString()) }
            .show()
    }

    // --- face preview (the clock step) ---

    private fun syncPreview() {
        val onStep = shown && flow.step == SetupStep.CLOCK && faces.isNotEmpty()
        if (onStep && !faceChosen) { faceIndex = SetupChoices.faceIndex(faces.map { it.id }, text(SetupChoices.KEY_FACE)); faceChosen = true }
        val spec = if (onStep) faces.getOrNull(faceIndex.coerceIn(0, faces.lastIndex)) else null
        if (spec?.id != previewId) {
            preview?.let { it.onScreenHidden(); previewBox.removeView(it.view) }
            preview = null; previewId = null
            if (spec != null) {
                val fv = try { spec.factory(context) } catch (e: Exception) { Log.w(TAG, "face ${spec.id}: ${e.javaClass.simpleName}"); null }
                if (fv != null) {
                    val v = fv.view
                    v.pivotX = 0f; v.pivotY = 0f
                    v.scaleX = PREVIEW_W / 960f; v.scaleY = PREVIEW_W / 960f
                    previewBox.addView(v, LayoutParams(960, 480))
                    preview = fv; previewId = spec.id
                    fv.onScreenShown(null)
                }
            }
        }
        preview?.setConfig(FaceConfig(color = faceColor(spec), complications = true, night = night))
        previewBox.visibility = if (preview != null) VISIBLE else GONE
        applyPreviewMotion()
    }

    private fun faceColor(spec: FaceSpec?): Int {
        spec ?: return Color.WHITE
        val s = text("clock.color.${spec.id}")
        val parsed = s?.let { runCatching { Color.parseColor(it) }.getOrNull() }
        return parsed ?: spec.colorOptions.firstOrNull() ?: Color.WHITE
    }

    // --- system signals (only while shown) ---

    private var receiving = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_TIME_TICK) { preview?.onTimeChanged(); return }
            refreshWifi()
            if (flow.step == SetupStep.WIFI) invalidate()
        }
    }

    private fun registerReceivers() {
        if (receiving) return
        receiving = true
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(Intent.ACTION_TIME_TICK)
        })
    }

    private fun unregisterReceivers() {
        if (!receiving) return
        receiving = false
        try { context.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
    }

    @Suppress("DEPRECATION")
    private fun refreshWifi() {
        val wm = context.applicationContext.getSystemService(WifiManager::class.java)
        val info = try { wm?.connectionInfo } catch (_: Exception) { null }
        wifiOn = try { wm?.isWifiEnabled == true } catch (_: Exception) { false }
        wifiConnected = (info?.networkId ?: -1) != -1
        // Android hides the SSID from apps without the location permission: "<unknown ssid>".
        wifiName = info?.ssid?.trim('"')?.takeIf { it.isNotBlank() && !it.startsWith("<") }
    }

    // --- settings reads (tolerant of the type another writer used) ---

    private fun text(key: String): String? = try { settings.getString(key, "").trim().ifEmpty { null } } catch (_: ClassCastException) { null }

    private fun bool(key: String, default: Boolean): Boolean = try { settings.getBoolean(key, default) } catch (_: ClassCastException) {
        try { settings.getString(key, default.toString()).toBoolean() } catch (_: ClassCastException) { default }
    }

    /** Back key (see [SetupFeature]). */
    fun onBackKey() = goBack()

    /** Holds the face preview: clipped to a rounded rect, and a tap anywhere picks the next face. */
    private class PreviewBox(context: Context) : FrameLayout(context) {
        var onTap: (() -> Unit)? = null
        init {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, 22f)
            }
        }
        override fun onInterceptTouchEvent(ev: MotionEvent) = true
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP) onTap?.invoke()
            return true
        }
    }

    companion object {
        private const val TAG = "VesperSetup"
        const val SCREEN_ENROL = "profiles-enrol"
        private const val CAMERA_REQUEST = 7302
        private const val INTRO_MS = 900L
        private const val STEP_MS = 450L

        const val SHEET_L = 48f
        const val SHEET_T = 40f
        const val SHEET_R = 912f
        const val SHEET_B = 400f
        const val CONTENT_T = 80f
        const val COL_L = 88f
        const val COL_W = 320f
        const val RIGHT_L = 448f
        const val RIGHT_R = 872f
        const val CHIPS_T = 164f
        const val NIGHT_T = 262f
        const val PREVIEW_W = 424f
        const val PREVIEW_H = 212f
        const val BAR_T = 412f

        val PALETTE = listOf(VesperColors.PINK, VesperColors.ORANGE, VesperColors.YELLOW, VesperColors.GREEN,
            VesperColors.CYAN, VesperColors.BLUE, VesperColors.PURPLE, VesperColors.RED)

        /** The setup on screen, for the Back key. */
        var active: WeakReference<OnboardingView>? = null
            private set

        fun lighten(color: Int, f: Float): Int {
            fun ch(v: Int) = (v + (255 - v) * f).toInt().coerceIn(0, 255)
            return Color.rgb(ch(Color.red(color)), ch(Color.green(color)), ch(Color.blue(color)))
        }
    }
}
