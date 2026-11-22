package app.vesper.feature.phone.ancs

import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Apple Notification Center Service (ANCS) wire format, from Apple's "ANCS Specification" (v1.1).
 * Pure Kotlin (no Android classes) so it's covered by JVM tests.
 *
 * Roles: the iPhone is the Notification Provider (GATT server, hosts the service); the Echo is the
 * Notification Consumer (GATT client). All multi-byte integers are little-endian. Strings are
 * UTF-8, not NUL-terminated, except the AppIdentifier inside Get App Attributes.
 */
object Ancs {
    val SERVICE: UUID = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")
    val NOTIFICATION_SOURCE: UUID = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")
    val CONTROL_POINT: UUID = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9")
    val DATA_SOURCE: UUID = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB")
    /** Client Characteristic Configuration descriptor. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // EventID
    const val EVENT_ADDED = 0
    const val EVENT_MODIFIED = 1
    const val EVENT_REMOVED = 2

    // EventFlags (bit mask)
    const val FLAG_SILENT = 1 shl 0
    const val FLAG_IMPORTANT = 1 shl 1
    const val FLAG_PRE_EXISTING = 1 shl 2
    const val FLAG_POSITIVE_ACTION = 1 shl 3
    const val FLAG_NEGATIVE_ACTION = 1 shl 4

    // CommandID
    const val CMD_GET_NOTIFICATION_ATTRIBUTES = 0
    const val CMD_GET_APP_ATTRIBUTES = 1
    const val CMD_PERFORM_NOTIFICATION_ACTION = 2

    // NotificationAttributeID
    const val ATTR_APP_IDENTIFIER = 0
    const val ATTR_TITLE = 1
    const val ATTR_SUBTITLE = 2
    const val ATTR_MESSAGE = 3
    const val ATTR_MESSAGE_SIZE = 4
    const val ATTR_DATE = 5
    const val ATTR_POSITIVE_ACTION_LABEL = 6
    const val ATTR_NEGATIVE_ACTION_LABEL = 7

    // AppAttributeID
    const val APP_ATTR_DISPLAY_NAME = 0

    // ActionID
    const val ACTION_POSITIVE = 0
    const val ACTION_NEGATIVE = 1

    // Control Point ATT error codes
    const val ERROR_UNKNOWN_COMMAND = 0xA0
    const val ERROR_INVALID_COMMAND = 0xA1
    const val ERROR_INVALID_PARAMETER = 0xA2
    const val ERROR_ACTION_FAILED = 0xA3

    /** Attributes that take a 2-byte max length in a Get Notification Attributes command. */
    fun needsMaxLength(attr: Int) = attr == ATTR_TITLE || attr == ATTR_SUBTITLE || attr == ATTR_MESSAGE
}

enum class AncsCategory(val id: Int) {
    OTHER(0), INCOMING_CALL(1), MISSED_CALL(2), VOICEMAIL(3), SOCIAL(4), SCHEDULE(5), EMAIL(6),
    NEWS(7), HEALTH_AND_FITNESS(8), BUSINESS_AND_FINANCE(9), LOCATION(10), ENTERTAINMENT(11);

    companion object {
        fun of(id: Int) = entries.firstOrNull { it.id == id } ?: OTHER
    }
}

/** One 8-byte Notification Source packet. */
data class NotificationSourceEvent(
    val eventId: Int,
    val flags: Int,
    val category: AncsCategory,
    val categoryCount: Int,
    /** uint32 as a Long so it compares cleanly. */
    val uid: Long,
) {
    val silent get() = flags and Ancs.FLAG_SILENT != 0
    val important get() = flags and Ancs.FLAG_IMPORTANT != 0
    val preExisting get() = flags and Ancs.FLAG_PRE_EXISTING != 0
    val hasPositiveAction get() = flags and Ancs.FLAG_POSITIVE_ACTION != 0
    val hasNegativeAction get() = flags and Ancs.FLAG_NEGATIVE_ACTION != 0

    companion object {
        /** Null when the packet is malformed (shorter than 8 bytes or an unknown EventID). */
        fun parse(b: ByteArray): NotificationSourceEvent? {
            if (b.size < 8) return null
            val event = b[0].u8()
            if (event > Ancs.EVENT_REMOVED) return null
            return NotificationSourceEvent(event, b[1].u8(), AncsCategory.of(b[2].u8()), b[3].u8(), b.u32(4))
        }
    }

    fun encode(): ByteArray = byteArrayOf(eventId.toByte(), flags.toByte(), category.id.toByte(), categoryCount.toByte()) + uid.le32()
}

