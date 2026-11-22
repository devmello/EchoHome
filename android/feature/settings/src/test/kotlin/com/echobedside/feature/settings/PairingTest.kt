package com.echobedside.feature.settings

import com.echobedside.feature.settings.pair.InMemoryPairStore
import com.echobedside.feature.settings.pair.PairResult
import com.echobedside.feature.settings.pair.Pairing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {
    private val clock = longArrayOf(1_000_000L)
    private val store = InMemoryPairStore()
    private val p = Pairing(store, { clock[0] }, { 1_795_330_000_000L })

    private fun start(): String = (p.start("Safari on iPhone") as PairResult.Started).pairId
    private fun wrong(code: String) = (0..999_999).map { it.toString().padStart(6, '0') }.first { it != code }

    @Test fun happyPath() {
        val id = start()
        val prompt = p.prompt.value!!
        assertEquals(6, prompt.code.length)
        assertTrue(prompt.code.all { it.isDigit() })
        val r = p.submit(id, prompt.code, "Safari on iPhone") as PairResult.Paired
        assertTrue(r.token.length >= 43) // 256 bits, base64url
        assertTrue(p.prompt.value!!.paired)
        assertEquals(r.browser, p.authenticate(r.token))
        assertNull(p.authenticate(r.token + "x"))
        assertNull(p.authenticate(null))
        assertEquals(1, store.list.size)
    }

    @Test fun tokenIsStoredHashedOnly() {
        val id = start()
        val r = p.submit(id, p.prompt.value!!.code, "x") as PairResult.Paired
        val stored = store.list.single()
        assertNotEquals(r.token, stored.tokenHash)
        assertEquals(Pairing.hash(r.token), stored.tokenHash)
        assertEquals(64, stored.tokenHash.length)
        assertTrue(store.list.none { r.token in it.toString() })
        // A fresh Pairing on the same store still knows the browser (persisted).
        assertNotNull(Pairing(store, { 0L }).authenticate(r.token))
    }

    @Test fun sha256KnownVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Pairing.hash("abc"))
    }

    @Test fun wrongCodesCountDownThenKillTheCode() {
        val id = start()
        val code = p.prompt.value!!.code
        repeat(4) { i ->
            val r = p.submit(id, wrong(code), "x") as PairResult.Failed
            assertEquals(403, r.status)
            assertEquals(4 - i, r.attemptsLeft)
        }
        val last = p.submit(id, wrong(code), "x") as PairResult.Failed
        assertEquals(429, last.status)
        assertNull(p.prompt.value)
        // Even the right code is useless now.
        assertEquals(410, (p.submit(id, code, "x") as PairResult.Failed).status)
    }

    @Test fun newCodesAreRateLimited() {
        start()
        assertEquals(429, (p.start("x") as PairResult.Failed).status)
        clock[0] += Pairing.START_INTERVAL_MS
        assertTrue(p.start("x") is PairResult.Started)
    }

    @Test fun manyFailuresLockPairing() {
        var failures = 0
        while (failures < Pairing.MAX_FAILURES) {
            clock[0] += Pairing.START_INTERVAL_MS
            val id = start()
            val code = p.prompt.value!!.code
            repeat(minOf(Pairing.ATTEMPTS_PER_CODE, Pairing.MAX_FAILURES - failures)) { p.submit(id, wrong(code), "x"); failures++ }
        }
        clock[0] += Pairing.START_INTERVAL_MS
        val r = p.start("x") as PairResult.Failed
        assertEquals(429, r.status)
        assertTrue(r.message, "Try again" in r.message)
        clock[0] += Pairing.LOCKOUT_MS
        assertTrue(p.start("x") is PairResult.Started)
    }

    @Test fun codesExpire() {
        val id = start()
        val code = p.prompt.value!!.code
        clock[0] += Pairing.CODE_TTL_MS
        assertEquals(410, (p.submit(id, code, "x") as PairResult.Failed).status)
        assertNull(p.prompt.value)
    }

    @Test fun anotherPairIdCantUseTheCode() {
        start()
        val code = p.prompt.value!!.code
        assertEquals(410, (p.submit("not-the-id", code, "x") as PairResult.Failed).status)
    }

    @Test fun expireOnlyDropsExpiredCodes() {
        start()
        p.expire()
        assertNotNull(p.prompt.value)
        clock[0] += Pairing.CODE_TTL_MS
        p.expire()
        assertNull(p.prompt.value)
    }

    @Test fun forget() {
        val id = start()
        val r = p.submit(id, p.prompt.value!!.code, "x") as PairResult.Paired
        assertTrue(p.forget(r.browser.id))
        assertNull(p.authenticate(r.token))
        assertTrue(store.list.isEmpty())
    }

    @Test fun browserNames() {
        assertEquals("Safari on iPhone", Pairing.browserName("Mozilla/5.0 (iPhone; CPU iPhone OS 26_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.1 Mobile/15E148 Safari/604.1"))
        assertEquals("Safari on Mac", Pairing.browserName("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.1 Safari/605.1.15"))
        assertEquals("Chrome on Mac", Pairing.browserName("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"))
        assertEquals("Safari on iPhone", Pairing.browserName("Mozilla/5.0 (iPhone; CPU iPhone OS 26_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"))
        assertEquals("curl", Pairing.browserName("curl/8.7.1"))
        assertEquals("A browser", Pairing.browserName(null))
    }
}
