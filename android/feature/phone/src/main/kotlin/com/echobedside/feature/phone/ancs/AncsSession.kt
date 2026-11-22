package com.echobedside.feature.phone.ancs

/** One iPhone notification with its attributes, as the Echo knows it. */
data class AncsNotification(
    val uid: Long,
    val category: AncsCategory,
    val flags: Int,
    val appId: String = "",
    /** From Get App Attributes (DisplayName); null if the phone didn't say. */
    val appName: String? = null,
    val title: String = "",
    val subtitle: String = "",
    val message: String = "",
    /** "yyyyMMdd'T'HHmmSS" in the phone's local time, as ANCS sends it. */
    val date: String = "",
    val positiveLabel: String = "",
    val negativeLabel: String = "",
) {
    val silent get() = flags and Ancs.FLAG_SILENT != 0
    val important get() = flags and Ancs.FLAG_IMPORTANT != 0
    val preExisting get() = flags and Ancs.FLAG_PRE_EXISTING != 0
    val hasPositiveAction get() = flags and Ancs.FLAG_POSITIVE_ACTION != 0
    val hasNegativeAction get() = flags and Ancs.FLAG_NEGATIVE_ACTION != 0
}

/**
 * The ANCS consumer state machine (pure, single-threaded: call everything from one thread).
 *
 * Notification Source events come in; it asks the phone for each new notification's attributes
 * (and the app's display name the first time it sees an app), one Control Point request at a
 * time, reassembles Data Source responses and reports the result to [listener].
 *
 * Event flags:
 *  - PreExisting (sent for everything already on the phone when we subscribe, e.g. on every
 *    reconnect): tracked but not fetched and reported as [Change.KNOWN], so they don't banner,
 *    except incoming calls, which are still ringing and are fetched and reported as [Change.POSTED].
 *  - Silent / Important / Positive / Negative action: passed through on [AncsNotification.flags].
 */
class AncsSession(private val io: Io, private val listener: Listener) {

    interface Io {
        /** Write [bytes] to the Control Point (with response); answer with [onWriteResult]. */
        fun writeControlPoint(bytes: ByteArray)
        /** Call [onTimeout] after [ms] unless [disarmTimeout] comes first. */
        fun armTimeout(ms: Long)
        fun disarmTimeout()
    }

    enum class Change { POSTED, MODIFIED, KNOWN, REMOVED }

    fun interface Listener {
        fun onNotification(n: AncsNotification, change: Change)
    }

    /** Every notification the phone has that we know about, by uid. */
    private val known = LinkedHashMap<Long, AncsNotification>()
    /** uids whose attributes have been fetched (or are in flight / queued). */
    private val fetching = HashSet<Long>()
    /** Display names by bundle id; survives reconnects (seed it from storage with [rememberAppName]). */
    private val appNames = HashMap<String, String>()
    /** Notifications waiting for their app's name. */
    private val waitingForApp = LinkedHashMap<String, MutableList<Pair<AncsNotification, Change>>>()

    private val queue = ArrayDeque<AncsCommand>()
    private var inFlight: AncsCommand? = null
    private var acked = false
    private var responseDone = false
    private val assembler = DataSourceAssembler()

    val notifications: Collection<AncsNotification> get() = known.values
    val appNameCache: Map<String, String> get() = appNames
    val idle: Boolean get() = inFlight == null && queue.isEmpty()

    fun rememberAppName(appId: String, name: String) { if (name.isNotBlank()) appNames[appId] = name }

    // --- inputs ---

    fun onNotificationSource(bytes: ByteArray) {
        val e = NotificationSourceEvent.parse(bytes) ?: return
        when (e.eventId) {
            Ancs.EVENT_ADDED, Ancs.EVENT_MODIFIED -> {
                val prev = known[e.uid]
                val base = (prev ?: AncsNotification(e.uid, e.category, e.flags)).copy(category = e.category, flags = e.flags)
                known[e.uid] = base
                val change = when {
                    e.eventId == Ancs.EVENT_MODIFIED || prev != null -> Change.MODIFIED
                    e.preExisting && e.category != AncsCategory.INCOMING_CALL -> Change.KNOWN
                    else -> Change.POSTED
                }
                if (change == Change.KNOWN) { listener.onNotification(base, Change.KNOWN); return }
                fetch(e.uid, change)
            }
            Ancs.EVENT_REMOVED -> {
                fetching.remove(e.uid)
                pendingChange.remove(e.uid)
                queue.removeAll { (it as? AncsCommand.GetNotificationAttributes)?.uid == e.uid || (it as? AncsCommand.PerformNotificationAction)?.uid == e.uid }
                waitingForApp.values.forEach { l -> l.removeAll { it.first.uid == e.uid } }
                val n = known.remove(e.uid) ?: AncsNotification(e.uid, e.category, e.flags)
                listener.onNotification(n, Change.REMOVED)
            }
        }
    }

