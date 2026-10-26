package `fun`.eternalblue.net

import java.net.URLEncoder
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object MobileProtocol {
    const val LOGIN_KEY = "526EBA802E6FCF44661DE4393A82ABDA"
    const val APP_ID = "MTANDROID"
    const val APP_VERSION = "5.4.30"
    const val MSSVC_APP_VERSION = "5.4.33"
    const val DEVICE_CODE = "bc55d279_8a7b_4a43_80e5_e6f8776d9424"
    const val CLOCKIN_API =
        "https://api.mosoteach.cn/mssvc/index.php/cc_clockin/clockin"
    const val NORMAL_CHECKIN_API = "https://checkin.mosoteach.cn:19528/checkin"
    const val LEGACY_LOGIN_API =
        "https://api.mosoteach.cn/mssvc/index.php/passport/login"
    const val MOBILE_USER_AGENT =
        "Dalvik/2.1.0 (Linux; U; Android 14; 2210132C Build/UKQ1.230804.001)"

    fun gmtDate(now: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC)): String =
        now.withZoneSameInstant(ZoneOffset.UTC).format(GMT_FORMAT)

    fun mssvcDate(now: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC)): String =
        now.withZoneSameInstant(ZoneOffset.UTC).format(MSSVC_GMT_FORMAT)

    fun hmacSha1(message: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun md5(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun loginMessage(date: String, payload: String): String =
        "POST|/passports/account-login|$APP_ID|$date||${md5(payload)}"

    fun coursesMessage(userId: String, date: String): String =
        "GET|/ccs/joined|${userId.uppercase()}|$date||"

    fun checkinStatusMessage(userId: String, date: String, courseId: String): String =
        "GET|/ccs/$courseId/checkins/current|${userId.uppercase()}|$date||"

    fun clockinMessage(userId: String, date: String, courseId: String): String =
        "$CLOCKIN_API|" +
            "${userId.uppercase()}|$date|${md5("cc_id=$courseId").uppercase()}"

    fun normalCheckinMessage(userId: String, date: String): String =
        "$NORMAL_CHECKIN_API|${userId.uppercase()}|$date"

    fun normalCheckinFields(
        checkinId: String,
        latitude: Double? = null,
        longitude: Double? = null
    ): List<Pair<String, String>> = listOf(
        "checkin_id" to checkinId,
        "report_pos_flag" to "Y",
        "lat" to formatCoordinate(latitude),
        "lng" to formatCoordinate(longitude)
    )

    fun formatCoordinate(value: Double?): String =
        value?.let { java.lang.String.format(Locale.ROOT, "%.6f", it) } ?: ""

    fun legacyLoginFields(account: String, password: String): List<Pair<String, String>> = listOf(
        "account_name" to account,
        "app_id" to APP_ID,
        "app_version_name" to LEGACY_LOGIN_APP_VERSION,
        "app_version_number" to LEGACY_LOGIN_APP_VERSION_NUMBER,
        "device_type" to "ANDROID",
        "dpr" to "2.7",
        "system_version" to "8.1.0",
        "user_pwd" to password
    )

    fun legacyLoginMessage(date: String, fields: List<Pair<String, String>>): String =
        "$LEGACY_LOGIN_API|$date|" +
            fields.joinToString("|") { (name, value) -> "$name=$value" }

    fun legacyLoginSignature(date: String, fields: List<Pair<String, String>>): String =
        hmacSha1(legacyLoginMessage(date, fields), LOGIN_KEY)

    fun aesEcbPkcs5Base64(key: String, plainText: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"))
        return Base64.getEncoder().encodeToString(cipher.doFinal(plainText.toByteArray(Charsets.UTF_8)))
    }

    fun loginJson(account: String, password: String): String {
        val accountValue = jsonEscape(account)
        val passwordValue = jsonEscape(password)
        return """{"account":"$accountValue","password":"$passwordValue"}"""
    }

    fun formValue(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    private fun jsonEscape(value: String): String = buildString(value.length + 8) {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char < ' ') {
                    append("\\u%04x".format(char.code))
                } else {
                    append(char)
                }
            }
        }
    }

    private val GMT_FORMAT = DateTimeFormatter.ofPattern(
        "EEE, dd MMM yyyy HH:mm:ss 'GMT'",
        Locale.ENGLISH
    )
    private val MSSVC_GMT_FORMAT = DateTimeFormatter.ofPattern(
        "EEE, dd MMM yyyy HH:mm:ss 'GMT+00:00'",
        Locale.ENGLISH
    )
    private const val LEGACY_LOGIN_APP_VERSION = "5.1.1"
    private const val LEGACY_LOGIN_APP_VERSION_NUMBER = "111"
}
