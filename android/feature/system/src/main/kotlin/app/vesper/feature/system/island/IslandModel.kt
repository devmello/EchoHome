package app.vesper.feature.system.island

import app.vesper.core.model.ActivityKind
import app.vesper.core.model.LiveActivity

/** Island geometry at 960×480 (Island.dc.html, NOTES §5.4). The island is centred, 12 px from the top. */
data class IslandBox(val width: Float, val height: Float, val radius: Float) {
    companion object {
        val HIDDEN = IslandBox(120f, 44f, 22f)
    }
}

enum class IslandMode { HIDDEN, COMPACT, EXPANDED }

/** What the renderer should show now. */
data class IslandLayout(
    val mode: IslandMode,
    /** The activity in the island (expanded one when EXPANDED). */
    val main: LiveActivity?,
    /** Second activity as the 44 px bead 8 px to the right; never while expanded. */
    val bead: LiveActivity?,
    val box: IslandBox,
) {
    /** Left edge of the bead in screen px: 480 + w/2 + 8. */
    val beadX: Float get() = 480f + box.width / 2f + 8f
}

/**
 * The island's state machine (pure; unit-tested):
 *  - input: [IslandManagerVisible] (≤ 2, priority order, already filtered for stale/owner screen),
 *  - tap compact → expand it; tap the bead → expand that one; tap outside → collapse,
 *  - alerts (doorbell, call, alarm, timer done) expand on their own the first time they appear;
 *    once collapsed by the user they stay compact,
 *  - if the expanded activity goes away, collapse.
 */
class IslandModel {
    private var visible: List<LiveActivity> = emptyList()
    private var expandedId: String? = null
    private val autoExpanded = HashSet<String>()

    var layout: IslandLayout = IslandLayout(IslandMode.HIDDEN, null, null, IslandBox.HIDDEN); private set

    /** New list from IslandManager.visible(). Returns the new layout. */
    fun update(list: List<LiveActivity>): IslandLayout {
        visible = list.take(2)
        val ids = visible.map { it.id }.toSet()
        autoExpanded.retainAll(ids)
        if (expandedId != null && expandedId !in ids) expandedId = null
        // a new alert expands on its own (highest priority first)
        visible.firstOrNull { it.alert && it.id !in autoExpanded }?.let { a ->
            autoExpanded += a.id
            if (expandedId == null || priorityOf(expandedId) <= a.priority) expandedId = a.id
        }
        return relayout()
    }

    fun tapMain(): IslandLayout {
        val m = layout.main ?: return layout
        if (layout.mode == IslandMode.COMPACT && canExpand(m)) expandedId = m.id
        return relayout()
    }

    fun tapBead(): IslandLayout {
        val b = layout.bead ?: return layout
        expandedId = if (canExpand(b)) b.id else expandedId
        return relayout()
    }

    fun collapse(): IslandLayout {
        expandedId = null
        return relayout()
    }

    private fun priorityOf(id: String?) = visible.firstOrNull { it.id == id }?.priority ?: Int.MIN_VALUE

    private fun relayout(): IslandLayout {
        val main = visible.firstOrNull()
        val exp = visible.firstOrNull { it.id == expandedId }
        layout = when {
            main == null -> IslandLayout(IslandMode.HIDDEN, null, null, layout.box)
            exp != null -> IslandLayout(IslandMode.EXPANDED, exp, null, expandedBox(exp.kind))
            else -> IslandLayout(IslandMode.COMPACT, main, visible.getOrNull(1), compactBox(main.kind))
        }
        return layout
    }

    companion object {
        /** Assistant and "AirPlay / Bluetooth connected" have no expanded form in the design. */
        fun canExpand(a: LiveActivity) = a.kind != ActivityKind.ASSISTANT && a.kind != ActivityKind.AIRPLAY_CONNECTED &&
            a.kind != ActivityKind.BLUETOOTH_CONNECTED

        /** Compact, 44 tall: timer 230, music 250, assistant 220 (design); the rest sized like them. */
        fun compactBox(kind: ActivityKind) = when (kind) {
            ActivityKind.TIMER -> IslandBox(230f, 44f, 22f)
            ActivityKind.MUSIC -> IslandBox(250f, 44f, 22f)
            ActivityKind.ASSISTANT -> IslandBox(220f, 44f, 22f)
            ActivityKind.AIRPLAY_CONNECTED, ActivityKind.BLUETOOTH_CONNECTED -> IslandBox(250f, 44f, 22f)
            ActivityKind.TIMER_DONE, ActivityKind.ALARM_RINGING -> IslandBox(230f, 44f, 22f)
            ActivityKind.DOORBELL, ActivityKind.CALL -> IslandBox(250f, 44f, 22f)
        }

        /** Expanded: Now Playing 660×160 r48, timer 460×120 r44, doorbell 640×196 r50, call 580×116 r58. */
        fun expandedBox(kind: ActivityKind) = when (kind) {
            ActivityKind.MUSIC -> IslandBox(660f, 160f, 48f)
            ActivityKind.TIMER, ActivityKind.TIMER_DONE, ActivityKind.ALARM_RINGING -> IslandBox(460f, 120f, 44f)
            ActivityKind.DOORBELL -> IslandBox(640f, 196f, 50f)
            ActivityKind.CALL -> IslandBox(580f, 116f, 58f)
            ActivityKind.ASSISTANT, ActivityKind.AIRPLAY_CONNECTED, ActivityKind.BLUETOOTH_CONNECTED -> compactBox(kind)
        }
    }
}

/** Typealias for readability in docs. */
typealias IslandManagerVisible = List<LiveActivity>
