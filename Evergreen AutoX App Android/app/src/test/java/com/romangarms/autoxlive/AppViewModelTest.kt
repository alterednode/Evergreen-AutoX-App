package com.romangarms.autoxlive

import com.romangarms.autoxlive.data.AccelEntry
import com.romangarms.autoxlive.data.ApiException
import com.romangarms.autoxlive.data.Driver
import com.romangarms.autoxlive.data.GGLCEventStub
import com.romangarms.autoxlive.data.LBBlock
import com.romangarms.autoxlive.data.LBCourse
import com.romangarms.autoxlive.data.LBCourseDetail
import com.romangarms.autoxlive.data.LBReportTarget
import com.romangarms.autoxlive.data.Run
import com.romangarms.autoxlive.data.SHEvent
import com.romangarms.autoxlive.data.SHOrg
import com.romangarms.autoxlive.data.SHSession
import com.romangarms.autoxlive.data.Saved
import com.romangarms.autoxlive.data.SettingsStore
import com.romangarms.autoxlive.data.StoredSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

private fun driver(position: Int, number: String, best: Double) = Driver(
    position = position,
    name = "Driver $number",
    startNumber = number,
    carClass = null,
    positionInClass = null,
    runs = listOf(Run(number = 1, lapNumber = 1, seconds = best)),
    officialRunNumber = null,
    bestTimeString = null,
)

private class FakeApi : AutoXApi {
    val events = mutableListOf<SHEvent>()
    val sessionsByEvent = mutableMapOf<Int, List<SHSession>>()
    val gates = mutableMapOf<Int, CompletableDeferred<Unit>>()
    val driversBySession = mutableMapOf<Int, List<Driver>>()
    var failDrivers = false
    var offline = false
    var saved: FakeApi? = null
    var savedAt = 0L

    private fun reach() {
        if (offline) throw ApiException("Unable to resolve host")
    }

    override suspend fun org(orgId: Int) = reach().let { SHOrg(orgId, "Org") }
    override suspend fun events(orgId: Int, limit: Int) = reach().let { events.toList() }
    override suspend fun sessions(eventId: Int): List<SHSession> {
        reach()
        gates[eventId]?.await()
        return sessionsByEvent[eventId] ?: emptyList()
    }
    override suspend fun drivers(sessionId: Int): List<Driver> {
        reach()
        if (failDrivers) error("offline")
        return driversBySession[sessionId] ?: emptyList()
    }
    override suspend fun <T> fromCache(load: suspend AutoXApi.() -> T): Saved<T>? =
        saved?.let { Saved(it.load(), savedAt) }

    // What a phone that loaded everything once has on disk.
    fun snapshot(at: Long) = FakeApi().also {
        it.events += events
        it.sessionsByEvent += sessionsByEvent
        it.driversBySession += driversBySession
        saved = it
        savedAt = at
    }
    override suspend fun gglcEvents(year: Int) = emptyList<GGLCEventStub>()
    override suspend fun leaderboardCourses() = emptyList<LBCourse>()
    override suspend fun leaderboardCourse(id: Int): LBCourseDetail = error("unused")
    override suspend fun acceleration() = emptyList<AccelEntry>()
    override suspend fun joinCourse(code: String): LBCourse = error("unused")
    override suspend fun leaveCourse(id: Int) {}
    override suspend fun report(target: LBReportTarget, reason: String) {}
    override suspend fun block(target: LBReportTarget): LBBlock = error("unused")
    override suspend fun blocks() = emptyList<LBBlock>()
    override suspend fun unblock(id: Int) {}
}

private class MemoryStore(var settings: StoredSettings = StoredSettings()) : SettingsStore {
    override suspend fun load() = settings
    override suspend fun save(settings: StoredSettings) { this.settings = settings }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val today = LocalDate.of(2026, 10, 10)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun api() = FakeApi().apply {
        events += SHEvent(1, "AutoX 1", "2026-10-10")
        events += SHEvent(2, "AutoX 2", "2026-09-01")
        sessionsByEvent[1] = listOf(SHSession(11, "Morning", startTime = "2026-10-10T09:00:00"))
        sessionsByEvent[2] = listOf(SHSession(21, "Afternoon", startTime = "2026-09-01T13:00:00"))
        driversBySession[11] = listOf(driver(1, "7", 45.0), driver(2, "12", 46.0))
        driversBySession[21] = listOf(driver(1, "99", 50.0))
    }

    @Test
    fun launchOpensNewestAutoXEventWithoutRememberingIt() = runTest(dispatcher) {
        val store = MemoryStore()
        val vm = AppViewModel(api(), store) { today }
        vm.start()
        advanceUntilIdle()
        assertEquals(1, vm.state.value.selectedEventId)
        assertEquals(11, vm.state.value.selectedSessionId)
        assertEquals(2, vm.state.value.drivers.size)
        assertNull(store.settings.selectedEventId)
    }

    @Test
    fun aSlowLoadForAnAbandonedEventLeavesTheNewOneAlone() = runTest(dispatcher) {
        val api = api()
        val vm = AppViewModel(api, MemoryStore()) { today }
        vm.start()
        advanceUntilIdle()

        val gate = CompletableDeferred<Unit>()
        api.gates[1] = gate
        val slow = launch { vm.selectEvent(1) }
        advanceUntilIdle()
        vm.selectEvent(2)
        assertEquals(listOf(21), vm.state.value.sessions.map { it.id })

        gate.complete(Unit)
        slow.join()
        assertEquals(2, vm.state.value.selectedEventId)
        assertEquals(listOf(21), vm.state.value.sessions.map { it.id })
        assertEquals(listOf("99"), vm.state.value.drivers.map { it.startNumber })
    }

