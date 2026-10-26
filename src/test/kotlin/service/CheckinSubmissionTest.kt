package `fun`.eternalblue.service

import `fun`.eternalblue.data.AccountOptions
import `fun`.eternalblue.data.CheckinCurrent
import `fun`.eternalblue.data.CheckinInfo
import `fun`.eternalblue.data.ClazzCourse
import `fun`.eternalblue.data.CourseLocation
import `fun`.eternalblue.data.Course
import `fun`.eternalblue.data.SignLocation
import `fun`.eternalblue.net.MobileProtocol
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CheckinSubmissionTest {
    private val course = ClazzCourse(
        id = "COURSE-1",
        course = Course(name = "测试课程")
    )

    @Test
    fun sendsClockinWithMobileSignatureAndSecurityTimestamp() = withServer { server ->
        server.enqueue(MockResponse().setBody("""{"result_code":0}"""))
        val service = newService(server)
        val session = service.createTestSession(
            userId = "user-1",
            accessId = "access-1",
            accessSecret = "secret-1",
            lastSecUpdateTsS = "1700000000"
        )

        runBlocking {
            service.submitCheckin(
                session,
                course,
                CheckinCurrent(
                    status = true,
                    checkin = CheckinInfo(id = "ignored", type = "CLOCKIN")
                )
            )
        }

        val request = requireNotNull(server.takeRequest())
        assertEquals("/cc_clockin/clockin", request.path)
        assertEquals("cc_id=COURSE-1", request.body.readUtf8())
        assertEquals("1700000000", request.getHeader("X-mssvc-sec-ts"))
        assertEquals(MobileProtocol.DEVICE_CODE, request.getHeader("X-device-code"))
        assertEquals("access-1", request.getHeader("X-mssvc-access-id"))
        assertEquals("MTANDROID", request.getHeader("X-app-id"))
        assertEquals("5.4.33", request.getHeader("X-app-version"))
        assertTrue(request.getHeader("Date")!!.endsWith("GMT+00:00"))

        val date = request.getHeader("Date")!!
        assertEquals(
            MobileProtocol.hmacSha1(
                MobileProtocol.clockinMessage("user-1", date, course.id),
                "secret-1"
            ),
            request.getHeader("X-mssvc-signature")
        )
    }

    @Test
    fun sendsSimpleCheckinWithCheckinIdAndSignature() = withServer { server ->
        server.enqueue(MockResponse().setBody("""{"result_code":2409}"""))
        val service = newService(server)
        val session = service.createTestSession(
            userId = "user-1",
            accessId = "access-1",
            accessSecret = "secret-1",
            lastSecUpdateTsS = "1700000001"
        )

        runBlocking {
            service.submitCheckin(
                session,
                course,
                CheckinCurrent(
                    status = true,
                    checkin = CheckinInfo(id = "checkin-1", type = "SIMPLE")
                )
            )
        }

        val request = requireNotNull(server.takeRequest())
        assertEquals("/checkin", request.path)
        assertEquals(
            "checkin_id=checkin-1&report_pos_flag=Y&lat=&lng=",
            request.body.readUtf8()
        )
        assertEquals("1700000001", request.getHeader("X-mssvc-sec-ts"))
        assertTrue(request.getHeader("Date")!!.endsWith("GMT+00:00"))

        val date = request.getHeader("Date")!!
        assertEquals(
            MobileProtocol.hmacSha1(
                MobileProtocol.normalCheckinMessage("user-1", date),
                "secret-1"
            ),
            request.getHeader("X-mssvc-signature")
        )
    }

    @Test
    fun refusesIncompleteCredentialsWithoutSendingRequest() = withServer { server ->
        val service = newService(server)
        val session = service.createTestSession(
            userId = "user-1",
            accessId = "access-1",
            accessSecret = "secret-1",
            lastSecUpdateTsS = ""
        )

        runBlocking {
            service.submitCheckin(
                session,
                course,
                CheckinCurrent(
                    status = true,
                    checkin = CheckinInfo(id = "checkin-1", type = "SIMPLE")
                )
            )
        }

        assertEquals(null, server.takeRequest(100, java.util.concurrent.TimeUnit.MILLISECONDS))
    }

    @Test
    fun routesBothNormalTypesToNormalCheckin() {
        val service = AutoSignService(testOptions())

        assertEquals("normal", service.checkinTypeForTest("SIMPLE"))
        assertEquals("normal", service.checkinTypeForTest("POINT_9"))
        assertEquals("clockin", service.checkinTypeForTest("CLOCKIN"))
        assertEquals("unsupported", service.checkinTypeForTest("OTHER"))
    }

    @Test
    fun sendsConfiguredLocationForPoint9() = withServer { server ->
        server.enqueue(MockResponse().setBody("""{"result_code":0}"""))
        val service = AutoSignService(
            options = testOptions(
                autoCheckin = true,
                location = SignLocation(longitude = 120.15507, latitude = 30.274085)
            ),
            api = `fun`.eternalblue.net.ApiClient(OkHttpClient()),
            coreApiBaseUrl = server.url("/").toString().removeSuffix("/"),
            normalCheckinApiUrl = server.url("/checkin").toString()
        )
        val session = service.createTestSession(
            userId = "user-1",
            accessId = "access-1",
            accessSecret = "secret-1",
            lastSecUpdateTsS = "1700000002"
        )

        runBlocking {
            service.submitCheckin(
                session,
                course,
                CheckinCurrent(
                    status = true,
                    checkin = CheckinInfo(id = "checkin-9", type = "POINT_9")
                )
            )
        }

        val request = requireNotNull(server.takeRequest())
        assertEquals("/checkin", request.path)
        assertEquals(
            "checkin_id=checkin-9&report_pos_flag=Y&lat=30.274085&lng=120.155070",
            request.body.readUtf8()
        )
    }

    @Test
    fun usesPerCourseLocationInsteadOfGlobal() = withServer { server ->
        server.enqueue(MockResponse().setBody("""{"result_code":0}"""))
        val service = AutoSignService(
            options = testOptions(
                autoCheckin = true,
                location = SignLocation(longitude = 120.0, latitude = 30.0),
                locations = listOf(
                    CourseLocation(
                        location = SignLocation(longitude = 121.5, latitude = 31.5),
                        courseName = "测试课程"
                    )
                )
            ),
            api = `fun`.eternalblue.net.ApiClient(OkHttpClient()),
            coreApiBaseUrl = server.url("/").toString().removeSuffix("/"),
            normalCheckinApiUrl = server.url("/checkin").toString()
        )
        val session = service.createTestSession(
            userId = "user-1",
            accessId = "access-1",
            accessSecret = "secret-1",
            lastSecUpdateTsS = "1700000003"
        )

        runBlocking {
            service.submitCheckin(
                session,
                course,
                CheckinCurrent(
                    status = true,
                    checkin = CheckinInfo(id = "checkin-10", type = "SIMPLE")
                )
            )
        }

        val request = requireNotNull(server.takeRequest())
        assertEquals(
            "checkin_id=checkin-10&report_pos_flag=Y&lat=31.500000&lng=121.500000",
            request.body.readUtf8()
        )
    }

    private fun newService(server: MockWebServer): AutoSignService {
        val baseUrl = server.url("/").toString().removeSuffix("/")
        return AutoSignService(
            options = testOptions(autoCheckin = true),
            api = `fun`.eternalblue.net.ApiClient(OkHttpClient()),
            coreApiBaseUrl = baseUrl,
            clockinApiUrl = "$baseUrl/cc_clockin/clockin",
            normalCheckinApiUrl = "$baseUrl/checkin"
        )
    }

    private fun withServer(block: (MockWebServer) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            block(server)
        }
    }

    private fun testOptions(
        autoCheckin: Boolean = true,
        location: SignLocation? = null,
        locations: List<CourseLocation> = emptyList()
    ) = AccountOptions(
        label = "测试账户",
        account = "user-1",
        password = "secret-1",
        autoCheckin = autoCheckin,
        location = location,
        locations = locations
    )
}
