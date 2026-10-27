package `fun`.eternalblue.net

import `fun`.eternalblue.data.ApiResponse
import `fun`.eternalblue.data.CheckinCurrent
import `fun`.eternalblue.data.SubmitResult
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiParsingTest {
    private val api = ApiClient()

    @Test
    fun detectsHtmlInsteadOfJson() {
        assertTrue(ApiClient.looksLikeHtml("<!DOCTYPE html><html></html>"))
        assertTrue(ApiClient.looksLikeHtml(" \n<html><body>login</body></html>"))
        assertTrue(ApiClient.looksLikeHtml("<?xml version=\"1.0\"?>"))
        assertFalse(ApiClient.looksLikeHtml("""{"status":false}"""))
    }

    @Test
    fun parsesWebLoginError() {
        val response = api.json.decodeFromString<ApiResponse>(
            """
            {
              "errorMessage": "你输入的账号或密码不正确",
              "errorCode": "err.invalid.userPassword",
              "status": false
            }
            """.trimIndent()
        )

        assertFalse(response.status)
        assertEquals("err.invalid.userPassword", response.errorCode)
        assertEquals("你输入的账号或密码不正确", response.errorMessage)
    }

    @Test
    fun readsCoursesFromDataEnvelopeAndRoot() {
        val wrapped = api.json.decodeFromString<ApiResponse>(
            """
            {
              "status": true,
              "data": {
                "clazzCourses": [
                  {"id":"course-1","status":"OPEN","course":{"name":"数据结构"}}
                ]
              }
            }
            """.trimIndent()
        )
        val root = api.json.decodeFromString<ApiResponse>(
            """
            {
              "status": true,
              "clazzCourses": [
                {"id":"course-2","status":"OPEN","course":{"name":"操作系统"}}
              ]
            }
            """.trimIndent()
        )

        assertEquals("数据结构", wrapped.courses().single().displayName)
        assertEquals("操作系统", root.courses().single().displayName)
    }

    @Test
    fun parsesBothUserFieldStyles() {
        val camel = api.json.decodeFromString<ApiResponse>(
            """
            {
              "status": true,
              "token": "web-token",
              "user": {
                "userId": "u1",
                "accessId": "a1",
                "accessSecret": "s1",
                "fullName": "张三",
                "lastSecUpdateTsS": "123",
                "school": {"name":"示例大学"}
              }
            }
            """.trimIndent()
        )
        val snake = api.json.decodeFromString<ApiResponse>(
            """
            {
              "status": true,
              "user": {
                "user_id": "u2",
                "access_id": "a2",
                "access_secret": "s2",
                "full_name": "李四",
                "last_sec_update_ts_s": "456",
                "school_name": "旧版大学"
              }
            }
            """.trimIndent()
        )

        assertEquals("u1", camel.user?.resolvedUserId)
        assertEquals("s1", camel.user?.resolvedAccessSecret)
        assertEquals("示例大学", camel.user?.resolvedSchoolName)
        assertEquals("u2", snake.user?.resolvedUserId)
        assertEquals("旧版大学", snake.user?.resolvedSchoolName)
    }

    @Test
    fun onlyMarksOpenWhenStatusAndCheckinExist() {
        val closed = api.json.decodeFromString<CheckinCurrent>("""{"status":false}""")
        val malformed = api.json.decodeFromString<CheckinCurrent>("""{"status":true}""")
        val open = api.json.decodeFromString<CheckinCurrent>(
            """{"status":true,"checkin":{"id":"c1","type":"CLOCKIN"}}"""
        )

        assertFalse(closed.isOpen)
        assertFalse(malformed.isOpen)
        assertTrue(open.isOpen)
    }

    @Test
    fun toleratesStatusAndTimestampTypeVariants() {
        val numericStatus = api.json.decodeFromString<CheckinCurrent>(
            """{"status":1,"checkin":{"id":123,"type":"SIMPLE","openTime":1690000000000}}"""
        )
        val stringStatus = api.json.decodeFromString<CheckinCurrent>(
            """{"status":"true","checkin":{"id":"c2","type":" clockin "}}"""
        )
        val flagStatus = api.json.decodeFromString<CheckinCurrent>(
            """{"status":"Y","checkin":{"id":"c3","type":"POINT_9"}}"""
        )
        val nullStatus = api.json.decodeFromString<CheckinCurrent>(
            """{"status":null,"checkin":{"id":"c4"}}"""
        )

        assertTrue(numericStatus.isOpen)
        assertEquals("123", numericStatus.checkin?.id)
        assertEquals("SIMPLE", numericStatus.checkin?.normalizedType)
        assertTrue(stringStatus.isOpen)
        assertEquals("CLOCKIN", stringStatus.checkin?.normalizedType)
        assertTrue(flagStatus.isOpen)
        assertEquals("POINT_9", flagStatus.checkin?.normalizedType)
        assertFalse(nullStatus.isOpen)
    }

    @Test
    fun toleratesCheckinEnvelopeVariants() {
        val errorOnly = api.json.decodeFromString<CheckinCurrent>(
            """
            {"requestId":"r1","errorCode":"err.no.open.checkin",
             "errorMessage":"当前没有进行中的签到","status":false}
            """.trimIndent()
        )
        val embeddedJson = api.json.decodeFromString<CheckinCurrent>(
            """{"status":true,"checkin":"{\"id\":\"c5\",\"type\":\"CLOCKIN\"}"}"""
        )
        val emptyObject = api.json.decodeFromString<CheckinCurrent>("{}")

        assertFalse(errorOnly.isOpen)
        assertEquals("当前没有进行中的签到", errorOnly.errorMessage)
        assertTrue(embeddedJson.isOpen)
        assertEquals("c5", embeddedJson.checkin?.id)
        assertFalse(emptyObject.isOpen)
    }

    @Test
    fun parsesRealClockinPayloadWithNegativeId() {
        val open = api.json.decodeFromString<CheckinCurrent>(
            """
            {"checkin":{"deadlineTime":"2026-10-26 13:12:17","checkinTime":null,
             "id":-8368735,"checkinFlag":"N","type":"CLOCKIN",
             "openTime":"2026-10-26 13:10:17","points":null},"status":true}
            """.trimIndent()
        )
        val signed = api.json.decodeFromString<CheckinCurrent>(
            """
            {"checkin":{"deadlineTime":"2026-10-26 13:12:17",
             "checkinTime":"2026-10-26 13:10:24","id":-8368735,"checkinFlag":"Y",
             "type":"CLOCKIN","openTime":"2026-10-26 13:10:17","points":null},"status":true}
            """.trimIndent()
        )

        assertTrue(open.isOpen)
        assertEquals("-8368735", open.checkin?.id)
        assertEquals("CLOCKIN", open.checkin?.normalizedType)
        assertFalse(open.checkin?.alreadySigned == true)
        assertTrue(signed.checkin?.alreadySigned == true)
    }

    @Test
    fun parsesClockinResults() {
        val ok = api.json.decodeFromString<SubmitResult>("""{"result_code":0}""")
        val repeat = api.json.decodeFromString<SubmitResult>("""{"resultCode":2409}""")

        assertEquals(0, ok.code)
        assertEquals(2409, repeat.code)
    }

    @Test
    fun parsesGesturePointsFromPoint9Checkin() {
        val open = api.json.decodeFromString<CheckinCurrent>(
            """
            {"status":true,"checkin":{"id":"c9","type":"POINT_9",
             "points":"1-4-7-8","checkinFlag":"N"}}
            """.trimIndent()
        )
        val noPoints = api.json.decodeFromString<CheckinCurrent>(
            """{"status":true,"checkin":{"id":"c10","type":"POINT_9","points":null}}"""
        )

        assertEquals("1-4-7-8", open.checkin?.points)
        assertEquals("POINT_9", open.checkin?.normalizedType)
        assertEquals(null, noPoints.checkin?.points)
    }
}
