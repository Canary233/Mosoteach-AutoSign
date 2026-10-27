package `fun`.eternalblue.service

import `fun`.eternalblue.data.ApiResponse
import `fun`.eternalblue.data.ApiUser
import `fun`.eternalblue.data.AccountOptions
import `fun`.eternalblue.data.CheckinCurrent
import `fun`.eternalblue.data.ClazzCourse
import `fun`.eternalblue.data.SubmitResult
import `fun`.eternalblue.net.ApiClient
import `fun`.eternalblue.net.ApiException
import `fun`.eternalblue.net.MobileProtocol
import `fun`.eternalblue.util.Log
import `fun`.eternalblue.util.Debug
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.decodeFromString
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class AutoSignService(
    private val options: AccountOptions,
    private val account: String = options.account,
    private val password: String = options.password,
    api: ApiClient? = null,
    private val coreApiBaseUrl: String = CORE_API,
    private val legacyLoginApiUrl: String = MobileProtocol.LEGACY_LOGIN_API,
    private val clockinApiUrl: String = MobileProtocol.CLOCKIN_API,
    private val normalCheckinApiUrl: String = MobileProtocol.NORMAL_CHECKIN_API
) {
    private val api: ApiClient = api ?: ApiClient(tag = options.label)

    private val accountLabel: String = options.label

    private fun say(message: String = "") {
        println("[$accountLabel] $message")
    }

    /** Log lines from concurrent accounts must stay attributable. */
    private fun logTag(message: String): String = "[$accountLabel] $message"

    private fun logInfo(message: String) = Log.info(logTag(message))

    private fun logError(message: String) = Log.error(logTag(message))

    private fun logDebug(message: String) = Log.debug(logTag(message))

    private fun debugLine(message: String) = Debug.line(logTag(message))

    private fun debugName(name: String): String = "$accountLabel-$name"

    internal sealed interface Session {
        val displayName: String
        val schoolName: String?
        val lastSecUpdateTsS: String?

        data class Mobile(
            val userId: String,
            val accessId: String,
            val accessSecret: String,
            override val lastSecUpdateTsS: String?,
            override val displayName: String,
            override val schoolName: String?
        ) : Session

        data class Web(
            val token: String,
            val userId: String?,
            val accessId: String?,
            val accessSecret: String?,
            override val lastSecUpdateTsS: String?,
            override val displayName: String,
            override val schoolName: String?
        ) : Session
    }

    suspend fun run(): Int {
        require(account.isNotBlank()) { "账号不能为空" }
        require(password.isNotEmpty()) { "密码不能为空" }
        val session = login(account, password)

        say("登录成功：${session.displayName}${session.schoolName?.let { "（$it）" } ?: ""}")
        logInfo("登录成功，模式=${session.javaClass.simpleName}")
        if (session is Session.Web && !session.hasMobileCredentials()) {
            say("提示：网页登录未返回移动端签名凭据，只能读取班课与签到状态。")
        } else if (session.lastSecUpdateTsS.isNullOrBlank()) {
            say("提示：登录响应缺少移动端安全时间戳，只能查询签到状态。")
        }

        return runPolling(session)
    }

    internal suspend fun runPolling(
        session: Session,
        nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }
    ): Int {
        var cycle = 0
        while (true) {
            val roundStartedAt = nowMillis()
            cycle += 1
            say()
            say("[${timeNow()}] 第 $cycle 轮检查")
            try {
                checkRound(session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                say("本轮检查失败：${e.message}")
                logError(e.message.orEmpty())
                if (e.message?.contains("Unauthorized", ignoreCase = true) == true ||
                    e.message?.contains("登录", ignoreCase = true) == true
                ) {
                    say("会话可能已失效，请重新启动程序登录。")
                    return 1
                }
            } catch (e: Exception) {
                say("本轮检查异常：${e.message ?: e::class.simpleName}")
                logError(e.message ?: e::class.simpleName.orEmpty())
            }

            if (options.once) break
            val remaining = options.intervalSeconds * 1000 - (nowMillis() - roundStartedAt)
            if (remaining > 0) {
                delay(remaining)
            } else {
                say("本轮检查耗时已达到 ${options.intervalSeconds} 秒，立即开始下一轮。")
            }
        }
        return 0
    }

    internal suspend fun checkRound(session: Session) {
        val courses = fetchCourses(session)
        if (courses.isEmpty()) {
            say("未找到进行中的班课。")
            return
        }
        say("进行中的班课：${courses.size} 个")

        var openCount = 0
        var failedCount = 0
        val roundStartedAt = System.nanoTime()
        courses.forEachIndexed { index, course ->
            if (options.debug) {
                say("[${index + 1}/${courses.size}] ${course.displayName}")
            }
            val courseStartedAt = System.nanoTime()
            val current = try {
                fetchCurrentCheckin(session, course.id, course.displayName)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // One malformed course response must not hide open check-ins in other courses.
                failedCount += 1
                say("${course.displayName}：签到状态读取失败（${e.message ?: "未知错误"}）")
                logError(
                    "签到状态读取失败：course=${course.id}, ${e.message ?: e::class.simpleName}"
                )
                delay(options.courseDelayMillis)
                return@forEachIndexed
            }
            if (options.debug) {
                val elapsed = (System.nanoTime() - courseStartedAt) / 1_000_000
                val state = when {
                    current == null -> "无响应"
                    current.isOpen -> "已开启 ${current.checkin?.normalizedType ?: "UNKNOWN"}"
                    current.isActive -> "状态异常（缺少签到信息）"
                    !current.errorCode.isNullOrBlank() ->
                        "未开启（${current.errorCode}${current.errorMessage?.let { "：$it" } ?: ""}）"
                    else -> "未开启"
                }
                say("[调试] ${course.displayName}：$state（${elapsed}ms）")
            }
            when {
                current == null -> Unit
                !current.isActive -> Unit
                !current.isOpen -> {
                    say("${course.displayName}：签到状态异常（status=true 但缺少签到信息）")
                }
                else -> {
                    openCount += 1
                    val type = current.checkin?.normalizedType ?: "UNKNOWN"
                    if (current.checkin?.alreadySigned == true) {
                        say("${course.displayName}：签到已开启，类型 $type（本账号已签到）")
                    } else {
                        say("${course.displayName}：签到已开启，类型 $type")
                    }
                    if (options.autoCheckin && current.checkin?.alreadySigned != true) {
                        submitCheckin(session, course, current)
                    }
                }
            }
            delay(options.courseDelayMillis)
        }
        if (options.debug) {
            val elapsed = (System.nanoTime() - roundStartedAt) / 1_000_000
            say("[调试] 本轮共 ${courses.size} 个班课，耗时 ${elapsed}ms")
        }
        if (openCount == 0) {
            say(
                if (failedCount == 0) "当前没有已开启的签到。"
                else "当前没有已开启的签到（$failedCount 个班课读取失败）。"
            )
        } else if (failedCount > 0) {
            say("另有 $failedCount 个班课读取失败，详见上方提示和 logs/log.log。")
        }
    }

    internal fun login(account: String, password: String): Session {
        val mobileFailure = runCatching { loginMobile(account, password) }
            .getOrElse {
                logDebug("移动端登录失败：${it.message}")
                null
            }
        if (mobileFailure != null) return mobileFailure

        val legacyMobile = runCatching { loginLegacyMobile(account, password) }
            .getOrElse {
                logDebug("旧版移动端登录失败：${it.message}")
                null
            }
        if (legacyMobile != null) return legacyMobile

        say("移动端登录未成功，尝试网页登录…")
        return loginWeb(account, password)
    }

    internal fun createTestSession(
        userId: String,
        accessId: String,
        accessSecret: String,
        lastSecUpdateTsS: String
    ): Session.Mobile = Session.Mobile(
        userId = userId,
        accessId = accessId,
        accessSecret = accessSecret,
        lastSecUpdateTsS = lastSecUpdateTsS,
        displayName = "测试用户",
        schoolName = null
    )

    internal fun loginLegacyMobileForTest(account: String, password: String): Session.Mobile? =
        loginLegacyMobile(account, password)

    internal fun checkinTypeForTest(type: String) = when (type.uppercase()) {
        "CLOCKIN" -> "clockin"
        "SIMPLE", "POINT_9" -> "normal"
        else -> "unsupported"
    }

    private fun loginMobile(account: String, password: String): Session.Mobile? {
        val date = MobileProtocol.gmtDate()
        val payload = MobileProtocol.loginJson(account, password)
        val encrypted = MobileProtocol.aesEcbPkcs5Base64(MobileProtocol.LOGIN_KEY, payload)
        val signature = MobileProtocol.hmacSha1(
            MobileProtocol.loginMessage(date, payload),
            MobileProtocol.LOGIN_KEY
        )
        val security = buildString(32) {
            repeat(32) { append("abcdefghijklmnopqrstuvwxyz0123456789".random()) }
        }
        val request = Request.Builder()
            .url("$coreApiBaseUrl/passports/account-login")
            .post(encrypted.toRequestBody(TEXT_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("Date", date)
            .header("X-access-id", MobileProtocol.APP_ID)
            .header("X-signature", signature)
            .header("X-client-app-id", MobileProtocol.APP_ID)
            .header("X-client-version", MobileProtocol.APP_VERSION)
            .header("X-security", security)
            .build()

        val body = api.execute(request)
        val response = decodeResponse(body, "移动端登录")
        if (!response.status) {
            logDebug("移动端登录未通过：${response.errorCode ?: "unknown"}")
            return null
        }
        val user = response.user
        val userId = user?.resolvedUserId
        val accessId = user?.resolvedAccessId
        val accessSecret = user?.resolvedAccessSecret
        if (userId.isNullOrBlank() || accessId.isNullOrBlank() || accessSecret.isNullOrBlank()) {
            logDebug("移动端登录响应缺少 accessSecret/accessId，回退网页登录")
            return null
        }
        return Session.Mobile(
            userId = userId,
            accessId = accessId,
            accessSecret = accessSecret,
            lastSecUpdateTsS = user.resolvedLastSecUpdateTsS,
            displayName = user.resolvedFullName ?: "云班课用户",
            schoolName = user.resolvedSchoolName
        )
    }

    private fun loginWeb(account: String, password: String): Session.Web {
        val payload = MobileProtocol.loginJson(account, password)
        val request = Request.Builder()
            .url("$coreApiBaseUrl/passports/account-login")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json;charset=utf-8")
            .header("X-client-app-id", "MTWEB")
            .header("X-client-version", "6.0.0")
            .header("X-security-type", "SECURITY_TYPE_TOKEN")
            .header("User-Agent", WEB_USER_AGENT)
            .build()

        val body = api.execute(request)
        val response = decodeResponse(body, "网页登录")
        if (!response.status || response.token.isNullOrBlank()) {
            val reason = response.errorMessage ?: "未返回登录令牌"
            throw ApiException("登录失败：$reason")
        }
        return Session.Web(
            token = response.token,
            userId = response.user?.resolvedUserId,
            accessId = response.user?.resolvedAccessId,
            accessSecret = response.user?.resolvedAccessSecret,
            lastSecUpdateTsS = response.user?.resolvedLastSecUpdateTsS,
            displayName = response.user?.resolvedFullName ?: "云班课用户",
            schoolName = response.user?.resolvedSchoolName
        )
    }

    private fun loginLegacyMobile(account: String, password: String): Session.Mobile? {
        val fields = MobileProtocol.legacyLoginFields(account, password)
        val date = MobileProtocol.mssvcDate()
        val signature = MobileProtocol.legacyLoginSignature(date, fields)
        val form = FormBody.Builder()
            .apply {
                fields.forEach { (name, value) -> add(name, value) }
            }
            .build()
        val request = Request.Builder()
            .url(legacyLoginApiUrl)
            .post(form)
            .header("User-Agent", LEGACY_MOBILE_USER_AGENT)
            .header("Date", date)
            .header("X-mssvc-signature", signature)
            .header("X-app-id", MobileProtocol.APP_ID)
            .header("X-app-version", LEGACY_LOGIN_APP_VERSION)
            .header("X-dpr", "2.7")
            .header("X-app-machine", "ONE A2001")
            .header("X-app-system-version", "8.1.0")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build()

        val body = api.execute(request)
        val response = decodeResponse(body, "旧版移动端登录")
        if (!response.isSuccess) {
            logDebug(
                "旧版移动端登录未通过：${response.errorCode ?: response.resultCode ?: "unknown"}"
            )
            return null
        }
        val user = response.user ?: return null
        val userId = user.resolvedUserId
        val accessId = user.resolvedAccessId
        val accessSecret = user.resolvedAccessSecret
        val securityTimestamp = user.resolvedLastSecUpdateTsS
        if (userId.isNullOrBlank() || accessId.isNullOrBlank() ||
            accessSecret.isNullOrBlank() || securityTimestamp.isNullOrBlank()
        ) {
            logDebug("旧版移动端登录响应缺少完整签名凭据")
            return null
        }
        return Session.Mobile(
            userId = userId,
            accessId = accessId,
            accessSecret = accessSecret,
            lastSecUpdateTsS = securityTimestamp,
            displayName = user.resolvedFullName ?: "云班课用户",
            schoolName = user.resolvedSchoolName
        )
    }

    private fun fetchCourses(session: Session): List<ClazzCourse> {
        val request = when (session) {
            is Session.Mobile -> {
                val date = MobileProtocol.gmtDate()
                Request.Builder()
                    .url("$coreApiBaseUrl/ccs/joined")
                    .get()
                    .header("Date", date)
                    .header("X-access-id", session.accessId)
                    .header(
                        "X-signature",
                        MobileProtocol.hmacSha1(
                            MobileProtocol.coursesMessage(session.userId, date),
                            session.accessSecret
                        )
                    )
                    .header("X-client-app-id", MobileProtocol.APP_ID)
                    .header("X-client-version", MobileProtocol.APP_VERSION)
                    .header("User-Agent", MobileProtocol.MOBILE_USER_AGENT)
                    .build()
            }
            is Session.Web -> Request.Builder()
                .url("$coreApiBaseUrl/ccs/joined?_ts=${System.currentTimeMillis()}")
                .get()
                .applyWebHeaders(session)
                .build()
        }

        val body = api.execute(request)
        val response = decodeResponse(body, "班课列表")
        ensureSuccess(response, "读取班课列表")
        val unique = LinkedHashMap<String, ClazzCourse>()
        response.courses()
            .asSequence()
            .filter { it.status.uppercase() != "CLOSED" }
            .forEach { unique.putIfAbsent(it.id, it) }
        return unique.values.toList()
    }

    private fun fetchCurrentCheckin(
        session: Session,
        courseId: String,
        courseName: String
    ): CheckinCurrent? {
        val request = when (session) {
            is Session.Mobile -> {
                val date = MobileProtocol.gmtDate()
                Request.Builder()
                    .url("$coreApiBaseUrl/ccs/$courseId/checkins/current")
                    .get()
                    .header("Date", date)
                    .header("Content-Type", "application/json")
                    .header("X-access-id", session.accessId)
                    .header(
                        "X-signature",
                        MobileProtocol.hmacSha1(
                            MobileProtocol.checkinStatusMessage(session.userId, date, courseId),
                            session.accessSecret
                        )
                    )
                    .header("X-client-app-id", MobileProtocol.APP_ID)
                    .header("X-client-version", MobileProtocol.APP_VERSION)
                    .header("User-Agent", MobileProtocol.MOBILE_USER_AGENT)
                    .build()
            }
            is Session.Web -> Request.Builder()
                .url("$coreApiBaseUrl/ccs/$courseId/checkins/current?_ts=${System.currentTimeMillis()}")
                .get()
                .applyWebHeaders(session)
                .build()
        }
        val body = api.execute(request)
        val current = try {
            api.json.decodeFromString<CheckinCurrent>(body)
        } catch (e: Exception) {
            if (options.debug) {
                Debug.snapshotCheckin(debugName(courseId), courseName, body)
            }
            throw ApiException(
                "签到状态响应格式异常：${api.errorMessage(body) ?: e.message ?: "无法解析"}",
                e
            )
        }
        // Only keep the raw payload when a check-in is actually open or the status is odd.
        if (options.debug && (current.isOpen || current.isActive)) {
            Debug.snapshotCheckin(debugName(courseId), courseName, body)
        }
        return current
    }

    internal suspend fun submitCheckin(
        session: Session,
        course: ClazzCourse,
        current: CheckinCurrent
    ) {
        val type = current.checkin?.normalizedType
        val mobileSession = when (session) {
            is Session.Mobile -> session
            is Session.Web -> session.asMobileOrNull()
        }
        if (mobileSession == null) {
            say("${course.displayName}：登录响应缺少移动端签名凭据，未自动提交。")
            return
        }

        when (type) {
            "CLOCKIN" -> submitClockin(mobileSession, course)
            "SIMPLE", "POINT_9" -> {
                if (type == "POINT_9" && options.debug) {
                    // The gesture pattern is only compared inside the Android client
                    // (LocusPassWordView); the submit endpoint never receives it.
                    debugLine(
                        "${course.displayName}：手势签到图案 " +
                            (current.checkin?.points ?: "未下发") +
                            "，按普通签到提交（服务端不校验图案）"
                    )
                }
                submitNormalCheckin(
                    mobileSession,
                    course,
                    current.checkin?.id
                )
            }
            else -> say("${course.displayName}：暂不支持签到类型 $type，未自动提交。")
        }
    }

    private fun submitClockin(session: Session.Mobile, course: ClazzCourse) {
        val securityTimestamp = session.lastSecUpdateTsS
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (securityTimestamp == null) {
            say("${course.displayName}：登录响应缺少移动端安全时间戳，未自动提交。")
            logError("限时签到未提交：course=${course.id}, 缺少 lastSecUpdateTsS")
            return
        }

        val date = MobileProtocol.mssvcDate()
        val signature = MobileProtocol.hmacSha1(
            MobileProtocol.clockinMessage(session.userId, date, course.id),
            session.accessSecret
        )
        val form = FormBody.Builder()
            .add("cc_id", course.id)
            .build()
        val request = Request.Builder()
            .url(clockinApiUrl)
            .post(form)
            .applyMssvcHeaders(session, date, signature, securityTimestamp)
            .build()

        submitAndReport(request, course, "限时签到")
    }

    private fun submitNormalCheckin(
        session: Session.Mobile,
        course: ClazzCourse,
        checkinId: String?
    ) {
        val normalizedCheckinId = checkinId?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedCheckinId == null) {
            say("${course.displayName}：签到响应缺少签到 ID，未自动提交。")
            logError("普通签到未提交：course=${course.id}, 缺少 checkin.id")
            return
        }
        val securityTimestamp = session.lastSecUpdateTsS
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (securityTimestamp == null) {
            say("${course.displayName}：登录响应缺少移动端安全时间戳，未自动提交。")
            logError("普通签到未提交：course=${course.id}, 缺少 lastSecUpdateTsS")
            return
        }

        val date = MobileProtocol.mssvcDate()
        val signature = MobileProtocol.hmacSha1(
            MobileProtocol.normalCheckinMessage(session.userId, date),
            session.accessSecret
        )
        val form = FormBody.Builder()
            .apply {
                val location = options.locationFor(course)
                MobileProtocol.normalCheckinFields(
                    normalizedCheckinId,
                    location?.latitude,
                    location?.longitude
                ).forEach { (name, value) ->
                    add(name, value)
                }
            }
            .build()
        if (options.debug) {
            val location = options.locationFor(course)
            debugLine(
                "${course.displayName}：普通签到定位 ${
                    location?.text ?: "未配置（提交空经纬度）"
                }（来源：${options.locationSourceFor(course) ?: "无"}）"
            )
        }
        val request = Request.Builder()
            .url(normalCheckinApiUrl)
            .post(form)
            .applyMssvcHeaders(session, date, signature, securityTimestamp)
            .build()

        submitAndReport(request, course, "普通签到")
    }

    internal fun submitAndReport(
        request: Request,
        course: ClazzCourse,
        label: String
    ) {
        if (options.debug) {
            debugLine("${course.displayName}：准备提交$label → ${request.url.encodedPath}")
        }
        val body = api.execute(request)
        if (options.debug) {
            Debug.snapshot(debugName("submit-$label-${course.id}"), body)
        }
        val result = try {
            api.json.decodeFromString<SubmitResult>(body)
        } catch (e: Exception) {
            throw ApiException(
                "签到响应格式异常：${api.errorMessage(body) ?: e.message ?: "无法解析"}",
                e
            )
        }
        val ok = result.status || result.code == 0 || result.code == 2409
        if (ok) {
            val repeat = result.code == 2409
            say("${course.displayName}：${if (repeat) "已签到，无需重复提交" else "${label}提交成功"}")
            logInfo("${label}成功：course=${course.id}, repeat=$repeat")
        } else {
            val reason = result.errorMessage ?: result.message ?: "code=${result.code ?: "unknown"}"
            say("${course.displayName}：${label}未成功（$reason）")
            logError("${label}失败：course=${course.id}, $reason")
        }
    }

    private fun decodeResponse(body: String, endpoint: String): ApiResponse {
        return try {
            api.json.decodeFromString<ApiResponse>(body)
        } catch (e: Exception) {
            throw ApiException("$endpoint 响应格式异常：${api.errorMessage(body) ?: "无法解析"}", e)
        }
    }

    private fun ensureSuccess(response: ApiResponse, operation: String) {
        if (!response.status) {
            val reason = response.errorMessage ?: response.errorCode ?: "未知错误"
            throw ApiException("$operation 失败：$reason")
        }
    }

    private fun timeNow(): String =
        java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))

    private fun Request.Builder.applyWebHeaders(session: Session.Web): Request.Builder =
        header("X-client-app-id", "MTWEB")
            .header("X-client-version", "6.0.0")
            .header("X-security-type", "SECURITY_TYPE_TOKEN")
            .header("X-token", session.token)
            .header("User-Agent", WEB_USER_AGENT)

    private fun Request.Builder.applyMssvcHeaders(
        session: Session.Mobile,
        date: String,
        signature: String,
        securityTimestamp: String
    ): Request.Builder =
        header("User-Agent", MobileProtocol.MOBILE_USER_AGENT)
            .header("Date", date)
            .header("X-device-code", MobileProtocol.DEVICE_CODE)
            .header("X-mssvc-signature", signature)
            .header("X-mssvc-access-id", session.accessId)
            .header("X-app-id", MobileProtocol.APP_ID)
            .header("X-app-version", MobileProtocol.MSSVC_APP_VERSION)
            .header("X-dpr", "3.5")
            .header("X-app-machine", "2210132C")
            .header("X-app-system-version", "14")
            .header("X-mssvc-sec-ts", securityTimestamp)
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")

    private fun Session.Web.hasMobileCredentials(): Boolean =
        !userId.isNullOrBlank() && !accessId.isNullOrBlank() && !accessSecret.isNullOrBlank()

    private fun Session.Web.asMobileOrNull(): Session.Mobile? {
        if (!hasMobileCredentials()) return null
        return Session.Mobile(
            userId = requireNotNull(userId),
            accessId = requireNotNull(accessId),
            accessSecret = requireNotNull(accessSecret),
            lastSecUpdateTsS = lastSecUpdateTsS,
            displayName = displayName,
            schoolName = schoolName
        )
    }

    companion object {
        fun maskAccount(value: String): String = when {
            value.length <= 4 -> "***"
            else -> value.take(3) + "****" + value.takeLast(2)
        }

        const val CORE_API = "https://coreapi.mosoteach.cn"
        private const val WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"
        private const val LEGACY_MOBILE_USER_AGENT =
            "Dalvik/2.1.0 (Linux; U; Android 8.1.0; ONE A2001 Build/OPM7.181205.001)"
        private const val LEGACY_LOGIN_APP_VERSION = "5.1.1"
        private val JSON_MEDIA_TYPE = "application/json;charset=utf-8".toMediaType()
        private val TEXT_MEDIA_TYPE = "text/plain; charset=utf-8".toMediaType()
    }
}