/** A request written to the Control Point. */
sealed interface AncsCommand {
    fun encode(): ByteArray

    /**
     * Get Notification Attributes. [attributes] maps AttributeID → max length (used only for
     * Title, Subtitle and Message; ignored for the rest).
     */
    data class GetNotificationAttributes(val uid: Long, val attributes: List<Pair<Int, Int>>) : AncsCommand {
        val attributeIds: List<Int> get() = attributes.map { it.first }
        override fun encode(): ByteArray {
            val o = ByteArrayOutputStream()
            o.write(Ancs.CMD_GET_NOTIFICATION_ATTRIBUTES)
            o.write(uid.le32())
            for ((id, max) in attributes) {
                o.write(id)
                if (Ancs.needsMaxLength(id)) { o.write(max and 0xFF); o.write((max shr 8) and 0xFF) }
            }
            return o.toByteArray()
        }
    }

    data class GetAppAttributes(val appId: String, val attributeIds: List<Int> = listOf(Ancs.APP_ATTR_DISPLAY_NAME)) : AncsCommand {
        override fun encode(): ByteArray {
            val o = ByteArrayOutputStream()
            o.write(Ancs.CMD_GET_APP_ATTRIBUTES)
            o.write(appId.toByteArray(Charsets.UTF_8))
            o.write(0)
            attributeIds.forEach { o.write(it) }
            return o.toByteArray()
        }
    }

    data class PerformNotificationAction(val uid: Long, val actionId: Int) : AncsCommand {
        override fun encode(): ByteArray = byteArrayOf(Ancs.CMD_PERFORM_NOTIFICATION_ACTION.toByte()) + uid.le32() + byteArrayOf(actionId.toByte())
    }

    /** Commands that produce a Data Source response. */
    val expectsResponse: Boolean get() = this !is PerformNotificationAction

    companion object {
        /** What the Echo asks for every new notification. */
        fun notificationAttributes(uid: Long) = GetNotificationAttributes(uid, listOf(
            Ancs.ATTR_APP_IDENTIFIER to 0,
            Ancs.ATTR_TITLE to 64,
            Ancs.ATTR_SUBTITLE to 64,
            Ancs.ATTR_MESSAGE to 240,
            Ancs.ATTR_DATE to 0,
            Ancs.ATTR_POSITIVE_ACTION_LABEL to 0,
            Ancs.ATTR_NEGATIVE_ACTION_LABEL to 0,
        ))
    }
}

/** A complete Data Source response. */
sealed interface AncsResponse {
    data class NotificationAttributes(val uid: Long, val attributes: Map<Int, String>) : AncsResponse
    data class AppAttributes(val appId: String, val attributes: Map<Int, String>) : AncsResponse
}

internal fun Byte.u8() = toInt() and 0xFF
internal fun ByteArray.u16(i: Int) = this[i].u8() or (this[i + 1].u8() shl 8)
internal fun ByteArray.u32(i: Int): Long =
    (this[i].u8().toLong()) or (this[i + 1].u8().toLong() shl 8) or (this[i + 2].u8().toLong() shl 16) or (this[i + 3].u8().toLong() shl 24)
internal fun Long.le32() = byteArrayOf((this and 0xFF).toByte(), ((this shr 8) and 0xFF).toByte(), ((this shr 16) and 0xFF).toByte(), ((this shr 24) and 0xFF).toByte())
