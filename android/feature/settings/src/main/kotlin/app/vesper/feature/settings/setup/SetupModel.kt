package app.vesper.feature.settings.setup

import app.vesper.core.model.SettingsStore

/**
 * Whether this Echo still needs the first-run setup, persisted as [KEY_DONE].
 *
 * The key is written the first time the gate runs, so the answer never changes by itself later:
 *  - a fresh install (no settings at all) gets `setup.done = false` and shows the setup,
 *  - an install that already has settings (an Echo set up before this screen existed) gets
 *    `setup.done = true` and is never interrupted.
 * "Run setup again" on the settings page sets it back to false and opens the setup.
 */
object FirstRun {
    const val KEY_DONE = "setup.done"

    /**
     * @param hadSettings the settings store held keys (other than [KEY_DONE]) before this process
     *   wrote any; see [SetupFeature] for why that's read before the other features install.
     * @return true when the setup should show.
     */
    fun resolve(settings: SettingsStore, hadSettings: Boolean): Boolean {
        val stored = stored(settings)
        if (stored != null) return !stored
        settings.putBoolean(KEY_DONE, hadSettings)
        return !hadSettings
    }

    /** null when the gate never ran on this install. */
    fun stored(settings: SettingsStore): Boolean? = try {
        // The two defaults differ only when the key is absent.
        val a = settings.getBoolean(KEY_DONE, false)
        val b = settings.getBoolean(KEY_DONE, true)
        if (a != b) null else a
    } catch (_: ClassCastException) {
        // Written as text by something else ("true" / "false").
        settings.getString(KEY_DONE, "").trim().lowercase().toBooleanStrictOrNull()
    }

    fun markDone(settings: SettingsStore) = settings.putBoolean(KEY_DONE, true)
    fun reset(settings: SettingsStore) = settings.putBoolean(KEY_DONE, false)

    /** Keys that don't count as "this Echo has settings". */
    fun countsAsSettings(key: String) = key != KEY_DONE
}

/**
 * What happens to the setup when something navigates to another screen (a launch intent's
 * `screen`, an alarm ringing, a banner tap). The shell always closes the setup then, and the
 * target screen shows: nothing waits behind the setup, and `setup.done` doesn't change.
 *
 * - A setup opened for review (`--es screen onboarding` on an Echo that's set up) stays closed.
 * - The first-run setup ([needed]) has to be finished or skipped, so it comes back when the Echo
 *   returns to StandBy ([home]), at the step it was on.
 *
 * Screens in [passThrough] (face enrolment, which comes back to the setup by itself) don't count
 * as leaving. Fed with every `navigator.current` value; [onScreen] true = open the setup again.
 */
class SetupResume(
    private val screen: String = SetupFeature.SCREEN,
    private val home: String = SetupFeature.HOME_SCREEN,
    private val passThrough: Set<String> = setOf(OnboardingView.SCREEN_ENROL),
) {
    private var inSetup = false
    private var interrupted = false

    fun onScreen(id: String, needed: Boolean): Boolean {
        if (!needed) { inSetup = false; interrupted = false; return false }
        when {
            id == screen -> { inSetup = true; interrupted = false }
            inSetup && id in passThrough -> Unit
            inSetup -> { inSetup = false; interrupted = true }
        }
        return interrupted && id == home
    }
}

/** The setup's pages, in order. Only [WELCOME] and [DONE] can't be skipped. */
enum class SetupStep(val id: String, val optional: Boolean = true) {
    WELCOME("welcome", optional = false),
    WIFI("wifi"),
    NAME("name"),
    LOCATION("location"),
    PHONE("phone"),
    PEOPLE("people"),
    CLOCK("clock"),
    DONE("done", optional = false);

    companion object {
        /** "3" (1-based, as the dots count) or an id ("wifi"); null when it matches nothing. */
        fun parse(arg: String?): SetupStep? {
            val s = arg?.trim()?.lowercase()?.ifEmpty { null } ?: return null
            s.toIntOrNull()?.let { n -> return entries.getOrNull(n - 1) }
            return entries.firstOrNull { it.id == s }
        }
    }
}

/** Where the user is in the setup. Pure state, no Android: the view draws it. */
class SetupFlow(val steps: List<SetupStep> = SetupStep.entries) {
    init { require(steps.firstOrNull() == SetupStep.WELCOME && steps.lastOrNull() == SetupStep.DONE) }

