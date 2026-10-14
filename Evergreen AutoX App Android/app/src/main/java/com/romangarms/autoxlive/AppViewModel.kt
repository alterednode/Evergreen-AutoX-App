package com.romangarms.autoxlive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.romangarms.autoxlive.data.AccelEntry
import com.romangarms.autoxlive.data.ApiClient
import com.romangarms.autoxlive.data.Driver
import com.romangarms.autoxlive.data.EventSource
import com.romangarms.autoxlive.data.GGLCEventStub
import com.romangarms.autoxlive.data.LBBlock
import com.romangarms.autoxlive.data.LBCourse
import com.romangarms.autoxlive.data.LBCourseDetail
import com.romangarms.autoxlive.data.LBReportTarget
import com.romangarms.autoxlive.data.LBReportType
import com.romangarms.autoxlive.data.SHEvent
import com.romangarms.autoxlive.data.SHLocation
import com.romangarms.autoxlive.data.SHOrg
import com.romangarms.autoxlive.data.SHSession
import com.romangarms.autoxlive.data.SettingsStore
import com.romangarms.autoxlive.data.StoredSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale

interface AutoXApi {
    suspend fun org(orgId: Int): SHOrg
    suspend fun events(orgId: Int, limit: Int = 200): List<SHEvent>
    suspend fun sessions(eventId: Int): List<SHSession>
    suspend fun drivers(sessionId: Int): List<Driver>
    suspend fun gglcEvents(year: Int): List<GGLCEventStub>
    suspend fun leaderboardCourses(): List<LBCourse>
    suspend fun leaderboardCourse(id: Int): LBCourseDetail
    suspend fun acceleration(): List<AccelEntry>
    suspend fun joinCourse(code: String): LBCourse
    suspend fun leaveCourse(id: Int)
    suspend fun report(target: LBReportTarget, reason: String)
    suspend fun block(target: LBReportTarget): LBBlock
    suspend fun blocks(): List<LBBlock>
    suspend fun unblock(id: Int)
}

class RemoteApi(private val client: ApiClient) : AutoXApi {
    override suspend fun org(orgId: Int) = client.org(orgId)
    override suspend fun events(orgId: Int, limit: Int) = client.events(orgId, limit)
    override suspend fun sessions(eventId: Int) = client.sessions(eventId)
    override suspend fun drivers(sessionId: Int) = client.drivers(sessionId)
    override suspend fun gglcEvents(year: Int) = client.gglcEvents(year)
    override suspend fun leaderboardCourses() = client.leaderboardCourses()
    override suspend fun leaderboardCourse(id: Int) = client.leaderboardCourse(id)
    override suspend fun acceleration() = client.acceleration()
    override suspend fun joinCourse(code: String) = client.joinCourse(code)
    override suspend fun leaveCourse(id: Int) { client.leaveCourse(id) }
    override suspend fun report(target: LBReportTarget, reason: String) { client.report(target, reason) }
    override suspend fun block(target: LBReportTarget) = client.block(target)
    override suspend fun blocks() = client.blocks()
    override suspend fun unblock(id: Int) { client.unblock(id) }
}

sealed interface NavCommand {
    data object ShowLive : NavCommand
    data class OpenLeaderboard(val courseId: Int) : NavCommand

    // Leaves the board's pages if one is open, landing on the Boards tab.
    data class LeaveLeaderboard(val courseId: Int) : NavCommand

    // Pops back to the board itself, since ranks may have moved under an
    // open driver page.
    data class BackToLeaderboard(val courseId: Int) : NavCommand
    data object BackToAcceleration : NavCommand
}

