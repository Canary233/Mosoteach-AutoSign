package `fun`.eternalblue.net

import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MobileProtocolTest {
    @Test
    fun formatsGmtDate() {
        val time = ZonedDateTime.of(2026, 10, 21, 16, 25, 42, 0, ZoneOffset.ofHours(8))

        assertEquals("Wed, 21 Oct 2026 08:25:42 GMT", MobileProtocol.gmtDate(time))
        assertEquals("Wed, 21 Oct 2026 08:25:42 GMT+00:00", MobileProtocol.mssvcDate(time))
    }

    @Test
    fun computesStableSignatures() {
        assertEquals(
            "2e987117d75014a98d0d6feb2a8f79617e5a5a98",
            MobileProtocol.hmacSha1("test-message", "test-secret")
        )
        assertEquals(
            "098f6bcd4621d373cade4e832627b4f6",
            MobileProtocol.md5("test")
        )
    }

    @Test
    fun buildsProtocolMessages() {
        assertEquals(
            "GET|/ccs/joined|ABC|Wed, 21 Oct 2026 08:25:42 GMT||",
            MobileProtocol.coursesMessage("abc", "Wed, 21 Oct 2026 08:25:42 GMT")
        )
        assertEquals(
            "GET|/ccs/c1/checkins/current|USER|date||",
            MobileProtocol.checkinStatusMessage("user", "date", "c1")
        )
        assertEquals(
            "https://checkin.mosoteach.cn:19528/checkin|USER|date",
            MobileProtocol.normalCheckinMessage("user", "date")
        )
        assertEquals(
            "https://api.mosoteach.cn/mssvc/index.php/cc_clockin/clockin|USER|date|" +
                "F1249164898AD991C2C7A5273326BBD1",
            MobileProtocol.clockinMessage("user", "date", "course")
        )
    }

    @Test
    fun buildsNormalCheckinForm() {
        assertEquals(
            listOf(
                "checkin_id" to "c1",
                "report_pos_flag" to "Y",
                "lat" to "",
                "lng" to ""
            ),
            MobileProtocol.normalCheckinFields("c1")
        )
        assertEquals(
            listOf(
                "checkin_id" to "c1",
                "report_pos_flag" to "Y",
                "lat" to "30.274085",
                "lng" to "120.155070"
            ),
            MobileProtocol.normalCheckinFields("c1", 30.274085, 120.15507)
        )
        assertEquals("", MobileProtocol.formatCoordinate(null))
    }

    @Test
    fun buildsLegacyLoginFormAndSignature() {
        val fields = MobileProtocol.legacyLoginFields("account", "password")
        assertEquals(
            listOf(
                "account_name" to "account",
                "app_id" to "MTANDROID",
                "app_version_name" to "5.1.1",
                "app_version_number" to "111",
                "device_type" to "ANDROID",
                "dpr" to "2.7",
                "system_version" to "8.1.0",
                "user_pwd" to "password"
            ),
            fields
        )
        val date = "Wed, 21 Oct 2026 08:25:42 GMT+00:00"
        assertEquals(
            "https://api.mosoteach.cn/mssvc/index.php/passport/login|$date|" +
                "account_name=account|app_id=MTANDROID|app_version_name=5.1.1|" +
                "app_version_number=111|device_type=ANDROID|dpr=2.7|" +
                "system_version=8.1.0|user_pwd=password",
            MobileProtocol.legacyLoginMessage(date, fields)
        )
    }

    @Test
    fun escapesJsonAndEncryptsDeterministically() {
        val payload = MobileProtocol.loginJson("a\"b", "line\nbreak")
        assertEquals("""{"account":"a\"b","password":"line\nbreak"}""", payload)
        assertEquals(
            MobileProtocol.aesEcbPkcs5Base64(MobileProtocol.LOGIN_KEY, payload),
            MobileProtocol.aesEcbPkcs5Base64(MobileProtocol.LOGIN_KEY, payload)
        )
        assertTrue(MobileProtocol.aesEcbPkcs5Base64(MobileProtocol.LOGIN_KEY, payload).isNotBlank())
    }
}
