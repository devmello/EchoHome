package app.vesper.core.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A household member (NOTES §6.6). "shared" is the default profile when nobody is recognised. */
data class Profile(
    val id: String,
    val name: String,
    /** Accent / face colour, ARGB. */
    val color: Int,
    /** Preferred StandBy face id (see [FaceRegistry]). */
    val faceId: String = "digital",
    /** Home Assistant calendar entity, e.g. "calendar.maya". */
    val calendarEntity: String? = null,
    /** Face-recognition templates are stored by `:feature:camera`, never here. */
    val enrolled: Boolean = false,
) {
    companion object {
        const val SHARED_ID = "shared"
        val SHARED = Profile(SHARED_ID, "Everyone", 0xFFFFFFFF.toInt())
    }
}

enum class SwitchReason { MANUAL, FACE, TIMEOUT }

interface ProfileManager {
    val profiles: StateFlow<List<Profile>>
    val active: StateFlow<Profile>
    /** Control Center's "Auto by face". */
    val autoByFace: StateFlow<Boolean>

    fun switchTo(profileId: String, reason: SwitchReason)
    fun setAutoByFace(enabled: Boolean)
    /** "Not me": go back to shared and block [profileId] from face matching for a few minutes. */
    fun reject(profileId: String)
    fun upsert(profile: Profile)
    fun delete(profileId: String)
}

/** Notification banner (Banners.dc.html). */
data class Banner(
    val id: String,
    val appName: String,
    /** Tile colour for the app icon, ARGB. */
    val appColor: Int,
    /** Optional glyph key the renderer maps to an icon ("message", "doorbell", "calendar", ...). */
    val glyph: String? = null,
    val title: String,
    val body: String,
    val postedAtMillis: Long = System.currentTimeMillis(),
    /** Shown even in Do Not Disturb: doorbell, alarm, calls. */
    val critical: Boolean = false,
    /** The one tinted action in the expanded view ("Reply by voice", "Show camera", "Join call"). */
    val primaryAction: IslandAction? = null,
    /** Package of the Android notification, or the iOS bundle id for iPhone (ANCS) notifications. */
    val sourcePackage: String? = null,
    /** The phone it came from, e.g. "Maya's iPhone"; null = the Echo itself. */
    val sourceDevice: String? = null,
)

interface NotificationFeed {
    /** Newest last. */
    val banners: StateFlow<List<Banner>>
    fun post(banner: Banner)
    /** [byUser] = an explicit Dismiss tap / swipe (not a timeout, DND or clear-all): the source may then clear it on the phone. */
    fun dismiss(id: String, byUser: Boolean = false)
    /** Ids of banners the user dismissed (see [dismiss]). */
    val userDismissals: kotlinx.coroutines.flow.SharedFlow<String>
}

class DefaultNotificationFeed : NotificationFeed {
    private val _banners = MutableStateFlow<List<Banner>>(emptyList())
    override val banners: StateFlow<List<Banner>> = _banners.asStateFlow()
    override fun post(banner: Banner) = _banners.update { l -> l.filterNot { it.id == banner.id } + banner }
    private val _userDismissals = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val userDismissals: kotlinx.coroutines.flow.SharedFlow<String> = _userDismissals
    override fun dismiss(id: String, byUser: Boolean) {
        _banners.update { l -> l.filterNot { it.id == id } }
        if (byUser) _userDismissals.tryEmit(id)
    }
}
