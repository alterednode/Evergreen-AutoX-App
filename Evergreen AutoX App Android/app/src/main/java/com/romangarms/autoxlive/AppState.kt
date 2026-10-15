package com.romangarms.autoxlive

import com.romangarms.autoxlive.data.AccelEntry
import com.romangarms.autoxlive.data.Driver
import com.romangarms.autoxlive.data.EventSource
import com.romangarms.autoxlive.data.LBBlock
import com.romangarms.autoxlive.data.LBCourse
import com.romangarms.autoxlive.data.LBCourseDetail
import com.romangarms.autoxlive.data.LBEntry
import com.romangarms.autoxlive.data.SHEvent
import com.romangarms.autoxlive.data.SHSession
import com.romangarms.autoxlive.data.StoredSettings
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

enum class RouteKind { LIVE, FRIENDS, EVENTS, BOARDS, SETUP, DRIVER, COMPARE, LEADERBOARD, ACCELERATION, OTHER }

// When what a page shows was fetched, and whether the latest try to replace
// it failed, which is when the page says its data is old.
data class DataAge(val fetchedAt: Long, val refreshFailed: Boolean = false)

data class AppState(
    val settings: StoredSettings = StoredSettings(),
    val loaded: Boolean = false,
    val orgName: String? = null,
    val events: List<SHEvent> = emptyList(),
    val leaderboardCourses: List<LBCourse> = emptyList(),
    val openCourseId: Int? = null,
    val leaderboardDetail: LBCourseDetail? = null,
    val leaderboardError: String? = null,
    val blocks: List<LBBlock> = emptyList(),
    val accelerationEntries: List<AccelEntry>? = null,
    val accelerationError: String? = null,
    val sessions: List<SHSession> = emptyList(),
    val drivers: List<Driver> = emptyList(),
    val selectedEventId: Int? = null,
    val selectedSessionId: Int? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val eventsAge: DataAge? = null,
    val boardsAge: DataAge? = null,
    val driversAge: DataAge? = null,
    val leaderboardAge: DataAge? = null,
    val accelerationAge: DataAge? = null,
    val isRetrying: Boolean = false,
    val compareSelection: List<String> = emptyList(),
    val route: RouteKind = RouteKind.LIVE,
) {
    // Car numbers repeat across events, so pins/nicknames/ME are scoped to
    // the selected event rather than stored globally.
    val eventKey: String? get() = selectedEventId?.toString()

    val selectedEvent: SHEvent? get() = events.firstOrNull { it.id == selectedEventId }
    val selectedSession: SHSession? get() = sessions.firstOrNull { it.id == selectedSessionId }

    val pins: Set<String> get() = eventKey?.let { settings.pinsByEvent[it] }?.toSet() ?: emptySet()
    val nicknames: Map<String, String> get() = eventKey?.let { settings.nicknamesByEvent[it] } ?: emptyMap()
    val meNumber: String? get() = eventKey?.let { settings.meNumberByEvent[it] }
    val me: Driver? get() = meNumber?.let(::driver)

    // Car numbers are per event, so the prompt comes back for each new event
    // until it's answered or dismissed there.
    val showsMePrompt: Boolean
        get() {
            val key = eventKey ?: return false
            return drivers.isNotEmpty() && meNumber == null && key !in settings.mePromptDismissedEvents
        }

    val friends: List<Driver>
        get() = listOfNotNull(me) + drivers.filter { it.startNumber in pins && it.startNumber != meNumber }

    val hasSpeedhiveAutoXEvents: Boolean
        get() = events.any { it.source == EventSource.SPEEDHIVE && isAutoX(it) }

    // `events` is newest-first, so each `first` is that source's latest.
    val latestAutoXEventId: Int?
        get() = events.firstOrNull { it.source != EventSource.LEADERBOARD && isAutoX(it) }?.id

    // The org's newest Speedhive event is usually an oval race night, which
    // means nothing to an autocrosser opening the app for the first time.
    val defaultEventId: Int?
        get() = (
            events.firstOrNull { it.source == EventSource.GGLC }
                ?: events.firstOrNull { it.source == EventSource.SPEEDHIVE && isAutoX(it) }
                ?: events.firstOrNull { it.source != EventSource.LEADERBOARD }
            )?.id

    // An oval race night hidden by the AutoX-only filter isn't reopened on
    // launch, even if it was the last event viewed.
    val restorableEventId: Int?
        get() {
            val saved = settings.selectedEventId ?: return null
            val event = events.firstOrNull { it.id == saved } ?: return null
            return if (!settings.speedhiveAutoXOnly || isAutoX(event)) event.id else null
        }

    val leaderboardEntries: List<LBEntry> get() = leaderboardDetail?.let { LBEntry.entries(it.runs) } ?: emptyList()

    fun leaderboardEntry(position: Int): LBEntry? = leaderboardEntries.firstOrNull { it.id == position }

    fun driver(number: String): Driver? = drivers.firstOrNull { it.startNumber == number }

    fun displayName(driver: Driver): String = nicknames[driver.startNumber] ?: driver.name

    fun classCount(carClass: String?): Int = drivers.count { it.carClass == carClass }

    fun gapToMe(driver: Driver): Double? {
        val mine = me?.best ?: return null
        val theirs = driver.best ?: return null
        if (driver.startNumber == meNumber) return null
        return theirs - mine
    }

    fun sessionLabel(session: SHSession, eventName: String? = null): String {
        val parts = mutableListOf<String>()
        val event = eventName ?: selectedEvent?.name
        if (!session.name.isNullOrEmpty() && session.name != event) {
            parts += session.name
        } else if (!session.type.isNullOrEmpty()) {
            parts += session.type.lowercase().replaceFirstChar { it.titlecase(Locale.getDefault()) }
        }
        timeOfDay(session.startTime)?.let { parts += it }
        return if (parts.isEmpty()) "Session ${session.id}" else parts.joinToString(" · ")
    }

    val staleSince: Long?
        get() {
            val age = when (route) {
                RouteKind.LIVE, RouteKind.FRIENDS, RouteKind.DRIVER, RouteKind.COMPARE -> driversAge
                RouteKind.EVENTS -> eventsAge
                RouteKind.BOARDS -> boardsAge
                RouteKind.LEADERBOARD -> leaderboardAge
                RouteKind.ACCELERATION -> accelerationAge
                RouteKind.SETUP, RouteKind.OTHER -> null
            }
            return age?.takeIf { it.refreshFailed }?.fetchedAt
        }

    // Only an event from today or yesterday can still be producing times, and
    // only these pages show them.
    fun wantsLiveRefresh(today: LocalDate = LocalDate.now()): Boolean {
        val event = selectedEvent ?: return false
        if (event.source == EventSource.LEADERBOARD) return false
        val start = event.startDate ?: return false
        if (start.take(10) < today.minusDays(1).toString()) return false
        return route in setOf(RouteKind.LIVE, RouteKind.FRIENDS, RouteKind.DRIVER, RouteKind.COMPARE)
    }

    companion object {
        const val DEFAULT_ORG_ID = 151294

        // GGLC events have no Speedhive ID, so they get -yyyymmdd: negative so
        // they can never collide with a real Speedhive event ID.
        fun gglcEventId(date: String): Int? = date.replace("-", "").toIntOrNull()?.let { -it }

        // Leaderboard courses share the negative ID space with GGLC's
        // -yyyymmdd, so they're offset past any representable date.
        fun leaderboardEventId(courseId: Int): Int = -(1_000_000_000 + courseId)

        fun leaderboardCourseId(eventId: Int): Int = -eventId - 1_000_000_000

        // The Speedhive org also times oval racing; only its autocross events
        // carry "AutoX" in the name.
        fun isAutoX(event: SHEvent): Boolean =
            event.source != EventSource.SPEEDHIVE || event.name.contains("autox", ignoreCase = true)

        fun eventMonth(event: SHEvent): String? = event.startDate?.takeIf { it.length >= 7 }?.take(7)

        fun monthLabel(month: String): String =
            runCatching {
                LocalDate.parse("$month-01").format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
            }.getOrDefault(month)

        fun timeOfDay(iso: String?): String? {
            iso ?: return null
            return runCatching {
                LocalDateTime.parse(iso.take(19)).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
            }.getOrNull()
        }

        fun eventDate(date: String?): String? {
            date ?: return null
            return runCatching {
                LocalDate.parse(date.take(10)).format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.getDefault()))
            }.getOrNull()
        }

        fun staleLabel(millis: Long, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
            val time = Instant.ofEpochMilli(millis).atZone(zone)
            return if (time.toLocalDate() == today) {
                time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
            } else {
                time.format(DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.getDefault()))
            }
        }

        // A list built from several answers is as old as its oldest one.
        fun combined(ages: List<DataAge?>): DataAge? {
            val known = ages.filterNotNull()
            val oldest = known.minOfOrNull { it.fetchedAt } ?: return null
            return DataAge(oldest, known.any { it.refreshFailed })
        }

        // Speedhive lists sessions grouped by class, not by time, so "first"
        // is usually a morning practice.
        fun latestSession(sessions: List<SHSession>): SHSession? = sessions.maxByOrNull { it.startTime ?: "" }

        fun byMostRecent(sessions: List<SHSession>): List<SHSession> = sessions.sortedByDescending { it.startTime ?: "" }
    }
}
