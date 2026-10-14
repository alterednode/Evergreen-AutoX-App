package com.romangarms.autoxlive

import com.romangarms.autoxlive.data.ApiClient
import com.romangarms.autoxlive.data.LBCourse
import com.romangarms.autoxlive.data.LBEntry
import com.romangarms.autoxlive.data.LBRun
import com.romangarms.autoxlive.data.LapTime
import com.romangarms.autoxlive.data.SHEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    @Test
    fun lapTimeParsesEveryServerFormat() {
        assertEquals(45.123, LapTime.seconds("45.123")!!, 1e-9)
        assertEquals(83.5, LapTime.seconds("1:23.500")!!, 1e-9)
        assertEquals(3650.0, LapTime.seconds("1:00:50")!!, 1e-9)
        assertEquals(3650.25, LapTime.seconds("1:00:50.250")!!, 1e-9)
        assertNull(LapTime.seconds(""))
        assertNull(LapTime.seconds("DNF"))
    }

    @Test
    fun lapTimeFormatsLikeIOS() {
        assertEquals("45.123", LapTime.format(45.123))
        assertEquals("1:05.000", LapTime.format(65.0))
        assertEquals("+0.25", LapTime.gap(0.25))
        assertEquals("-1.50", LapTime.gap(-1.5))
    }

    @Test
    fun courseDecodesWithoutOwnershipFields() {
        val course = ApiClient.json.decodeFromString<LBCourse>("""{"id": 4, "name": "Hill", "region": "CA"}""")
        assertFalse(course.isOwner)
        assertFalse(course.hasOwner)
        assertNull(course.joinCode)
    }

    @Test
    fun eventsDecodeAsSpeedhive() {
        val event = ApiClient.json.decodeFromString<SHEvent>("""{"id": 9, "name": "AutoX 3", "startDate": "2026-09-01"}""")
        assertEquals(com.romangarms.autoxlive.data.EventSource.SPEEDHIVE, event.source)
    }

    @Test
    fun leaderboardEntriesGroupByDriverAndCar() {
        fun run(id: Int, driver: String, vehicle: String?, seconds: Double) = LBRun(
            id = id, driver = driver, vehicle = vehicle, time = "", adjustedSeconds = seconds,
            adjustedTime = "", legacy = false,
        )
        val entries = LBEntry.entries(
            listOf(
                run(1, "Ann", "Miata", 60.0),
                run(2, "Ann", " miata ", 58.0),
                run(3, "Ann", "S2000", 59.0),
                run(4, "Bob", null, 57.0),
            ),
        )
        assertEquals(listOf(4, 2, 3), entries.map { it.best.id })
        assertEquals(listOf(1, 2, 3), entries.map { it.id })
        assertEquals(2, entries[1].runs.size)
    }

    @Test
    fun fastApiErrorsBecomeMessages() {
        assertEquals("Not found", ApiClient.errorMessage("""{"detail": "Not found"}"""))
        assertEquals(
            "code: too short",
            ApiClient.errorMessage("""{"detail": [{"loc": ["body", "code"], "msg": "too short"}]}"""),
        )
        assertNull(ApiClient.errorMessage("<html>"))
    }

    @Test
    fun negativeIdsRoundTrip() {
        assertEquals(-20260801, AppState.gglcEventId("2026-08-01"))
        assertEquals("2026-08-01", com.romangarms.autoxlive.data.SessionResults.gglcDate(-20260801))
        assertEquals(17, AppState.leaderboardCourseId(AppState.leaderboardEventId(17)))
        assertTrue(AppState.leaderboardEventId(1) < -99_999_999)
    }
}
