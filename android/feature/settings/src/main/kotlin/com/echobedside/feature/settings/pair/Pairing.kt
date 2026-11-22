package com.echobedside.feature.settings.pair

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** A browser that paired. Only the SHA-256 of its token is kept. */
data class PairedBrowser(
    val id: String,
    val tokenHash: String,
    val name: String,
    val createdAt: Long,
    val lastSeenAt: Long,
)

/** Where paired browsers persist (SharedPreferences on the Echo, memory in tests). */
interface PairStore {
    fun load(): List<PairedBrowser>
    fun save(list: List<PairedBrowser>)
}

class InMemoryPairStore : PairStore {
    var list: List<PairedBrowser> = emptyList()
    override fun load() = list
    override fun save(list: List<PairedBrowser>) { this.list = list }
}

/** What the Echo shows while a browser pairs. */
data class PairPrompt(
    val pairId: String,
    val code: String,
    val browser: String,
    /** [Pairing.now] time the code stops working. */
    val expiresAt: Long,
    val paired: Boolean = false,
)

sealed class PairResult {
    data class Started(val pairId: String, val expiresInMs: Long) : PairResult()
    data class Paired(val token: String, val browser: PairedBrowser) : PairResult()
    data class Failed(val status: Int, val message: String, val attemptsLeft: Int? = null) : PairResult()
}

/**
 * Pairing with a 6-digit code shown on the Echo (2 min), then a long random token in an HttpOnly
 * cookie, stored hashed.
 *
 * Rate limits: a new code at most every [START_INTERVAL_MS]; [ATTEMPTS_PER_CODE] wrong tries kill
 * the code (the Echo must show a new one); [MAX_FAILURES] wrong tries within [FAILURE_WINDOW_MS]
 * lock pairing for [LOCKOUT_MS]. An attacker on the LAN gets about 5 guesses per code the user
 * can see appear, out of 1,000,000.
 */
