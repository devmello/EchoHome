package app.vesper.feature.camera

import app.vesper.core.model.Presence
import app.vesper.core.model.Profile
import app.vesper.core.model.SettingsStore
import app.vesper.core.model.SwitchReason
import app.vesper.feature.camera.face.FaceTemplates
import app.vesper.feature.camera.profiles.FaceProfileManager
import app.vesper.feature.camera.profiles.SecretBox
import app.vesper.feature.camera.profiles.TemplateStore
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private fun aesKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

class SecretBoxTest {
    private val key = aesKey()
    private val box = SecretBox { key }
    private val aad = "vesper.face:maya".toByteArray()

    private fun expectSecurity(block: () -> Unit) {
        try { block(); fail("expected SecurityException") } catch (_: SecurityException) {}
    }

    @Test fun roundTrip() {
        val plain = ByteArray(1000) { it.toByte() }
        assertArrayEquals(plain, box.open(box.seal(plain, aad), aad))
    }

    @Test fun emptyPayloadRoundTrips() = assertArrayEquals(ByteArray(0), box.open(box.seal(ByteArray(0), aad), aad))

    @Test fun envelopeLayout() {
        val s = box.seal(ByteArray(10), aad)
        assertEquals(SecretBox.VERSION, s[0])
        val iv = s[1].toInt()
        assertEquals(12, iv)
        assertEquals(2 + iv + 10 + SecretBox.TAG_BYTES, s.size)
    }

    @Test fun freshIvEveryTime() = assertNotEquals(box.seal(ByteArray(8), aad).toList(), box.seal(ByteArray(8), aad).toList())

    @Test fun ciphertextDoesNotContainPlaintext() {
        val plain = "embedding-embedding-embedding".toByteArray()
        val s = String(box.seal(plain, aad), Charsets.ISO_8859_1)
        assertFalse(s.contains("embedding"))
    }

    @Test fun tamperedBodyFails() = expectSecurity {
        val s = box.seal(ByteArray(32) { 1 }, aad); s[s.size - 20] = (s[s.size - 20] + 1).toByte(); box.open(s, aad)
    }

    @Test fun tamperedTagFails() = expectSecurity {
        val s = box.seal(ByteArray(32) { 1 }, aad); s[s.size - 1] = (s[s.size - 1] + 1).toByte(); box.open(s, aad)
    }

    @Test fun wrongAadFails() = expectSecurity { box.open(box.seal(ByteArray(8), aad), "vesper.face:sam".toByteArray()) }

    @Test fun wrongKeyFails() = expectSecurity { SecretBox { aesKey() }.open(box.seal(ByteArray(8), aad), aad) }

    @Test fun truncatedFails() {
        val s = box.seal(ByteArray(8), aad)
        expectSecurity { box.open(s.copyOf(1), aad) }
        expectSecurity { box.open(s.copyOf(2 + 12 + 5), aad) }
        expectSecurity { box.open(ByteArray(0), aad) }
    }

    @Test fun unknownVersionFails() = expectSecurity { val s = box.seal(ByteArray(8), aad); s[0] = 9; box.open(s, aad) }

    @Test fun badIvLengthFails() = expectSecurity { val s = box.seal(ByteArray(8), aad); s[1] = 3; box.open(s, aad) }
}

class TemplateStoreTest {
    private val dir: File = Files.createTempDirectory("faces").toFile()
    private val key = aesKey()
    private fun store() = TemplateStore(dir, SecretBox { key })
    private val t = FaceTemplates("maya", "lbp", List(5) { i -> FloatArray(16) { it * 0.1f + i } })

    @Test fun saveLoadDelete() {
        store().save(t)
        val back = store().load("maya")!!
        assertEquals("lbp", back.embedderId)
        assertEquals(5, back.vectors.size)
        assertArrayEquals(t.vectors[3], back.vectors[3], 0f)
        assertTrue(store().has("maya"))
        store().delete("maya")
        assertFalse(store().has("maya"))
        assertNull(store().load("maya"))
    }

