package `fun`.eternalblue.service

import `fun`.eternalblue.data.AccountOptions
import `fun`.eternalblue.net.ApiClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals

class CheckinRoundResilienceTest {
    @Test
    fun malformedCourseDoesNotStopLaterCoursesFromBeingChecked() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setBody(
                    """{"status":true,"clazzCourses":[
                       {"id":"bad","course":{"name":"异常课程"}},
                       {"id":"good","course":{"name":"正常课程"}}
                    ]}"""
                )
            )
            server.enqueue(MockResponse().setBody("""not-json"""))
            server.enqueue(
                MockResponse().setBody(
                    """{"status":true,"checkin":{"id":"c1","type":"CLOCKIN"}}"""
                )
            )

            val baseUrl = server.url("/").toString().removeSuffix("/")
            val service = AutoSignService(
                options = AccountOptions(
                    label = "测试账户",
                    account = "user-1",
                    password = "secret-1",
                    autoCheckin = false
                ),
                api = ApiClient(OkHttpClient()),
                coreApiBaseUrl = baseUrl
            )
            val session = service.createTestSession(
                userId = "user-1",
                accessId = "access-1",
                accessSecret = "secret-1",
                lastSecUpdateTsS = "1700000000"
            )

            runBlocking { service.checkRound(session) }

            assertEquals(3, server.requestCount)
            assertEquals("/ccs/joined", server.takeRequest().path)
            assertEquals("/ccs/bad/checkins/current", server.takeRequest().path)
            assertEquals("/ccs/good/checkins/current", server.takeRequest().path)
        }
    }
}
