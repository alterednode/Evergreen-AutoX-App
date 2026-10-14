package com.romangarms.autoxlive.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(message: String, val status: Int? = null) : Exception(message)

private interface AutoXService {
    @GET("api/orgs/{id}")
    suspend fun org(@Path("id") orgId: Int): SHOrg

    @GET("api/orgs/{id}/events")
    suspend fun events(@Path("id") orgId: Int, @Query("limit") limit: Int): List<SHEvent>

    @GET("api/events/{id}/sessions")
    suspend fun sessions(@Path("id") eventId: Int): List<SHSession>

    @GET("api/sessions/{id}/results")
    suspend fun results(@Path("id") sessionId: Int): List<SHResultRow>

    @GET("api/sessions/{id}/laps")
    suspend fun laps(@Path("id") sessionId: Int): List<SHLapsRow>

    @GET("api/gglc/events")
    suspend fun gglcEvents(@Query("year") year: Int): List<GGLCEventStub>

    @GET("api/gglc/events/{date}")
    suspend fun gglcEvent(@Path("date") date: String): GGLCEvent

    @GET("api/leaderboard/courses")
    suspend fun leaderboardCourses(): List<LBCourse>

    @GET("api/leaderboard/courses/{id}")
    suspend fun leaderboardCourse(@Path("id") id: Int): LBCourseDetail

    @GET("api/acceleration")
    suspend fun acceleration(): List<AccelEntry>

    @POST("api/leaderboard/join")
    suspend fun joinCourse(@Body input: LBJoinInput): LBCourse

    @DELETE("api/leaderboard/courses/{id}/membership")
    suspend fun leaveCourse(@Path("id") id: Int): Deleted

    @POST("api/leaderboard/reports")
    suspend fun report(@Body input: LBReportInput): Created

    @POST("api/leaderboard/blocks")
    suspend fun block(@Body input: LBBlockInput): LBBlock

    @GET("api/leaderboard/blocks")
    suspend fun blocks(): List<LBBlock>

    @DELETE("api/leaderboard/blocks/{id}")
    suspend fun unblock(@Path("id") id: Int): Deleted

    @GET("api/android/release")
    suspend fun androidRelease(): AndroidRelease
}

class ApiClient(baseUrl: String, private val deviceToken: String) {
    val baseUrl: String = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    // Short timeouts so an unreachable server fails fast at launch.
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    // The update check deliberately carries no device token; the privacy page
    // promises it identifies nobody.
    private val anonymous = retrofit(http)
    private val authed = http.newBuilder()
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $deviceToken").build())
        }
        .build()
    private val service = retrofit(authed)

    // GGLC pages are scraped on demand server-side; a cold fetch can outlast
    // the fast-fail timeout, and timing out midway leaves stale results up.
    private val gglcService = retrofit(
        authed.newBuilder().readTimeout(30, TimeUnit.SECONDS).build(),
    )

    private fun retrofit(client: OkHttpClient): AutoXService =
        Retrofit.Builder()
            .baseUrl(this.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AutoXService::class.java)

    suspend fun org(orgId: Int) = call { service.org(orgId) }
    suspend fun events(orgId: Int, limit: Int = 200) = call { service.events(orgId, limit) }
    suspend fun sessions(eventId: Int) = call { service.sessions(eventId) }
    suspend fun gglcEvents(year: Int) = call { gglcService.gglcEvents(year) }
    suspend fun leaderboardCourses() = call { service.leaderboardCourses() }
    suspend fun leaderboardCourse(id: Int) = call { service.leaderboardCourse(id) }
    suspend fun acceleration() = call { service.acceleration() }
    suspend fun joinCourse(code: String) = call { service.joinCourse(LBJoinInput(code)) }
    suspend fun leaveCourse(id: Int) = call { service.leaveCourse(id) }
    suspend fun report(target: LBReportTarget, reason: String) = call { service.report(LBReportInput(target, reason)) }
    suspend fun block(target: LBReportTarget) = call { service.block(LBBlockInput(target)) }
    suspend fun blocks() = call { service.blocks() }
    suspend fun unblock(id: Int) = call { service.unblock(id) }
    suspend fun androidRelease() = call { anonymous.androidRelease() }

    suspend fun drivers(sessionId: Int): List<Driver> {
        if (sessionId < 0) {
            val event = call { gglcService.gglcEvent(SessionResults.gglcDate(sessionId)) }
            return SessionResults.gglcDrivers(event)
        }
        return coroutineScope {
            val results = async { call { service.results(sessionId) } }
            val laps = async { call { service.laps(sessionId) } }
            SessionResults.speedhiveDrivers(results.await(), laps.await())
        }
    }

    fun resolve(path: String): String =
        if (path.startsWith("http")) path else baseUrl + path.removePrefix("/")

    private suspend fun <T> call(block: suspend () -> T): T =
        try {
            block()
        } catch (e: HttpException) {
            val body = e.response()?.errorBody()?.string()
            throw ApiException(errorMessage(body) ?: "Server returned ${e.code()}", e.code())
        } catch (e: IOException) {
            throw ApiException(e.message ?: "Network error")
        }

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }

        // FastAPI puts a string in `detail` for our own errors and a list of
        // field errors for validation failures.
        fun errorMessage(body: String?): String? {
            val obj = runCatching { json.parseToJsonElement(body ?: return null) as? JsonObject }.getOrNull()
                ?: return null
            when (val detail = obj["detail"]) {
                is JsonPrimitive -> return detail.contentOrNull
                is JsonArray -> {
                    val messages = detail.mapNotNull { error ->
                        val e = error as? JsonObject ?: return@mapNotNull null
                        val message = e["msg"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val field = (e["loc"] as? JsonArray)?.lastOrNull()?.jsonPrimitive?.contentOrNull
                        if (field != null) "$field: $message" else message
                    }
                    if (messages.isNotEmpty()) return messages.joinToString("\n")
                }
                else -> {}
            }
            return null
        }
    }
}