    fun onDataSource(bytes: ByteArray) {
        when (val r = assembler.feed(bytes)) {
            DataSourceAssembler.Result.Partial -> {}
            is DataSourceAssembler.Result.Dropped -> {}
            is DataSourceAssembler.Result.Complete -> {
                responseDone = true
                handle(r.response)
                // The write's own ack can arrive after the response; GATT allows one write at a time.
                if (acked) finish()
            }
        }
    }

    /** Result of the last Control Point write (0 = success, else an ATT / ANCS error code). */
    fun onWriteResult(status: Int) {
        val c = inFlight ?: return
        if (status != 0) {
            // e.g. 0xA2 invalid parameter for a notification that just went away.
            if (c is AncsCommand.GetNotificationAttributes) { fetching.remove(c.uid); pendingChange.remove(c.uid) }
            if (c is AncsCommand.GetAppAttributes) flushApp(c.appId, null)
            assembler.expect(null)
            finish()
            return
        }
        acked = true
        if (!c.expectsResponse || responseDone) finish()
    }

    fun onTimeout() {
        val c = inFlight ?: return
        if (!responseDone) {
            if (c is AncsCommand.GetNotificationAttributes) { fetching.remove(c.uid); pendingChange.remove(c.uid) }
            if (c is AncsCommand.GetAppAttributes) flushApp(c.appId, null)
        }
        assembler.expect(null)
        finish()
    }

    private fun finish() {
        io.disarmTimeout()
        inFlight = null
        acked = false
        responseDone = false
        next()
    }

    /** Perform the positive (e.g. "Call Back") or negative ("Decline", "Clear") action on the phone. */
    fun performAction(uid: Long, actionId: Int) {
        if (!known.containsKey(uid)) return
        enqueue(AncsCommand.PerformNotificationAction(uid, actionId), front = true)
    }

    /** The link dropped: forget per-connection state (uids aren't stable across connections). */
    fun reset() {
        known.clear(); fetching.clear(); pendingChange.clear(); waitingForApp.clear(); queue.clear()
        inFlight = null
        assembler.expect(null)
        io.disarmTimeout()
    }

    // --- internals ---

    private val pendingChange = HashMap<Long, Change>()

    private fun fetch(uid: Long, change: Change) {
        // Keep the strongest change: POSTED wins over MODIFIED if both happen before the reply.
        pendingChange[uid] = if (pendingChange[uid] == Change.POSTED) Change.POSTED else change
        if (!fetching.add(uid)) return
        enqueue(AncsCommand.notificationAttributes(uid))
    }

    private fun enqueue(c: AncsCommand, front: Boolean = false) {
        if (front) queue.addFirst(c) else queue.addLast(c)
        next()
    }

    private fun next() {
        if (inFlight != null) return
        val c = queue.removeFirstOrNull() ?: return
        inFlight = c
        assembler.expect(c)
        io.armTimeout(RESPONSE_TIMEOUT_MS)
        io.writeControlPoint(c.encode())
    }

    private fun handle(r: AncsResponse) {
        when (r) {
            is AncsResponse.NotificationAttributes -> {
                fetching.remove(r.uid)
                val change = pendingChange.remove(r.uid) ?: Change.MODIFIED
                val base = known[r.uid] ?: return // removed meanwhile
                val a = r.attributes
                val n = base.copy(
                    appId = a[Ancs.ATTR_APP_IDENTIFIER] ?: base.appId,
                    title = a[Ancs.ATTR_TITLE] ?: "",
                    subtitle = a[Ancs.ATTR_SUBTITLE] ?: "",
                    message = a[Ancs.ATTR_MESSAGE] ?: "",
                    date = a[Ancs.ATTR_DATE] ?: "",
                    positiveLabel = a[Ancs.ATTR_POSITIVE_ACTION_LABEL] ?: "",
                    negativeLabel = a[Ancs.ATTR_NEGATIVE_ACTION_LABEL] ?: "",
                )
                known[r.uid] = n
                val name = appNames[n.appId]
                when {
                    n.appId.isEmpty() || name != null -> listener.onNotification(n.copy(appName = name), change)
                    else -> {
                        val waiting = waitingForApp.getOrPut(n.appId) { mutableListOf() }
                        waiting += n to change
                        if (waiting.size == 1) enqueue(AncsCommand.GetAppAttributes(n.appId), front = true)
                    }
                }
            }
            is AncsResponse.AppAttributes -> {
                val name = r.attributes[Ancs.APP_ATTR_DISPLAY_NAME]?.takeIf { it.isNotBlank() }
                if (name != null) appNames[r.appId] = name
                flushApp(r.appId, name)
            }
        }
    }

    private fun flushApp(appId: String, name: String?) {
        val list = waitingForApp.remove(appId) ?: return
        for ((n, change) in list) {
            val cur = known[n.uid] ?: continue
            known[n.uid] = cur.copy(appName = name)
            listener.onNotification(cur.copy(appName = name), change)
        }
    }

    companion object {
        const val RESPONSE_TIMEOUT_MS = 8_000L
    }
}