    @Test fun fileIsNotPlaintext() {
        store().save(t)
        val bytes = File(dir, "maya.tpl").readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("lbp"))
    }

    @Test fun fileMovedToAnotherProfileIsUnreadable() {
        store().save(t)
        File(dir, "maya.tpl").copyTo(File(dir, "sam.tpl"))
        assertNull(store().load("sam"))
    }

    @Test fun otherKeyIsUnreadable() {
        store().save(t)
        assertNull(TemplateStore(dir, SecretBox { aesKey() }).load("maya"))
    }

    @Test fun idsCantEscapeTheDirectory() {
        assertEquals("______etc_passwd", TemplateStore.safe("../../etc/passwd"))
        assertEquals("maya-1_x", TemplateStore.safe("maya-1_x"))
    }
}

/** In-memory SettingsStore for tests. */
class MemSettings : SettingsStore {
    val map = HashMap<String, Any>()
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val changes: SharedFlow<String> = _changes
    override fun getString(key: String, default: String) = map[key] as? String ?: default
    override fun putString(key: String, value: String) { map[key] = value; _changes.tryEmit(key) }
    override fun getInt(key: String, default: Int) = map[key] as? Int ?: default
    override fun putInt(key: String, value: Int) { map[key] = value; _changes.tryEmit(key) }
    override fun getFloat(key: String, default: Float) = map[key] as? Float ?: default
    override fun putFloat(key: String, value: Float) { map[key] = value; _changes.tryEmit(key) }
    override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
    override fun putBoolean(key: String, value: Boolean) { map[key] = value; _changes.tryEmit(key) }
    override fun remove(key: String) { map.remove(key); _changes.tryEmit(key) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FaceProfileManagerTest {
    private val settings = MemSettings()
    private val dir: File = Files.createTempDirectory("faces").toFile()
    private val key = aesKey()
    private val templates = TemplateStore(dir, SecretBox { key })
    private var now = 1_000_000L

    private fun TestScope.manager(presence: MutableStateFlow<Presence>? = null, demo: Boolean = true) =
        FaceProfileManager(settings, templates, backgroundScope, presence, demo, clock = { now }, rejectCooldownMs = 60_000, timeoutMs = 600_000)

    @Test fun demoSeedsSharedMayaSam() = runTest {
        val m = manager()
        assertEquals(listOf("shared", "maya", "sam"), m.profiles.value.map { it.id })
        assertEquals(Profile.SHARED_ID, m.active.value.id)
        assertFalse(m.profiles.value.any { it.enrolled })
    }

    @Test fun noDemoIsSharedOnly() = runTest { assertEquals(listOf("shared"), manager(demo = false).profiles.value.map { it.id }) }

    @Test fun persistsProfilesActiveAndAuto() = runTest {
        val m = manager()
        m.upsert(Profile("lee", "Lee", 0xFF0A84FF.toInt(), faceId = "analog"))
        m.switchTo("lee", SwitchReason.MANUAL)
        m.setAutoByFace(false)
        val again = manager(demo = false)
        assertEquals(listOf("shared", "maya", "sam", "lee"), again.profiles.value.map { it.id })
        assertEquals("lee", again.active.value.id)
        assertEquals("analog", again.active.value.faceId)
        assertFalse(again.autoByFace.value)
    }

    @Test fun upsertReplacesInPlace() = runTest {
        val m = manager()
        m.upsert(Profile("maya", "Maya R", 0xFFFF375F.toInt()))
        assertEquals(listOf("shared", "maya", "sam"), m.profiles.value.map { it.id })
        assertEquals("Maya R", m.profiles.value[1].name)
    }

    @Test fun enrolledFollowsTemplates() = runTest {
        val m = manager()
        m.saveTemplates(FaceTemplates("maya", "lbp", List(5) { FloatArray(4) { 1f } }))
        assertTrue(m.profiles.value.first { it.id == "maya" }.enrolled)
        assertEquals(1, m.enrolledTemplates("lbp").size)
        assertEquals(0, m.enrolledTemplates("other-model").size)
        m.forgetFace("maya")
        assertFalse(m.profiles.value.first { it.id == "maya" }.enrolled)
        assertEquals(0, m.enrolledTemplates("lbp").size)
    }

    @Test fun deleteRemovesTemplatesAndFallsBackToShared() = runTest {
        val m = manager()
        m.saveTemplates(FaceTemplates("maya", "lbp", List(3) { FloatArray(4) }))
        m.switchTo("maya", SwitchReason.MANUAL)
        m.delete("maya")
        assertEquals(Profile.SHARED_ID, m.active.value.id)
        assertFalse(templates.has("maya"))
        m.delete(Profile.SHARED_ID)
        assertEquals(Profile.SHARED_ID, m.profiles.value.first().id)
    }

    @Test fun faceSwitchRespectsAutoByFace() = runTest {
        val m = manager()
        m.setAutoByFace(false)
        m.switchTo("maya", SwitchReason.FACE)
        assertEquals(Profile.SHARED_ID, m.active.value.id)
        m.setAutoByFace(true)
        m.switchTo("maya", SwitchReason.FACE)
        assertEquals("maya", m.active.value.id)
        assertEquals(SwitchReason.FACE, m.lastReason)
    }

    @Test fun webSettingsApplyLive() = runTest {
        val m = manager()
        runCurrent()
        settings.putBoolean(FaceProfileManager.KEY_AUTO, false)
        settings.putInt(FaceProfileManager.KEY_TIMEOUT_MIN, 3)
        runCurrent()
        assertFalse(m.autoByFace.value)
        assertEquals(3 * 60_000L, m.timeoutMs)
    }

    @Test fun notMeBlocksForCooldown() = runTest {
        val m = manager()
        m.switchTo("maya", SwitchReason.FACE)
        m.reject("maya")
        assertEquals(Profile.SHARED_ID, m.active.value.id)
        assertTrue(m.isBlocked("maya"))
        assertEquals(setOf("maya"), m.blocked())
        m.switchTo("maya", SwitchReason.FACE)
        assertEquals("blocked from face matching", Profile.SHARED_ID, m.active.value.id)
        m.switchTo("maya", SwitchReason.MANUAL)
        assertEquals("manual still works", "maya", m.active.value.id)
        now += 60_001
        assertFalse(m.isBlocked("maya"))
    }

    @Test fun timeoutBackToSharedAfterEmpty() = runTest {
        val presence = MutableStateFlow(Presence.PRESENT)
        val m = manager(presence)
        m.switchTo("maya", SwitchReason.FACE)
        runCurrent()
        presence.value = Presence.EMPTY
        runCurrent()
        advanceTimeBy(599_000); runCurrent()
        assertEquals("maya", m.active.value.id)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(Profile.SHARED_ID, m.active.value.id)
        assertEquals(SwitchReason.TIMEOUT, m.lastReason)
    }

    @Test fun presenceReturningCancelsTimeout() = runTest {
        val presence = MutableStateFlow(Presence.PRESENT)
        val m = manager(presence)
        m.switchTo("maya", SwitchReason.FACE)
        presence.value = Presence.EMPTY; runCurrent()
        advanceTimeBy(300_000); runCurrent()
        presence.value = Presence.PRESENT; runCurrent()
        advanceTimeBy(900_000); runCurrent()
        assertEquals("maya", m.active.value.id)
    }

    @Test fun unknownPresenceDoesNotTimeOut() = runTest {
        // A dark bedroom at night is UNKNOWN, not EMPTY: keep the sleeper's profile.
        val presence = MutableStateFlow(Presence.PRESENT)
        val m = manager(presence)
        m.switchTo("maya", SwitchReason.FACE)
        presence.value = Presence.UNKNOWN; runCurrent()
        advanceTimeBy(3_600_000); runCurrent()
        assertEquals("maya", m.active.value.id)
    }

    @Test fun noTimeoutWithAutoOff() = runTest {
        val presence = MutableStateFlow(Presence.PRESENT)
        val m = manager(presence)
        m.switchTo("maya", SwitchReason.MANUAL)
        m.setAutoByFace(false)
        presence.value = Presence.EMPTY; runCurrent()
        advanceTimeBy(3_600_000); runCurrent()
        assertEquals("maya", m.active.value.id)
    }

    @Test fun jsonRoundTrip() {
        val l = listOf(Profile("a", "A", 1, "float", "calendar.a"), Profile("b", "B", -2, "digital", null))
        assertEquals(l, FaceProfileManager.parse(FaceProfileManager.encode(l)))
        assertEquals(emptyList<Profile>(), FaceProfileManager.parse("not json"))
    }
}
