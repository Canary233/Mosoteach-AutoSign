package `fun`.eternalblue.service

import `fun`.eternalblue.data.AccountConfig
import `fun`.eternalblue.data.AppConfig
import `fun`.eternalblue.net.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultiAccountRunTest {
    @Test
    fun accountsLoginAndCheckInIndependently() {
        val submittedBy = CopyOnWriteArrayList<String>()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/passports/account-login") ->
                            MockResponse().setBody("""{"status":false}""")
                        path.contains("/passport/login") -> legacyLogin(request)
                        path == "/ccs/joined" -> {
                            val user = accessId(request).removePrefix("access-")
                            MockResponse().setBody(
                                """{"status":true,"clazzCourses":[
                                   {"id":"course-$user","course":{"name":"课程$user"}}
                                ]}"""
                            )
                        }
                        path.contains("/checkins/current") -> {
                            val user = accessId(request).removePrefix("access-")
                            MockResponse().setBody(
                                """{"status":true,"checkin":{
                                   "id":"checkin-$user","type":"CLOCKIN","checkinFlag":"N"
                                }}"""
                            )
                        }
                        path == "/cc_clockin/clockin" -> {
                            submittedBy += request.getHeader("X-mssvc-access-id").orEmpty()
                            MockResponse().setBody("""{"result_code":0,"result_msg":"OK"}""")
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()

            val baseUrl = server.url("/").toString().removeSuffix("/")
            val accounts = AppConfig(
                accounts = listOf(
                    AccountConfig("alice", "pass-a", "甲"),
                    AccountConfig("bob", "pass-b", "乙")
                ),
                once = true
            ).effectiveAccounts()

            val results = runBlocking {
                accounts.map { account ->
                    async(Dispatchers.IO) {
                        AutoSignService(
                            options = account,
                            api = ApiClient(OkHttpClient()),
                            coreApiBaseUrl = baseUrl,
                            legacyLoginApiUrl = "$baseUrl/passport/login",
                            clockinApiUrl = "$baseUrl/cc_clockin/clockin",
                            normalCheckinApiUrl = "$baseUrl/checkin"
                        ).run()
                    }
                }.awaitAll()
            }

            assertEquals(listOf(0, 0), results)
            assertEquals(2, submittedBy.size)
            assertTrue(submittedBy.containsAll(listOf("access-alice", "access-bob")))
        }
    }

    @Test
    fun oneAccountFailingDoesNotStopOthers() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/passports/account-login") ->
                            MockResponse().setBody("""{"status":false}""")
                        path.contains("/passport/login") -> legacyLogin(request)
                        path == "/ccs/joined" ->
                            MockResponse().setBody("""{"status":true,"clazzCourses":[]}""")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()

            val baseUrl = server.url("/").toString().removeSuffix("/")
            val accounts = AppConfig(
                accounts = listOf(
                    AccountConfig("broken", "bad-pass", "坏账号"),
                    AccountConfig("good", "good-pass", "好账号")
                ),
                once = true
            ).effectiveAccounts()

            val results = runBlocking {
                accounts.map { account ->
                    async(Dispatchers.IO) {
                        runCatching {
                            AutoSignService(
                                options = account,
                                api = ApiClient(OkHttpClient()),
                                coreApiBaseUrl = baseUrl,
                                legacyLoginApiUrl = "$baseUrl/passport/login"
                            ).run()
                        }.getOrElse { 1 }
                    }
                }.awaitAll()
            }

            assertEquals(listOf(1, 0), results)
        }
    }

    private fun legacyLogin(request: RecordedRequest): MockResponse {
        val body = request.body.readUtf8()
        val account = Regex("(?:^|&)account_name=([^&]*)")
            .find(body)?.groupValues?.get(1).orEmpty()
        if (account == "broken") {
            return MockResponse().setResponseCode(401)
                .setBody("""{"status":false,"errorMessage":"Unauthorized"}""")
        }
        return MockResponse().setBody(
            """
            {"status":true,"user":{
              "userId":"user-$account","accessId":"access-$account",
              "accessSecret":"secret-$account","fullName":"$account",
              "lastSecUpdateTsS":"1700000000"
            }}
            """.trimIndent()
        )
    }

    private fun accessId(request: RecordedRequest): String =
        request.getHeader("X-access-id").orEmpty()
}
