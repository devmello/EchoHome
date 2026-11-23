package app.vesper.feature.camera.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.RectF
import android.os.Bundle
import android.text.InputType
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import app.vesper.core.design.VesperColors
import app.vesper.core.design.SpecText
import app.vesper.core.design.withAlpha
import app.vesper.core.model.VesperScreen
import app.vesper.core.model.VesperServices
import app.vesper.core.model.Profile
import app.vesper.core.model.SwitchReason
import app.vesper.core.model.observeWhileAttached
import app.vesper.feature.camera.CameraPipeline
import app.vesper.feature.camera.OffReason
import app.vesper.feature.camera.profiles.FaceProfileManager
import app.vesper.feature.camera.profiles.PersonalStuff
import app.vesper.core.model.Secrets
import kotlinx.coroutines.flow.combine

/**
 * "profiles" (settings, not in the pager): who uses this Echo, Auto by face, enrol / forget a
 * face, add / delete a person. There's no artboard for it yet (NOTES §2.2 lists first-run /
 * face enrolment as still to design), so it follows Profiles.dc.html's avatar and type styles
 * and the system rules: content on plain fills, one tinted primary action (Done).
 *
 * Tap a person: switch to them. Long-press: delete. "Enrol face" opens "profiles-enrol".
 * Under the people, "Your stuff" lists what's personal for the active person ([PersonalStuff]):
 * clock, wallpaper, Night mode, alarm sound (these three cycle on tap), alarms, calendar.
 */
