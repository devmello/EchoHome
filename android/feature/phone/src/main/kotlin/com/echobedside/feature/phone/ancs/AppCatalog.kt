package com.echobedside.feature.phone.ancs

/**
 * Banner tile colour + glyph for common iOS bundle ids, and a fallback by ANCS category.
 * Glyph keys follow the banner renderer (`:feature:system` Icons.glyph): "message", "call",
 * "calendar", "home", "doorbell", "camera", "alarm", "timer"; the extra keys used here ("mail",
 * "video", "music", "news", "heart", "location", "bell") fall back to a bell until it draws them.
 */
object AppCatalog {
    data class App(val name: String, val color: Int, val glyph: String)

    private fun c(hex: Long) = hex.toInt()

    private val apps: Map<String, App> = mapOf(
        "com.apple.MobileSMS" to App("Messages", c(0xFF30D158), "message"),
        "com.apple.mobilephone" to App("Phone", c(0xFF30D158), "call"),
        "com.apple.facetime" to App("FaceTime", c(0xFF30D158), "video"),
        "com.apple.mobilemail" to App("Mail", c(0xFF0A84FF), "mail"),
        "com.apple.mobilecal" to App("Calendar", c(0xFFFF453A), "calendar"),
        "com.apple.reminders" to App("Reminders", c(0xFF0A84FF), "alarm"),
        "com.apple.mobiletimer" to App("Clock", c(0xFFFF9F0A), "alarm"),
        "com.apple.Home" to App("Home", c(0xFFFF9F0A), "home"),
        "com.apple.Health" to App("Health", c(0xFFFF375F), "heart"),
        "com.apple.Fitness" to App("Fitness", c(0xFF30D158), "heart"),
        "com.apple.news" to App("News", c(0xFFFF375F), "news"),
        "com.apple.Music" to App("Music", c(0xFFFF375F), "music"),
        "com.apple.podcasts" to App("Podcasts", c(0xFFBF5AF2), "music"),
        "com.apple.findmy" to App("Find My", c(0xFF30D158), "location"),
        "com.apple.weather" to App("Weather", c(0xFF0A84FF), "bell"),
        "com.apple.mobileslideshow" to App("Photos", c(0xFFFF9F0A), "camera"),
        "com.apple.Passbook" to App("Wallet", c(0xFF636366), "bell"),
        "net.whatsapp.WhatsApp" to App("WhatsApp", c(0xFF25D366), "message"),
        "org.whispersystems.signal" to App("Signal", c(0xFF3A76F0), "message"),
        "ph.telegra.Telegraph" to App("Telegram", c(0xFF2AABEE), "message"),
        "com.facebook.Messenger" to App("Messenger", c(0xFF0A84FF), "message"),
        "com.burbn.instagram" to App("Instagram", c(0xFFE1306C), "camera"),
        "com.tinyspeck.chatlyio" to App("Slack", c(0xFF611F69), "message"),
        "com.hammerandchisel.discord" to App("Discord", c(0xFF5865F2), "message"),
        "com.microsoft.skype.teams" to App("Teams", c(0xFF6264A7), "message"),
        "com.google.Gmail" to App("Gmail", c(0xFFEA4335), "mail"),
        "com.microsoft.Office.Outlook" to App("Outlook", c(0xFF0078D4), "mail"),
        "us.zoom.videomeetings" to App("Zoom", c(0xFF2D8CFF), "video"),
        "com.spotify.client" to App("Spotify", c(0xFF1DB954), "music"),
        "com.google.ios.youtube" to App("YouTube", c(0xFFFF0000), "video"),
        "com.ubercab.UberClient" to App("Uber", c(0xFF636366), "location"),
        "com.ringapp" to App("Ring", c(0xFF1C9AD6), "doorbell"),
        "com.amazon.AmazonShopping" to App("Amazon", c(0xFFFF9F0A), "bell"),
    )

    fun lookup(bundleId: String): App? = apps[bundleId]

    /** Colour + glyph for an app we don't know, by what kind of notification it is. */
    fun forCategory(category: AncsCategory): App = when (category) {
        AncsCategory.INCOMING_CALL, AncsCategory.MISSED_CALL, AncsCategory.VOICEMAIL -> App("Phone", c(0xFF30D158), "call")
        AncsCategory.SOCIAL -> App("", c(0xFF0A84FF), "message")
        AncsCategory.SCHEDULE -> App("", c(0xFFFF453A), "calendar")
        AncsCategory.EMAIL -> App("", c(0xFF0A84FF), "mail")
        AncsCategory.NEWS -> App("", c(0xFFFF375F), "news")
        AncsCategory.HEALTH_AND_FITNESS -> App("", c(0xFFFF375F), "heart")
        AncsCategory.LOCATION -> App("", c(0xFF30D158), "location")
        AncsCategory.ENTERTAINMENT -> App("", c(0xFFBF5AF2), "music")
        AncsCategory.BUSINESS_AND_FINANCE, AncsCategory.OTHER -> App("", c(0xFF636366), "bell")
    }

    /** "com.example.CoolApp" → "CoolApp", when the phone sends no display name. */
    fun nameFromBundle(bundleId: String): String = bundleId.substringAfterLast('.').ifBlank { bundleId }
}
