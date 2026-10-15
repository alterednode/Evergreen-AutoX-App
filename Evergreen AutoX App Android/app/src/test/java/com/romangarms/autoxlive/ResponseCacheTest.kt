package com.romangarms.autoxlive

import com.romangarms.autoxlive.data.ApiClient
import com.romangarms.autoxlive.data.ApiException
import com.romangarms.autoxlive.data.ResponseCache
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResponseCacheTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun keptAnswersOutliveTheServer() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""[{"id": 11, "name": "Morning"}]"""))
        server.enqueue(MockResponse().setBody("[]"))
        server.start()
        val client = ApiClient(server.url("/").toString(), "token", ResponseCache(folder.root))
        client.sessions(1)
        client.blocks()
        server.shutdown()

        val failure = runCatching { client.sessions(1) }.exceptionOrNull() as ApiException
        assertTrue(failure.unreachable)
        val saved = client.fromCache { sessions(1) }
        assertNotNull(saved)
        assertEquals(listOf(11), saved!!.value.map { it.id })
        assertTrue(saved.fetchedAt > 0)
        assertNull(client.fromCache { sessions(2) })
        assertNull(client.fromCache { blocks() })
    }

    @Test
    fun answersAreKeptPerServer() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("[]"))
        server.start()
        val cache = ResponseCache(folder.root)
        ApiClient(server.url("/").toString(), "token", cache).sessions(1)
        server.shutdown()
        assertNull(ApiClient("http://127.0.0.1:1/", "token", cache).fromCache { sessions(1) })
    }
}
