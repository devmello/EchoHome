package app.vesper.feature.home.glance

import app.vesper.core.model.Profile
import app.vesper.core.model.SecretStore
import app.vesper.core.model.Secrets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.yield

/**
 * Each profile's own calendars: the iCal URLs (comma / newline separated) stored under
 * `Secrets.calendarKeyFor(profile.id)`, for every profile except the shared one. Re-emits when the
 * profile list or a person's URLs change, so [IcsCalendarProvider] reloads at once.
 *
 * [profiles] and [secrets] are read lazily, after the other features finished installing (the
 * camera feature binds the real profile manager after this one).
 */
object PersonalCalendars {
    fun flow(profiles: () -> Flow<List<Profile>>, secrets: () -> SecretStore): Flow<List<PersonalFeeds>> = flow {
        yield()
        val store = secrets()
        val changed = store.changes.filter { it.startsWith(Secrets.CALENDAR_ICS_URLS + ".") }.map { }.onStart { emit(Unit) }
        emitAll(combine(profiles(), changed) { ps, _ -> read(ps, store) })
    }

    fun read(profiles: List<Profile>, store: SecretStore): List<PersonalFeeds> = profiles
        .filter { it.id != Profile.SHARED_ID }
        .mapNotNull { p ->
            val urls = Secrets.splitUrls(store.get(Secrets.calendarKeyFor(p.id)).orEmpty())
            if (urls.isEmpty()) null else PersonalFeeds(p.id, p.color, urls)
        }
}
