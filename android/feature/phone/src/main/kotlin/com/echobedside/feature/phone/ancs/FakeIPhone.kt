package com.echobedside.feature.phone.ancs

import java.io.ByteArrayOutputStream

/**
 * A pretend iPhone ANCS provider, byte-exact to Apple's spec: produces Notification Source
 * packets and answers Control Point commands with Data Source responses split at ATT_MTU − 3.
 * Used by the JVM tests and by the debug demo (`--es phone_demo …`) to drive the real
 * session/presenter path on the Echo without a phone.
 */
class FakeIPhone(var mtu: Int = 23) {
    data class Note(
        val uid: Long,
        val category: AncsCategory,
        val appId: String,
        val title: String,
        val subtitle: String = "",
        val message: String = "",
        val date: String = "20261122T071500",
        val positive: String = "",
        val negative: String = "Clear",
        val extraFlags: Int = 0,
    ) {
        val flags: Int get() = extraFlags or (if (positive.isNotEmpty()) Ancs.FLAG_POSITIVE_ACTION else 0) or (if (negative.isNotEmpty()) Ancs.FLAG_NEGATIVE_ACTION else 0)
    }

    val apps = mutableMapOf(
        "com.apple.MobileSMS" to "Messages",
        "com.apple.mobilephone" to "Phone",
        "com.apple.mobilemail" to "Mail",
        "net.whatsapp.WhatsApp" to "WhatsApp",
    )
    val notes = LinkedHashMap<Long, Note>()
    /** (uid, actionId) for every Perform Notification Action received. */
    val actions = mutableListOf<Pair<Long, Int>>()
    val commands = mutableListOf<ByteArray>()

    fun add(n: Note, preExisting: Boolean = false): ByteArray {
        notes[n.uid] = n
        val count = notes.values.count { it.category == n.category }
        return NotificationSourceEvent(Ancs.EVENT_ADDED, n.flags or (if (preExisting) Ancs.FLAG_PRE_EXISTING else 0), n.category, count, n.uid).encode()
    }

    fun modify(n: Note): ByteArray {
        notes[n.uid] = n
        return NotificationSourceEvent(Ancs.EVENT_MODIFIED, n.flags, n.category, notes.values.count { it.category == n.category }, n.uid).encode()
    }

    fun remove(uid: Long): ByteArray {
        val n = notes.remove(uid)
        val cat = n?.category ?: AncsCategory.OTHER
        return NotificationSourceEvent(Ancs.EVENT_REMOVED, n?.flags ?: 0, cat, notes.values.count { it.category == cat }, uid).encode()
    }

    /**
     * Handles one Control Point write. Returns the ATT status (0 or an ANCS error code) and the
     * Data Source notifications to send back, already fragmented.
     */
    fun write(cp: ByteArray): Pair<Int, List<ByteArray>> {
        commands += cp
        if (cp.isEmpty()) return Ancs.ERROR_INVALID_COMMAND to emptyList()
        return when (cp[0].u8()) {
            Ancs.CMD_GET_NOTIFICATION_ATTRIBUTES -> {
                if (cp.size < 5) return Ancs.ERROR_INVALID_COMMAND to emptyList()
                val uid = cp.u32(1)
                val n = notes[uid] ?: return Ancs.ERROR_INVALID_PARAMETER to emptyList()
                val o = ByteArrayOutputStream()
                o.write(Ancs.CMD_GET_NOTIFICATION_ATTRIBUTES); o.write(uid.le32())
                var i = 5
                while (i < cp.size) {
                    val id = cp[i].u8(); i++
                    var max = Int.MAX_VALUE
                    if (Ancs.needsMaxLength(id)) { if (i + 2 > cp.size) return Ancs.ERROR_INVALID_COMMAND to emptyList(); max = cp.u16(i); i += 2 }
                    val v = when (id) {
                        Ancs.ATTR_APP_IDENTIFIER -> n.appId
                        Ancs.ATTR_TITLE -> n.title
                        Ancs.ATTR_SUBTITLE -> n.subtitle
                        Ancs.ATTR_MESSAGE -> n.message
                        Ancs.ATTR_MESSAGE_SIZE -> n.message.toByteArray().size.toString()
                        Ancs.ATTR_DATE -> n.date
                        Ancs.ATTR_POSITIVE_ACTION_LABEL -> n.positive
                        Ancs.ATTR_NEGATIVE_ACTION_LABEL -> n.negative
                        else -> return Ancs.ERROR_INVALID_PARAMETER to emptyList()
                    }
                    writeAttr(o, id, truncate(v, max))
                }
                0 to fragment(o.toByteArray())
            }
            Ancs.CMD_GET_APP_ATTRIBUTES -> {
                var nul = -1
                for (k in 1 until cp.size) if (cp[k].toInt() == 0) { nul = k; break }
                if (nul < 0) return Ancs.ERROR_INVALID_COMMAND to emptyList()
                val appId = String(cp, 1, nul - 1, Charsets.UTF_8)
                val o = ByteArrayOutputStream()
                o.write(cp, 0, nul + 1)
                for (k in nul + 1 until cp.size) {
                    if (cp[k].u8() != Ancs.APP_ATTR_DISPLAY_NAME) return Ancs.ERROR_INVALID_PARAMETER to emptyList()
                    writeAttr(o, Ancs.APP_ATTR_DISPLAY_NAME, (apps[appId] ?: "").toByteArray())
                }
                0 to fragment(o.toByteArray())
            }
            Ancs.CMD_PERFORM_NOTIFICATION_ACTION -> {
                if (cp.size < 6) return Ancs.ERROR_INVALID_COMMAND to emptyList()
                val uid = cp.u32(1)
                if (!notes.containsKey(uid)) return Ancs.ERROR_INVALID_PARAMETER to emptyList()
                actions += uid to cp[5].u8()
                0 to emptyList()
            }
            else -> Ancs.ERROR_UNKNOWN_COMMAND to emptyList()
        }
    }

    private fun writeAttr(o: ByteArrayOutputStream, id: Int, v: ByteArray) {
        o.write(id); o.write(v.size and 0xFF); o.write((v.size shr 8) and 0xFF); o.write(v)
    }

    /** UTF-8 bytes cut to [max] without splitting a character (iOS truncates whole characters). */
    private fun truncate(s: String, max: Int): ByteArray {
        val b = s.toByteArray(Charsets.UTF_8)
        if (b.size <= max) return b
        var end = max
        while (end > 0 && (b[end].toInt() and 0xC0) == 0x80) end--
        return b.copyOf(end)
    }

    fun fragment(b: ByteArray): List<ByteArray> {
        val size = (mtu - 3).coerceAtLeast(1)
        return (b.indices step size).map { b.copyOfRange(it, minOf(b.size, it + size)) }
    }
}
