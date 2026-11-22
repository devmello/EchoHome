package com.echobedside.feature.phone.ancs

import java.io.ByteArrayOutputStream

/**
 * Reassembles one Data Source response from GATT notifications. A response longer than
 * ATT_MTU − 3 arrives split across several notifications with no framing of its own, so the
 * only way to know it's complete is to parse it against the request: it's done once every
 * requested attribute (id, uint16 length, value) has been read.
 *
 * The session keeps one request outstanding at a time, so [expect] is called before each write
 * and [feed] returns the response once it's whole.
 */
class DataSourceAssembler {
    private val buf = ByteArrayOutputStream()
    private var pending: AncsCommand? = null

    /** The request whose response is expected next (null = none; stray packets are dropped). */
    fun expect(command: AncsCommand?) {
        pending = command?.takeIf { it.expectsResponse }
        buf.reset()
    }

    val waiting: Boolean get() = pending != null

    sealed interface Result {
        /** Need more fragments. */
        data object Partial : Result
        data class Complete(val response: AncsResponse) : Result
        /** The bytes don't belong to the pending request (or nothing was pending); dropped. */
        data class Dropped(val reason: String) : Result
    }

    fun feed(chunk: ByteArray): Result {
        val p = pending ?: return Result.Dropped("no request pending")
        buf.write(chunk)
        val b = buf.toByteArray()
        return when (val r = parse(p, b)) {
            is Result.Complete, is Result.Dropped -> { pending = null; buf.reset(); r }
            Result.Partial -> r
        }
    }

    companion object {
        /** Parses [b] as the response to [p]: Partial if it's a valid prefix. */
        fun parse(p: AncsCommand, b: ByteArray): Result {
            if (b.isEmpty()) return Result.Partial
            return when (p) {
                is AncsCommand.GetNotificationAttributes -> {
                    if (b[0].u8() != Ancs.CMD_GET_NOTIFICATION_ATTRIBUTES) return Result.Dropped("command ${b[0].u8()}")
                    if (b.size < 5) return Result.Partial
                    val uid = b.u32(1)
                    if (uid != p.uid) return Result.Dropped("uid $uid != ${p.uid}")
                    val attrs = readAttributes(b, 5, p.attributes.size) ?: return Result.Partial
                    Result.Complete(AncsResponse.NotificationAttributes(uid, attrs))
                }
                is AncsCommand.GetAppAttributes -> {
                    if (b[0].u8() != Ancs.CMD_GET_APP_ATTRIBUTES) return Result.Dropped("command ${b[0].u8()}")
                    var nul = -1
                    for (i in 1 until b.size) if (b[i].toInt() == 0) { nul = i; break }
                    if (nul < 0) return Result.Partial
                    val appId = String(b, 1, nul - 1, Charsets.UTF_8)
                    if (appId != p.appId) return Result.Dropped("app $appId != ${p.appId}")
                    val attrs = readAttributes(b, nul + 1, p.attributeIds.size) ?: return Result.Partial
                    Result.Complete(AncsResponse.AppAttributes(appId, attrs))
                }
                is AncsCommand.PerformNotificationAction -> Result.Dropped("no response expected")
            }
        }

        /** Reads [count] (id, len16, value) tuples from [start]; null if more bytes are needed. */
        private fun readAttributes(b: ByteArray, start: Int, count: Int): Map<Int, String>? {
            val out = LinkedHashMap<Int, String>()
            var i = start
            repeat(count) {
                if (i + 3 > b.size) return null
                val id = b[i].u8()
                val len = b.u16(i + 1)
                i += 3
                if (i + len > b.size) return null
                out[id] = String(b, i, len, Charsets.UTF_8)
                i += len
            }
            return out
        }
    }
}
