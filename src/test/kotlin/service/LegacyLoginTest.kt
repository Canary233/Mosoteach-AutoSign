package `fun`.eternalblue.service

import `fun`.eternalblue.data.AppConfig
import `fun`.eternalblue.net.MobileProtocol
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LegacyLoginTest {
    @Test
    fun sendsLegacyFormLoginAndReadsSecurityTimestamp() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setBody(
                    """
                    {
                      "result_code": 0,
                      "result_msg": "ok",
                      "user": {
                        "user_id": "user-1",
                        "access_id": "access-1",
                        "access_secret": "secret-1",
                        "last_sec_update_ts_s": "1700000002",
                        "full_name": "测试用户"
                      }
                    }
                    """.trimIndent()
                )
            )
            val baseUrl = server.url("/").toString().removeSuffix("/")
            val service = AutoSignService(
                options = AppConfig(),
                api = `fun`.eternalblue.net.ApiClient(OkHttpClient()),
                coreApiBaseUrl = baseUrl,
                legacyLoginApiUrl = "$baseUrl/mssvc/index.php/passport/login"
            )

            val session = service.loginLegacyMobileForTest("account", "password")

            val request = requireNotNull(server.takeRequest())
            assertEquals("/mssvc/index.php/passport/login", request.path)
            assertEquals(
                "account_name=account&app_id=MTANDROID&app_version_name=5.1.1" +
                    "&app_version_number=111&device_type=ANDROID&dpr=2.7" +
                    "&system_version=8.1.0&user_pwd=password",
                request.body.readUtf8()
            )
            assertEquals("MTANDROID", request.getHeader("X-app-id"))
            assertEquals("5.1.1", request.getHeader("X-app-version"))
            val date = requireNotNull(request.getHeader("Date"))
            assertTrue(date.endsWith("GMT+00:00"))
            assertEquals(
                MobileProtocol.hmacSha1(
                    MobileProtocol.legacyLoginMessage(
                        date,
                        MobileProtocol.legacyLoginFields("account", "password")
                    ),
                    MobileProtocol.LOGIN_KEY
                ),
                request.getHeader("X-mssvc-signature")
            )
            assertEquals("1700000002", session?.lastSecUpdateTsS)
        }
    }
}