@SuppressLint("ViewConstructor")
class ProfilesView(
    context: Context,
    private val services: VesperServices,
    private val manager: FaceProfileManager,
    private val pipeline: CameraPipeline,
) : View(context), VesperScreen {

    private val p = Painter()
    private val title = SpecText(34f, 800, -0.02f)
    private val sub = SpecText(15f, 600)
    private val name = SpecText(20f, 700)
    private val small = SpecText(13f, 600)
    private val btn = SpecText(14f, 700)
    private val row = SpecText(15f, 700)
    private val foot = SpecText(12f, 600)

    private var people: List<Profile> = emptyList()
    private var active: Profile = Profile.SHARED
    private var auto = true

    private val done = RectF()
    private val autoSwitch = RectF()
    private val allow = RectF()
    private val tiles = mutableListOf<Pair<RectF, Profile?>>() // null = "Add person"
    private val actions = mutableListOf<Pair<RectF, Profile>>()
    /** "Your stuff" chips: hit rect → kind (the editable ones cycle, Alarms opens the list). */
    private val stuff = mutableListOf<Pair<RectF, PersonalStuff.Kind>>()
    private val chipText = SpecText(13f, 700)
    private val stuffTitle = SpecText(15f, 700)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observeWhileAttached(combine(manager.profiles, manager.active, manager.autoByFace) { a, b, c -> Triple(a, b, c) }) { (l, a, au) ->
            people = l; active = a; auto = au; invalidate()
        }
        observeWhileAttached(pipeline.ui) { invalidate() }
        observeWhileAttached(services.alarms.alarms) { invalidate() }
        observeWhileAttached(services.secrets.changes) { invalidate() }
    }

    override fun onScreenShown(args: Bundle?) = invalidate()

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        title.draw(c, "Profiles", 40f, title.baselineIn(30f), Color.WHITE)
        sub.draw(c, "Who’s using this Echo. Each person gets their own clock, colour, alarm and calendar.", 40f,
            sub.baselineIn(30f + title.normalLineHeight + 2f), withAlpha(Color.WHITE, 0.6f))
        p.primary(c, btn, "Done", 960f - 32f, 30f, 44f, 22f, done, right = true)

        // Auto by face
        val ry = 112f
        row.draw(c, "Auto by face", 40f, row.baselineIn(ry, 31f), Color.WHITE)
        val camState = when {
            !pipeline.hasPermission() -> "Camera permission needed"
            services.system.state.value.cameraBlocked -> "Camera off"
            pipeline.ui.value.off == OffReason.DARK -> "Too dark to see · paused"
            else -> "Switches to whoever the camera recognises"
        }
        small.draw(c, camState, 40f + row.width("Auto by face") + 14f, small.baselineIn(ry, 31f), withAlpha(Color.WHITE, 0.55f))
        p.switch(c, 960f - 32f - 51f, ry, auto, autoSwitch)
        allow.setEmpty()
        if (!pipeline.hasPermission()) p.button(c, btn, "Allow camera", 960f - 32f - 51f - 16f, ry - 3f, 36f, 16f, allow, right = true)

        // people
        tiles.clear(); actions.clear()
        val tw = 168f; val th = 214f; val gap = 14f
        val shown = people.take(4)
        var x = 40f
        val ty = 160f
        for (person in shown) {
            val r = RectF(x, ty, x + tw, ty + th)
            tiles += r to person
            p.roundRect(c, r.left, r.top, r.right, r.bottom, 28f, withAlpha(Color.WHITE, if (person.id == active.id) 0.14f else 0.08f))
            val color = if (person.id == Profile.SHARED_ID) 0xFF8E8E93.toInt() else person.color
            p.avatar(c, r.centerX(), r.top + 62f, 38f, color, person.name, ring = person.id == active.id)
            val n = person.name
            name.draw(c, n, r.centerX() - name.width(n) / 2f, name.baselineIn(r.top + 116f), Color.WHITE)
            val status = when {
                person.id == Profile.SHARED_ID -> "When nobody’s recognised"
                person.enrolled -> "Face enrolled"
                else -> "No face yet"
            }
            small.draw(c, status, r.centerX() - small.width(status) / 2f, small.baselineIn(r.top + 144f),
                if (person.enrolled) withAlpha(VesperColors.GREEN, 0.9f) else withAlpha(Color.WHITE, 0.55f))
            if (person.id != Profile.SHARED_ID) {
                val label = if (person.enrolled) "Forget face" else "Enrol face"
                val bw = 28f + btn.width(label)
                val br = RectF()
                p.button(c, btn, label, r.centerX() - bw / 2f, r.bottom - 50f, 34f, 14f, br)
                actions += br to person
            }
            x += tw + gap
        }
        if (people.size <= 4) {
            val r = RectF(x, ty, x + tw, ty + th)
            tiles += r to null
            p.stroke.color = withAlpha(Color.WHITE, 0.3f); p.stroke.strokeWidth = 1.5f
            p.stroke.pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
            c.drawRoundRect(RectF(r.left + 1f, r.top + 1f, r.right - 1f, r.bottom - 1f), 28f, 28f, p.stroke)
            p.stroke.pathEffect = null
            p.fill.color = withAlpha(Color.WHITE, 0.6f)
            val cx = r.centerX(); val cy = r.top + 80f
            c.drawRect(cx - 14f, cy - 1.5f, cx + 14f, cy + 1.5f, p.fill)
            c.drawRect(cx - 1.5f, cy - 14f, cx + 1.5f, cy + 14f, p.fill)
            val l = "Add person"
            name.draw(c, l, cx - name.width(l) / 2f, name.baselineIn(r.top + 116f), withAlpha(Color.WHITE, 0.8f))
        }

        drawStuff(c, ty + th + 10f)

        foot.draw(c, "On-device recognition · no images saved · face templates are encrypted on this Echo", 22f,
            foot.baselineIn(480f - 14f - foot.normalLineHeight), withAlpha(Color.WHITE, 0.5f))
    }

    /**
     * "Your stuff": what's personal for the active person, one row of plain-fill chips. Wallpaper,
     * Night and Alarm sound cycle on tap (through "Everyone’s" = follow the shared settings);
     * the alarm count opens the Alarms screen. Shared: a hint to pick a person.
     */
    private fun drawStuff(c: Canvas, top: Float) {
        stuff.clear()
        val person = people.firstOrNull { it.id == active.id }?.takeIf { it.id != Profile.SHARED_ID }
        if (person == null) {
            stuffTitle.draw(c, "Everyone’s stuff", 40f, stuffTitle.baselineIn(top), Color.WHITE)
            val h = "Tap a person to see what’s theirs: clock, wallpaper, Night mode, alarm sound, alarms and calendar."
            small.draw(c, h, 40f, small.baselineIn(top + stuffTitle.normalLineHeight + 8f), withAlpha(Color.WHITE, 0.6f))
            return
        }
        stuffTitle.draw(c, "${possessive(person.name)} stuff", 40f, stuffTitle.baselineIn(top), Color.WHITE)
        val face = services.faces.byId(person.faceId)?.name ?: person.faceId
        val alarms = services.alarms.alarms.value.count { it.profileId == person.id }
        val cal = services.secrets.isSet(Secrets.calendarKeyFor(person.id))
        var x = 40f
        val y = top + stuffTitle.normalLineHeight + 6f
        for (item in PersonalStuff.items(person, face, alarms, cal)) {
            val w = 24f + chipText.width(item.text)
            if (x + w > 920f) break
            val r = RectF(x, y, x + w, y + 30f)
            val bg = if (item.editable || item.kind == PersonalStuff.Kind.ALARMS) withAlpha(person.color, 0.26f) else withAlpha(Color.WHITE, 0.1f)
            p.button(c, chipText, item.text, x, y, 30f, 12f, r, bg = bg)
            if (item.editable || item.kind == PersonalStuff.Kind.ALARMS) stuff += r to item.kind
            x += w + 8f
        }
    }

    private fun possessive(name: String) = name.trim().let { if (it.endsWith("s", true)) "$it’" else "$it’s" }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { tap(e.x, e.y); return true }
        override fun onLongPress(e: MotionEvent) {
            val person = tiles.firstOrNull { it.first.contains(e.x, e.y) }?.second ?: return
            if (person.id == Profile.SHARED_ID) return
            AlertDialog.Builder(context)
                .setTitle("Delete ${person.name}?")
                .setMessage("Removes ${person.name}’s profile and face template from this Echo.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ -> manager.delete(person.id) }
                .show()
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent) = gestures.onTouchEvent(e)

    private fun tap(x: Float, y: Float) {
        when {
            done.contains(x, y) -> services.navigator.home()
            !allow.isEmpty && allow.contains(x, y) -> (context as? Activity)?.requestPermissions(arrayOf(Manifest.permission.CAMERA), 7301)
            autoSwitch.contains(x, y) -> manager.setAutoByFace(!auto)
            stuff.any { it.first.contains(x, y) } -> {
                val kind = stuff.first { it.first.contains(x, y) }.second
                val person = people.firstOrNull { it.id == active.id } ?: return
                if (kind == PersonalStuff.Kind.ALARMS) services.navigator.show("alarms")
                else manager.upsert(PersonalStuff.cycle(person, kind))
            }
            else -> {
                actions.firstOrNull { it.first.contains(x, y) }?.second?.let { person ->
                    if (person.enrolled) confirmForget(person)
                    else services.navigator.show("profiles-enrol", Bundle().apply { putString("profile", person.id) })
                    return
                }
                val hit = tiles.firstOrNull { it.first.contains(x, y) } ?: return
                val person = hit.second
                if (person == null) addPerson() else manager.switchTo(person.id, SwitchReason.MANUAL)
            }
        }
    }

    private fun confirmForget(person: Profile) {
        AlertDialog.Builder(context)
            .setTitle("Forget ${person.name}’s face?")
            .setMessage("Deletes the encrypted face template. ${person.name} stays as a profile you can pick by hand.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Forget") { _, _ -> manager.forgetFace(person.id) }
            .show()
    }

    private fun addPerson() {
        val input = EditText(context).apply { hint = "Name"; inputType = InputType.TYPE_TEXT_FLAG_CAP_WORDS; setSingleLine() }
        AlertDialog.Builder(context)
            .setTitle("Add person")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add") { _, _ ->
                val n = input.text.toString().trim().take(20)
                if (n.isEmpty()) return@setPositiveButton
                val used = people.map { it.color }.toSet()
                val color = PALETTE.firstOrNull { it !in used } ?: PALETTE[people.size % PALETTE.size]
                val id = n.lowercase().replace(Regex("[^a-z0-9]"), "").ifEmpty { "person" } + "-" + (System.currentTimeMillis() % 100000)
                manager.upsert(Profile(id, n, color, faceId = "digital"))
            }
            .show()
    }

    companion object {
        private val PALETTE = listOf(VesperColors.PINK, VesperColors.GREEN, VesperColors.BLUE, VesperColors.ORANGE, VesperColors.PURPLE, VesperColors.CYAN, VesperColors.YELLOW, VesperColors.RED)
    }
}