class AppViewModel(
    private val api: AutoXApi,
    private val store: SettingsStore,
    private val today: () -> LocalDate = LocalDate::now,
) : ViewModel() {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val navChannel = Channel<NavCommand>(Channel.BUFFERED)
    val navCommands = navChannel.receiveAsFlow()

    var lastSessionLoad: Long = 0
        private set

    private val current get() = _state.value

    fun start() {
        if (current.loaded) return
        viewModelScope.launch {
            val settings = store.load()
            _state.update { it.copy(settings = settings, loaded = true) }
            loadEvents()
        }
    }

    private fun editSettings(change: (StoredSettings) -> StoredSettings) {
        _state.update { it.copy(settings = change(it.settings)) }
        val snapshot = current.settings
        viewModelScope.launch { store.save(snapshot) }
    }

    fun setRoute(route: RouteKind) {
        _state.update { it.copy(route = route) }
    }

    fun setLiveSortByClass(byClass: Boolean) = editSettings { it.copy(liveSortByClass = byClass) }
    fun setSpeedhiveAutoXOnly(only: Boolean) = editSettings { it.copy(speedhiveAutoXOnly = only) }
    fun acceptGuidelines() = editSettings { it.copy(acceptedGuidelines = true) }
    fun setLastUpdateCheck(millis: Long) = editSettings { it.copy(lastUpdateCheck = millis) }

    fun setMe(number: String?) {
        val key = current.eventKey ?: return
        editSettings {
            it.copy(meNumberByEvent = if (number == null) it.meNumberByEvent - key else it.meNumberByEvent + (key to number))
        }
    }

    fun dismissMePrompt() {
        val key = current.eventKey ?: return
        if (key in current.settings.mePromptDismissedEvents) return
        editSettings { it.copy(mePromptDismissedEvents = it.mePromptDismissedEvents + key) }
    }

    fun togglePin(number: String) {
        val key = current.eventKey ?: return
        val pins = current.pins.let { if (number in it) it - number else it + number }
        editSettings {
            it.copy(pinsByEvent = if (pins.isEmpty()) it.pinsByEvent - key else it.pinsByEvent + (key to pins.sorted()))
        }
        _state.update { s -> s.copy(compareSelection = s.compareSelection.filter { it in s.pins || it == s.meNumber }) }
    }

    fun toggleCompareSelection(number: String) {
        _state.update { s ->
            val selection = if (number in s.compareSelection) s.compareSelection - number else (s.compareSelection + number).takeLast(2)
            s.copy(compareSelection = selection)
        }
    }

    fun setNickname(nickname: String, number: String) {
        val key = current.eventKey ?: return
        val trimmed = nickname.trim()
        val names = current.nicknames.let { if (trimmed.isEmpty()) it - number else it + (number to trimmed) }
        editSettings {
            it.copy(nicknamesByEvent = if (names.isEmpty()) it.nicknamesByEvent - key else it.nicknamesByEvent + (key to names))
        }
    }

    fun resetPersonalization() {
        editSettings {
            it.copy(
                pinsByEvent = emptyMap(),
                nicknamesByEvent = emptyMap(),
                meNumberByEvent = emptyMap(),
                mePromptDismissedEvents = emptyList(),
            )
        }
        _state.update { it.copy(compareSelection = emptyList()) }
    }

    fun refreshEvents() {
        viewModelScope.launch { loadEvents() }
    }

    suspend fun loadEvents() {
        _state.update { it.copy(isLoading = true, errorMessage = null) }
        try {
            val (org, speedhive, gglc, leaderboard) = coroutineScope {
                val org = async { runCatching { api.org(AppState.DEFAULT_ORG_ID) }.getOrNull() }
                val gglc = async { loadGGLCEvents() }
                val leaderboard = async { loadLeaderboardEvents() }
                val speedhive = api.events(AppState.DEFAULT_ORG_ID)
                Quad(org.await(), speedhive, gglc.await(), leaderboard.await())
            }
            val events = (speedhive + gglc).sortedByDescending { it.startDate ?: "" } + leaderboard
            _state.update { it.copy(events = events, orgName = org?.name) }
            // Only an event the user opened is remembered; until then every
            // launch follows the newest one. A reload keeps what's on screen.
            val s = current
            val stillThere = s.events.firstOrNull { it.id == s.selectedEventId }?.id
            val eventId = stillThere ?: s.restorableEventId ?: s.defaultEventId
            if (eventId != null) {
                selectEvent(eventId, remember = false)
            } else {
                _state.update { it.copy(isLoading = false) }
            }
        } catch (e: Exception) {
            _state.update { it.copy(errorMessage = e.message, isLoading = false) }
        }
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    // GGLC failures only cost the extra events; Speedhive stays usable.
    private suspend fun loadGGLCEvents(): List<SHEvent> = coroutineScope {
        val year = today().year
        val thisYear = async { runCatching { api.gglcEvents(year) }.getOrDefault(emptyList()) }
        val lastYear = async { runCatching { api.gglcEvents(year - 1) }.getOrDefault(emptyList()) }
        (thisYear.await() + lastYear.await()).mapNotNull { stub ->
            AppState.gglcEventId(stub.date)?.let { id ->
                SHEvent(
                    id = id,
                    name = "GGLC Autocross",
                    startDate = stub.date,
                    location = SHLocation(name = "Golden Gate Lotus Club"),
                    source = EventSource.GGLC,
                )
            }
        }
    }

    // Like GGLC, leaderboard failures only cost the extra rows.
    private suspend fun loadLeaderboardEvents(): List<SHEvent> {
        val courses = runCatching { api.leaderboardCourses() }.getOrDefault(emptyList())
        _state.update { it.copy(leaderboardCourses = courses) }
        val hidden = current.settings.hiddenCourseIds
        return courses.filter { it.id !in hidden }.map(::leaderboardEvent)
    }

    private fun leaderboardEvent(course: LBCourse) = SHEvent(
        id = AppState.leaderboardEventId(course.id),
        name = course.name,
        location = SHLocation(
            name = course.createdBy?.let { "by $it" },
            lengthLabel = course.distanceMiles?.let { String.format(Locale.US, "%.2f mi", it) },
        ),
        source = EventSource.LEADERBOARD,
    )

    suspend fun refreshLeaderboardEvents() {
        val leaderboard = loadLeaderboardEvents()
        _state.update { s -> s.copy(events = s.events.filter { it.source != EventSource.LEADERBOARD } + leaderboard) }
    }

    fun openEvent(eventId: Int) {
        val event = current.events.firstOrNull { it.id == eventId } ?: return
        if (event.source == EventSource.LEADERBOARD) {
            navChannel.trySend(NavCommand.OpenLeaderboard(AppState.leaderboardCourseId(eventId)))
            return
        }
        navChannel.trySend(NavCommand.ShowLive)
        if (eventId == current.selectedEventId && current.drivers.isNotEmpty()) return
        // Stale rows from the old session would make a failed load look like
        // the switch never happened, especially between same-named events.
        _state.update {
            it.copy(drivers = emptyList(), compareSelection = emptyList(), sessions = emptyList(), selectedSessionId = null, isLoading = true)
        }
        viewModelScope.launch { selectEvent(eventId) }
    }

    fun pickSession(sessionId: Int) {
        if (sessionId == current.selectedSessionId) return
        navChannel.trySend(NavCommand.ShowLive)
        _state.update { it.copy(drivers = emptyList(), compareSelection = emptyList(), isLoading = true) }
        viewModelScope.launch { selectSession(sessionId) }
    }

    // Loads overlap when the user taps around faster than the network; after
    // every await, a load whose event or session is no longer the selected
    // one stops without touching state.
    suspend fun selectEvent(eventId: Int, remember: Boolean = true) {
        _state.update { it.copy(selectedEventId = eventId, isLoading = true, errorMessage = null) }
        if (remember) editSettings { it.copy(selectedEventId = eventId) }
        try {
            val fetched = if (eventId < 0) gglcSessions(eventId) else api.sessions(eventId)
            if (current.selectedEventId != eventId) return
            _state.update { it.copy(sessions = fetched) }
            val saved = current.settings.sessionChoice[eventId.toString()]
            val sessionId = fetched.firstOrNull { it.id == saved }?.id ?: AppState.latestSession(fetched)?.id
            if (sessionId != null) {
                selectSession(sessionId)
            } else {
                _state.update { it.copy(drivers = emptyList(), isLoading = false) }
            }
        } catch (e: Exception) {
            if (current.selectedEventId != eventId) return
            _state.update { it.copy(errorMessage = e.message, isLoading = false) }
        }
    }

    private fun gglcSessions(eventId: Int) = listOf(SHSession(id = eventId, name = "Results"))

    suspend fun selectSession(sessionId: Int) {
        _state.update { it.copy(selectedSessionId = sessionId) }
        current.selectedEventId?.let { eventId ->
            editSettings { it.copy(sessionChoice = it.sessionChoice + (eventId.toString() to sessionId)) }
        }
        loadSessionData()
    }

    fun reload() {
        viewModelScope.launch { loadSessionData() }
    }

    suspend fun loadSessionData() {
        val sessionId = current.selectedSessionId ?: return
        _state.update { it.copy(isLoading = true, errorMessage = null) }
        lastSessionLoad = System.currentTimeMillis()
        try {
            val loaded = api.drivers(sessionId)
            if (current.selectedSessionId != sessionId) return
            _state.update { it.copy(drivers = loaded) }
        } catch (e: Exception) {
            if (current.selectedSessionId != sessionId) return
            _state.update { it.copy(errorMessage = e.message) }
        }
        _state.update { it.copy(isLoading = false) }
    }

    // The automatic reload: no spinner, and a failure leaves the last results
    // up rather than replacing a live board with an error every few seconds.
    // Driver and compare pages are addressed by car number, so they follow
    // their drivers to whatever positions the new times give them.
    suspend fun refreshSessionData() {
        lastSessionLoad = System.currentTimeMillis()
        val sessionId = current.selectedSessionId ?: return
        if (current.isLoading) return
        val eventId = current.selectedEventId
        // Speedhive adds sessions through the day; GGLC has only the one.
        if (eventId != null && eventId > 0) {
            val fetched = runCatching { api.sessions(eventId) }.getOrNull()
            if (fetched != null && current.selectedEventId == eventId && fetched != current.sessions) {
                _state.update { it.copy(sessions = fetched) }
            }
        }
        val loaded = runCatching { api.drivers(sessionId) }.getOrNull() ?: return
        if (current.selectedSessionId != sessionId) return
        _state.update { it.copy(drivers = loaded, errorMessage = null) }
    }

    fun openLeaderboard(courseId: Int) {
        _state.update { it.copy(openCourseId = courseId, leaderboardError = null, leaderboardDetail = null) }
        viewModelScope.launch {
            try {
                val detail = api.leaderboardCourse(courseId)
                if (current.openCourseId != courseId) return@launch
                _state.update { it.copy(leaderboardDetail = detail) }
            } catch (e: Exception) {
                if (current.openCourseId != courseId) return@launch
                _state.update { it.copy(leaderboardError = e.message) }
            }
        }
    }

    // The last list stays up while it reloads; the board changes rarely.
    fun loadAcceleration() {
        _state.update { it.copy(accelerationError = null) }
        viewModelScope.launch {
            try {
                val entries = api.acceleration()
                _state.update { it.copy(accelerationEntries = entries) }
            } catch (e: Exception) {
                _state.update { it.copy(accelerationError = e.message) }
            }
        }
    }

    suspend fun joinCourse(code: String) {
        val course = api.joinCourse(code)
        editSettings { it.copy(hiddenCourseIds = it.hiddenCourseIds - course.id) }
        refreshLeaderboardEvents()
        navChannel.trySend(NavCommand.OpenLeaderboard(course.id))
    }

    suspend fun leaveCourse(id: Int) {
        api.leaveCourse(id)
        refreshLeaderboardEvents()
        navChannel.trySend(NavCommand.LeaveLeaderboard(id))
    }

    fun hideCourse(id: Int) {
        editSettings { it.copy(hiddenCourseIds = it.hiddenCourseIds + id) }
        _state.update { s -> s.copy(events = s.events.filter { it.id != AppState.leaderboardEventId(id) }) }
        navChannel.trySend(NavCommand.LeaveLeaderboard(id))
    }

    fun unhideAllCourses() {
        editSettings { it.copy(hiddenCourseIds = emptySet()) }
        viewModelScope.launch { refreshLeaderboardEvents() }
    }

    suspend fun report(target: LBReportTarget, reason: String) = api.report(target, reason)

    // The server stops sending anything the blocked poster owns, so the open
    // board is reloaded, or left if it was theirs.
    suspend fun blockPoster(target: LBReportTarget, courseId: Int) {
        val block = api.block(target)
        _state.update { s -> s.copy(blocks = listOf(block) + s.blocks.filter { it.id != block.id }) }
        refreshLeaderboardEvents()
        if (current.leaderboardCourses.any { it.id == courseId }) {
            navChannel.trySend(NavCommand.BackToLeaderboard(courseId))
            openLeaderboard(courseId)
        } else {
            navChannel.trySend(NavCommand.LeaveLeaderboard(courseId))
        }
    }

    suspend fun blockAccelerationPoster(entryId: Int) {
        val block = api.block(LBReportTarget(LBReportType.ACCELERATION, entryId))
        _state.update { s ->
            s.copy(
                blocks = listOf(block) + s.blocks.filter { it.id != block.id },
                accelerationEntries = s.accelerationEntries?.filter { it.id != entryId },
            )
        }
        navChannel.trySend(NavCommand.BackToAcceleration)
        loadAcceleration()
        refreshLeaderboardEvents()
    }

    suspend fun unblock(id: Int) {
        api.unblock(id)
        _state.update { s -> s.copy(blocks = s.blocks.filter { it.id != id }) }
        refreshLeaderboardEvents()
    }

    fun loadBlocks() {
        viewModelScope.launch {
            runCatching { api.blocks() }.getOrNull()?.let { blocks -> _state.update { it.copy(blocks = blocks) } }
        }
    }
}