    @Test
    fun openedEventsAreRemembered() = runTest(dispatcher) {
        val store = MemoryStore()
        val vm = AppViewModel(api(), store) { today }
        vm.start()
        advanceUntilIdle()
        vm.openEvent(2)
        advanceUntilIdle()
        assertEquals(2, store.settings.selectedEventId)
    }

    @Test
    fun autoRefreshFailureKeepsTheLastResults() = runTest(dispatcher) {
        val api = api()
        val vm = AppViewModel(api, MemoryStore()) { today }
        vm.start()
        advanceUntilIdle()
        api.failDrivers = true
        vm.refreshSessionData()
        assertEquals(2, vm.state.value.drivers.size)
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun driverPagesFollowTheirCarAfterARefresh() = runTest(dispatcher) {
        val api = api()
        val vm = AppViewModel(api, MemoryStore()) { today }
        vm.start()
        advanceUntilIdle()
        assertEquals(2, vm.state.value.driver("12")!!.position)
        api.driversBySession[11] = listOf(driver(1, "12", 44.0), driver(2, "7", 45.0))
        vm.refreshSessionData()
        assertEquals(1, vm.state.value.driver("12")!!.position)
    }

    @Test
    fun pinsAndMeAreScopedToTheEvent() = runTest(dispatcher) {
        val vm = AppViewModel(api(), MemoryStore()) { today }
        vm.start()
        advanceUntilIdle()
        vm.togglePin("12")
        vm.setMe("7")
        assertEquals(listOf("7", "12"), vm.state.value.friends.map { it.startNumber })
        vm.selectEvent(2)
        assertTrue(vm.state.value.pins.isEmpty())
        assertNull(vm.state.value.meNumber)
    }

    @Test
    fun liveRefreshOnlyForRecentEventsOnResultsPages() = runTest(dispatcher) {
        val vm = AppViewModel(api(), MemoryStore()) { today }
        vm.start()
        advanceUntilIdle()
        assertTrue(vm.state.value.wantsLiveRefresh(today))
        assertTrue(vm.state.value.wantsLiveRefresh(today.plusDays(1)))
        assertFalse(vm.state.value.wantsLiveRefresh(today.plusDays(2)))
        vm.setRoute(RouteKind.EVENTS)
        assertFalse(vm.state.value.wantsLiveRefresh(today))
        vm.setRoute(RouteKind.DRIVER)
        assertTrue(vm.state.value.wantsLiveRefresh(today))
    }

    @Test
    fun launchingOfflineShowsTheSavedResultsAsOld() = runTest(dispatcher) {
        val api = api().apply {
            snapshot(at = 1_000L)
            offline = true
        }
        val vm = AppViewModel(api, MemoryStore()) { today }
        vm.start()
        advanceUntilIdle()
        assertEquals(1, vm.state.value.selectedEventId)
        assertEquals(2, vm.state.value.drivers.size)
        assertNull(vm.state.value.errorMessage)
        assertEquals(1_000L, vm.state.value.staleSince)
        vm.setRoute(RouteKind.EVENTS)
        assertEquals(1_000L, vm.state.value.staleSince)
        vm.setRoute(RouteKind.SETUP)
        assertNull(vm.state.value.staleSince)
    }

    @Test
    fun launchingOfflineWithNothingSavedStillSaysSo() = runTest(dispatcher) {
        val api = api().apply { offline = true }
        val vm = AppViewModel(api, MemoryStore()) { today }
        vm.start()
        advanceUntilIdle()
        assertTrue(vm.state.value.events.isEmpty())
        assertEquals("Unable to resolve host", vm.state.value.errorMessage)
        assertNull(vm.state.value.staleSince)
    }

    @Test
    fun aFailedLiveRefreshMarksTheResultsOldUntilOneSucceeds() = runTest(dispatcher) {
        val api = api()
        val vm = AppViewModel(api, MemoryStore()) { today }
        vm.start()
        advanceUntilIdle()
        assertNull(vm.state.value.staleSince)
        val fetchedAt = vm.state.value.driversAge!!.fetchedAt

        api.offline = true
        vm.refreshSessionData()
        assertEquals(2, vm.state.value.drivers.size)
        assertEquals(fetchedAt, vm.state.value.staleSince)

        api.offline = false
        vm.retryStale()
        advanceUntilIdle()
        assertNull(vm.state.value.staleSince)
        assertFalse(vm.state.value.isRetrying)
    }

    @Test
    fun anErrorFromAReachableServerDoesNotFallBackToSavedResults() = runTest(dispatcher) {
        val api = api().apply {
            snapshot(at = 1_000L)
            failDrivers = true
        }
        val vm = AppViewModel(api, MemoryStore()) { today }
        vm.start()
        advanceUntilIdle()
        assertTrue(vm.state.value.drivers.isEmpty())
        assertEquals("offline", vm.state.value.errorMessage)
    }

    @Test
    fun staleLabelShowsTheDayOnlyWhenItIsNotToday() {
        val zone = java.time.ZoneOffset.UTC
        val millis = java.time.LocalDateTime.of(2026, 10, 9, 14, 41).toInstant(zone).toEpochMilli()
        assertFalse(AppState.staleLabel(millis, zone, LocalDate.of(2026, 10, 9)).contains("Oct"))
        assertTrue(AppState.staleLabel(millis, zone, LocalDate.of(2026, 10, 10)).startsWith("Oct 9"))
    }
}
