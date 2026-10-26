package `fun`.eternalblue.util

import okhttp3.Request
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Debug capture for check-in hunting.
 *
 * Writes redacted HTTP traffic and check-in snapshots under logs/debug/.
 * Never records account passwords, tokens, signatures or secrets.
 */
object Debug {
    @Volatile
    var enabled: Boolean = false
        private set

    private var debugDir: File = File("logs/debug")
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val fileTimeFormat = DateTimeFormatter.ofPattern("HHmmss-SSS")
    private val lock = Any()
    private val loggedOnce = HashSet<String>()

    fun configure(enabled: Boolean, baseDir: File) {
        this.enabled = enabled
        debugDir = File(File(baseDir, "logs"), "debug")
        if (enabled) {
            debugDir.mkdirs()
            synchronized(lock) {
                // Remove only files this program writes; leave anything else untouched.
                debugDir.listFiles()
                    ?.filter { it.isFile && (it.name == "http.log" || it.extension == "json") }
                    ?.forEach { it.delete() }
                File(debugDir, "http.log").writeText("")
                loggedOnce.clear()
            }
        }
    }

    fun line(message: String) {
        if (!enabled) return
        println("[调试] $message")
        Log.debug(message)
    }

    fun http(
        request: Request,
        statusCode: Int,
        elapsedMillis: Long,
        responseBody: String,
        tag: String? = null
    ) {
        if (!enabled) return
        val path = request.url.encodedPath
        line(
            (tag?.let { "[$it] " } ?: "") +
                "HTTP $statusCode ${request.method} $path（${elapsedMillis}ms，${responseBody.length} 字节）"
        )

        // Login traffic carries credentials and encrypted payloads; keep metadata only.
        if (path.contains("login", ignoreCase = true)) return

        // Large, repetitive list responses are captured once per run to keep the log usable.
        val onceOnly = path == "/ccs/joined"
        if (onceOnly) {
            val firstTime = synchronized(lock) { loggedOnce.add("$tag|$path") }
            if (!firstTime) return
        }

        val entry = buildString {
            appendLine(
                "[${LocalTime.now().format(timeFormat)}] " +
                    (tag?.let { "[$it] " } ?: "") +
                    "${request.method} $path -> $statusCode (${elapsedMillis}ms)"
            )
            request.url.query?.let { appendLine("QUERY: ${redact(it)}") }
            request.bodyText()?.takeIf { it.isNotBlank() }?.let {
                appendLine("REQUEST: ${redact(it)}")
            }
            val body = if (onceOnly) responseBody else responseBody.take(MAX_BODY_CHARS)
            appendLine("RESPONSE: ${redact(body)}")
            appendLine()
        }
        synchronized(lock) {
            File(debugDir, "http.log").appendText(entry)
        }
    }

    fun snapshot(name: String, content: String) {
        if (!enabled) return
        val safeName = name.replace(Regex("""[\\/:*?"<>|\x00-\x1F]"""), "_").take(80)
        val file = File(debugDir, "$safeName-${LocalTime.now().format(fileTimeFormat)}.json")
        synchronized(lock) {
            file.writeText(redact(content))
        }
        line("已保存抓包：${file.path}")
    }

    /** Captures only responses worth inspecting, so long debug runs stay small. */
    fun snapshotCheckin(courseId: String, courseName: String, content: String) {
        if (!enabled) return
        snapshot("checkin-${courseName.take(20)}-$courseId", content)
    }

    fun redact(text: String): String {
        var result = text
        // JSON string fields: password/token/secret/signature/security/cookie.
        result = Regex(
            """(?i)("(?:[^"]*(?:password|passwd|token|secret|signature|authorization|cookie|security)[^"]*)"\s*:\s*)"(?:\\.|[^"\\])*""""
        ).replace(result) { match -> "${match.groupValues[1]}\"***\"" }
        // Form bodies and headers.
        result = Regex(
            """(?i)\b(password|passwd|token|access_secret|accessSecret|signature|security)=([^&\s]+)"""
        ).replace(result) { match -> "${match.groupValues[1]}=***" }
        result = Regex(
            """(?i)((?:x-mssvc-signature|x-signature|x-token|x-security|authorization|cookie)\s*[:=]\s*)[^\r\n,}]+"""
        ).replace(result) { match -> "${match.groupValues[1]}***" }
        return result
    }

    private fun Request.bodyText(): String? = body?.let { requestBody ->
        runCatching {
            val buffer = okio.Buffer()
            requestBody.writeTo(buffer)
            buffer.readUtf8()
        }.getOrNull()
    }

    private const val MAX_BODY_CHARS = 4000
}
