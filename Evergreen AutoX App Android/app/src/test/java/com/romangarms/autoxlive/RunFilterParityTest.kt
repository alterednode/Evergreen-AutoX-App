package com.romangarms.autoxlive

import com.romangarms.autoxlive.data.ApiClient
import com.romangarms.autoxlive.data.GGLCEvent
import com.romangarms.autoxlive.data.SHLap
import com.romangarms.autoxlive.data.SHLapsRow
import com.romangarms.autoxlive.data.SHResultRow
import com.romangarms.autoxlive.data.SessionResults
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

// The same cases run against server/notify.py (tests/test_run_filter_parity.py).
class RunFilterParityTest {
    private val cases: JsonObject = ApiClient.json.parseToJsonElement(
        File("../../tests/data/run_filter_cases.json").readText(),
    ).jsonObject

    @Test
    fun speedhiveRunFilter() {
        for (case in cases["speedhive"]!!.jsonArray) {
            val obj = case.jsonObject
            val laps = obj["laps"]!!.jsonArray.mapIndexed { index, lap ->
                SHLap(lap = index + 1, lapTime = (lap as? JsonNull)?.let { null } ?: lap.jsonPrimitive.contentOrNull)
            }
            val drivers = SessionResults.speedhiveDrivers(
                listOf(SHResultRow(position = 1, name = "Driver", startNumber = "1")),
                listOf(SHLapsRow(position = 1, laps = laps)),
            )
            val expected = obj["runs"]!!.jsonArray.map { it.jsonPrimitive.doubleOrNull!! }
            assertEquals(obj["about"]!!.jsonPrimitive.content, expected, drivers.single().runs.map { it.seconds })
        }
    }

    @Test
    fun gglcRanking() {
        val gglc = cases["gglc"]!!.jsonObject
        val event = ApiClient.json.decodeFromJsonElement(GGLCEvent.serializer(), gglc["event"]!!)
        val drivers = SessionResults.gglcDrivers(event)
        val expected = (gglc["ranked"] as JsonArray).map { it.jsonObject }
        assertEquals(expected.size, drivers.size)
        for ((driver, want) in drivers.zip(expected)) {
            assertEquals(want["position"]!!.jsonPrimitive.int, driver.position)
            assertEquals(want["number"]!!.jsonPrimitive.content, driver.startNumber)
            assertEquals(want["name"]!!.jsonPrimitive.content, driver.name)
            assertEquals(want["class"]!!.jsonPrimitive.content, driver.carClass)
            assertEquals(want["class_position"]!!.jsonPrimitive.int, driver.positionInClass)
            assertEquals(want["best"]!!.jsonPrimitive.doubleOrNull, driver.best)
        }
    }
}
