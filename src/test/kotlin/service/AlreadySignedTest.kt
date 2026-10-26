package `fun`.eternalblue.service

import `fun`.eternalblue.data.AppConfig
import `fun`.eternalblue.net.ApiClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals

class AlreadySignedTest {
    @Test
    fun doesNotSubmitWhenServerReportsCheckinFlagY() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setBody(
                    """{"status":true,"clazzCourses":[
                       {"id":"course-1","course":{"name":"已签到课程"}}
                    ]}"""
                )
            )
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"status":true,"checkin":{"id":-8368735,"type":"CLOCKIN",
                     "checkinFlag":"Y","checkinTime":"2026-10-26 13:10:24"}}
                    """.trimIndent()
                )
            )

            val baseUrl = server.url("/").toString().removeSuffix("/")
            val service = AutoSignService(
                options = AppConfig(autoCheckin = true),
                api = ApiClient(OkHttpClient()),
                coreApiBaseUrl = baseUrl,
                clockinApiUrl = "$baseUrl/cc_clockin/clockin",
                normalCheckinApiUrl = "$baseUrl/checkin"
            )
            val session = service.createTestSession(
                userId = "user-1",
                accessId = "access-1",
                accessSecret = "secret-1",
                lastSecUpdateTsS = "1700000000"
            )

            runBlocking { service.checkRound(session) }

            // Only the course list and status request; no duplicate check-in submission.
            assertEquals(2, server.requestCount)
        }
    }
}