    var index = 0
        private set
    val step: SetupStep get() = steps[index]
    val isFirst get() = index == 0
    val isLast get() = index == steps.lastIndex
    /** Steps left with "Skip" (not "Next"), for the log and the Done page. */
    val skipped = linkedSetOf<SetupStep>()

    val canBack get() = !isFirst

    /** Next page; [engaged] = the user did this step's thing (see [rightLabel]). False on the last page. */
    fun next(engaged: Boolean = true): Boolean {
        if (isLast) return false
        if (!engaged && step.optional) skipped += step else skipped -= step
        index++
        return true
    }

    fun back(): Boolean {
        if (isFirst) return false
        index--
        return true
    }

    fun go(to: SetupStep): Boolean {
        val i = steps.indexOf(to)
        if (i < 0 || i == index) return false
        index = i
        return true
    }

    fun restart() { index = 0; skipped.clear() }

    /**
     * The tinted button at the bottom right. An optional step the user hasn't done says "Skip",
     * so nobody feels they must; once done (or when it shows a value they can keep) it says "Next".
     */
    fun rightLabel(engaged: Boolean): String = when {
        step == SetupStep.WELCOME -> "Get started"
        isLast -> "Done"
        step.optional && !engaged -> "Skip"
        else -> "Next"
    }
}

/** Choices the setup offers, mapped onto the settings keys the features already read. */
object SetupChoices {
    // Owned by other features; the same keys, never parallel ones.
    const val KEY_UNIT = "weather.unit"          // :feature:home WeatherSettings.UNIT ("F" / "C" / "" = from region)
    const val KEY_FACE = "clock.face"            // :feature:clock ClockSettings.KEY_FACE
    const val KEY_NIGHT_MODE = "clock.night.mode" // :feature:clock ClockSettings.KEY_NIGHT_MODE
    const val KEY_NIGHT_START = "clock.night.start"
    const val KEY_NIGHT_END = "clock.night.end"
    const val KEY_WAKE_WORD = "assistant.wakeWord" // :feature:assistant AssistantSettings.KEY_WAKE

    /** Tap-to-pick names (typing on the Echo is poor). The first is the default. */
    val NAMES = listOf("Echo Show", "Kitchen", "Bedroom", "Living Room", "Office", "Nursery")
    const val NAME_MAX = 32

    /** A name as AirPlay and Bluetooth show it: trimmed, no control characters, at most [NAME_MAX]. */
    fun cleanName(raw: String): String? =
        raw.filter { !it.isISOControl() }.trim().replace(Regex("\\s+"), " ").take(NAME_MAX).trim().ifEmpty { null }

    /** Same rule as :feature:home's WeatherSettings.fahrenheit. */
    fun fahrenheit(stored: String?, country: String): Boolean = when (stored?.trim()?.uppercase()) {
        "F" -> true
        "C" -> false
        else -> country in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW", "FM", "MH")
    }

    enum class Night(val stored: String, val title: String, val detail: String) {
        AUTO("auto", "Automatic", "When the room gets dark"),
        SCHEDULE("schedule", "On a schedule", ""),
        OFF("off", "Off", "Always full colour");

        companion object {
            /** "on" (always on) isn't offered here; it reads as Automatic. */
            fun of(stored: String?): Night = entries.firstOrNull { it.stored == stored?.trim()?.lowercase() } ?: AUTO
        }
    }

    /** "22:00".."07:00" → "10 PM to 7 AM" (or "22:00 to 07:00" in 24 h). */
    fun scheduleText(start: String, end: String, is24: Boolean): String = "${clock(start, is24)} to ${clock(end, is24)}"

    private fun clock(hhmm: String, is24: Boolean): String {
        val p = hhmm.split(':')
        val h = p.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: return hhmm
        val m = p.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        if (is24) return "%02d:%02d".format(h, m)
        val h12 = if (h % 12 == 0) 12 else h % 12
        val ap = if (h < 12) "AM" else "PM"
        return if (m == 0) "$h12 $ap" else "$h12:%02d $ap".format(m)
    }

    /** Which of the faces to show first: the stored one if it exists, else the first. */
    fun faceIndex(ids: List<String>, stored: String?): Int = ids.indexOf(stored).takeIf { it >= 0 } ?: 0
}
