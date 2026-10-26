package `fun`.eternalblue.service

import `fun`.eternalblue.data.AccountOptions
import `fun`.eternalblue.data.AppConfig
import `fun`.eternalblue.net.ApiClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PollingIntervalTest {
    @Test
    fun configuredIntervalIncludesTimeSpentCheckingCourses() = runTest {
        val file = Files.createTempFile("autosign-interval", ".toml").toFile()
        try {
            file.writeText("check_interval = 20")
            MockWebServer().use { server ->
                server.start()
                repeat(3) { enqueueRound(server, 1) }
                val loaded = AppConfig.load(emptyArray(), file)
                val service = service(server, options(loaded.intervalSeconds))
                val session = session(service)
                val polling = launch { service.runPolling(session) { testScheduler.currentTime } }
                try {
                    runCurrent()
                    assertEquals(2, server.requestCount)
                    advanceTimeBy(19_999)
                    runCurrent()
                    assertEquals(2, server.requestCount)
                    advanceTimeBy(1)
                    runCurrent()
                    assertEquals(4, server.requestCount)
                    advanceTimeBy(20_000)
                    runCurrent()
                    assertEquals(6, server.requestCount)
                } finally {
                    polling.cancelAndJoin()
                }
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun longRoundStartsNextRoundImmediatelyWithoutOverlapping() = runTest {
        MockWebServer().use { server ->
            server.start()
            repeat(2) { enqueueRound(server, 41) }
            val service = service(server, options(intervalSeconds = 10))
            val session = session(service)
            val polling = launch { service.runPolling(session) { testScheduler.currentTime } }
            try {
                runCurrent()
                advanceTimeBy(10_000)
                runCurrent()
                assertTrue(server.requestCount < 42)
                advanceTimeBy(2_300)
                runCurrent()
                // 41 courses take 12.3 seconds; the next courses request is sent immediately.
                assertEquals(44, server.requestCount)
            } finally {
                polling.cancelAndJoin()
            }
        }
    }

    @Test
    fun onceReturnsWithoutWaitingForTheInterval() = runTest {
        MockWebServer().use { server ->
            server.start()
            enqueueRound(server, 1)
            val service = service(server, options(intervalSeconds = 20, once = true))
            assertEquals(0, service.runPolling(session(service)) { testScheduler.currentTime })
            assertEquals(300, testScheduler.currentTime)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun debugModeUsesShorterCourseDelay() = runTest {
        MockWebServer().use { server ->
            server.start()
            enqueueRound(server, 1)
            val service = service(server, options(debug = true, once = true))
            assertEquals(0, service.runPolling(session(service)) { testScheduler.currentTime })
            assertEquals(AppConfig.DEBUG_COURSE_DELAY_MILLIS, testScheduler.currentTime)
        }
    }

    private fun enqueueRound(server: MockWebServer, courseCount: Int) {
        val courses = (1..courseCount).joinToString(",") { """{"id":"course-$it"}""" }
        server.enqueue(MockResponse().setBody("""{"status":true,"clazzCourses":[$courses]}"""))
        repeat(courseCount) {
            server.enqueue(MockResponse().setBody("""{"status":false}"""))
        }
    }

    private fun options(
        intervalSeconds: Long = AppConfig.DEFAULT_INTERVAL_SECONDS,
        once: Boolean = false,
        debug: Boolean = false
    ) = AccountOptions(
        label = "测试账户",
        account = "test-user",
        password = "test-secret",
        intervalSeconds = intervalSeconds,
        once = once,
        debug = debug
    )

    private fun service(server: MockWebServer, options: AccountOptions) = AutoSignService(
        options = options,
        api = ApiClient(OkHttpClient()),
        coreApiBaseUrl = server.url("/").toString().removeSuffix("/")
    )

    private fun session(service: AutoSignService) = service.createTestSession(
        userId = "test-user",
        accessId = "test-access",
        accessSecret = "test-secret",
        lastSecUpdateTsS = "1700000000"
    )
}