class Pairing(
    private val store: PairStore,
    /** Monotonic ms (SystemClock.elapsedRealtime on the Echo). */
    private val now: () -> Long,
    /** Wall-clock ms, for "paired on" dates. */
    private val wallNow: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private val _prompt = MutableStateFlow<PairPrompt?>(null)
    /** Non-null while a code is showing (or, briefly, the "Paired" confirmation). */
    val prompt: StateFlow<PairPrompt?> = _prompt.asStateFlow()

    private val _browsers = MutableStateFlow(store.load())
    val browsers: StateFlow<List<PairedBrowser>> = _browsers.asStateFlow()

    private var attempts = 0
    private var lastStart = Long.MIN_VALUE / 2
    private val failures = ArrayDeque<Long>()
    private var lockedUntil = Long.MIN_VALUE / 2

    @Synchronized
    fun start(browser: String): PairResult {
        val t = now()
        if (t < lockedUntil) return PairResult.Failed(429, "Too many wrong codes. Try again in ${minutesLeft(t)} min.")
        if (t - lastStart < START_INTERVAL_MS) return PairResult.Failed(429, "Wait a few seconds, then tap Pair again.")
        lastStart = t
        attempts = 0
        val code = (random.nextInt(1_000_000)).toString().padStart(6, '0')
        val p = PairPrompt(randomHex(16), code, browser.take(60), t + CODE_TTL_MS)
        _prompt.value = p
        return PairResult.Started(p.pairId, CODE_TTL_MS)
    }

    @Synchronized
    fun submit(pairId: String, code: String, browser: String): PairResult {
        val t = now()
        if (t < lockedUntil) return PairResult.Failed(429, "Too many wrong codes. Try again in ${minutesLeft(t)} min.")
        val p = _prompt.value
        if (p == null || p.paired || p.pairId != pairId) return PairResult.Failed(410, "This code isn't active. Tap Pair again.")
        if (t >= p.expiresAt) { _prompt.value = null; return PairResult.Failed(410, "The code expired. Tap Pair again.") }
        val ok = MessageDigest.isEqual(code.trim().toByteArray(), p.code.toByteArray())
        if (!ok) {
            attempts++
            failures.addLast(t)
            while (failures.isNotEmpty() && t - failures.first() > FAILURE_WINDOW_MS) failures.removeFirst()
            if (failures.size >= MAX_FAILURES) {
                lockedUntil = t + LOCKOUT_MS
                failures.clear()
                _prompt.value = null
                return PairResult.Failed(429, "Too many wrong codes. Pairing is off for ${LOCKOUT_MS / 60_000} min.")
            }
            if (attempts >= ATTEMPTS_PER_CODE) {
                _prompt.value = null
                return PairResult.Failed(429, "Too many wrong tries. Tap Pair for a new code.", 0)
            }
            return PairResult.Failed(403, "That's not the code on the Echo.", ATTEMPTS_PER_CODE - attempts)
        }
        val token = randomToken()
        val wall = wallNow()
        val b = PairedBrowser(randomHex(4), hash(token), browser.take(60), wall, wall)
        val list = _browsers.value.let { if (it.size >= MAX_BROWSERS) it.sortedBy { x -> x.lastSeenAt }.drop(1) else it } + b
        store.save(list)
        _browsers.value = list
        _prompt.value = p.copy(paired = true)
        return PairResult.Paired(token, b)
    }

    /** The paired browser for a cookie token, or null. */
    fun authenticate(token: String?): PairedBrowser? {
        if (token.isNullOrEmpty() || token.length > 200) return null
        val h = hash(token).toByteArray()
        val b = _browsers.value.firstOrNull { MessageDigest.isEqual(it.tokenHash.toByteArray(), h) } ?: return null
        val wall = wallNow()
        if (wall - b.lastSeenAt > LAST_SEEN_PERSIST_MS) touch(b.id, wall)
        return b
    }

    @Synchronized
    private fun touch(id: String, wall: Long) {
        val list = _browsers.value.map { if (it.id == id) it.copy(lastSeenAt = wall) else it }
        store.save(list)
        _browsers.value = list
    }

    @Synchronized
    fun forget(id: String): Boolean {
        val list = _browsers.value
        if (list.none { it.id == id }) return false
        val next = list.filterNot { it.id == id }
        store.save(next)
        _browsers.value = next
        return true
    }

    /** The user dismissed the code on the Echo, or the "Paired" confirmation finished. */
    @Synchronized
    fun dismissPrompt() { _prompt.value = null }

    /** Drops an expired code (the Echo's sheet calls this when its countdown ends). */
    @Synchronized
    fun expire() { _prompt.value?.let { if (!it.paired && now() >= it.expiresAt) _prompt.value = null } }

    private fun minutesLeft(t: Long) = ((lockedUntil - t + 59_999) / 60_000).coerceAtLeast(1)

    private fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes).also(random::nextBytes)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun randomToken(): String {
        val b = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    companion object {
        const val CODE_TTL_MS = 120_000L
        const val START_INTERVAL_MS = 3_000L
        const val ATTEMPTS_PER_CODE = 5
        const val MAX_FAILURES = 15
        const val FAILURE_WINDOW_MS = 10 * 60_000L
        const val LOCKOUT_MS = 10 * 60_000L
        const val MAX_BROWSERS = 20
        const val LAST_SEEN_PERSIST_MS = 6 * 3600_000L
        const val COOKIE = "vesper_session"

        /** SHA-256 hex. Tokens are 256 random bits, so no salt or stretching is needed. */
        fun hash(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

        /** "Safari on iPhone" from a User-Agent. */
        fun browserName(ua: String?): String {
            if (ua.isNullOrBlank()) return "A browser"
            val device = when {
                "iPhone" in ua -> "iPhone"; "iPad" in ua -> "iPad"
                "Android" in ua -> "Android"; "Macintosh" in ua || "Mac OS X" in ua -> "Mac"
                "Windows" in ua -> "Windows PC"; "CrOS" in ua -> "Chromebook"; "Linux" in ua -> "Linux"
                else -> null
            }
            val app = when {
                "Edg/" in ua -> "Edge"; "Firefox/" in ua || "FxiOS" in ua -> "Firefox"
                "CriOS" in ua || ("Chrome/" in ua && "Chromium" !in ua) -> "Chrome"
                "Safari/" in ua || ("AppleWebKit" in ua && (device == "iPhone" || device == "iPad")) -> "Safari"
                "curl/" in ua -> "curl"
                else -> "Browser"
            }
            return if (device != null) "$app on $device" else app
        }
    }
}
