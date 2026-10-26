package `fun`.eternalblue.net

import `fun`.eternalblue.util.Log
import `fun`.eternalblue.util.Debug
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class ApiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
    /** Account label used to keep interleaved multi-account logs attributable. */
    private val tag: String? = null
) {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        explicitNulls = false
    }

    fun execute(request: Request): String {
        val startedAt = System.nanoTime()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
                if (!response.isSuccessful) {
                    throw ApiException(
                        "服务器返回 HTTP ${response.code}${errorSuffix(body)}"
                    )
                }
                if (body.isBlank()) {
                    throw ApiException("服务器返回了空响应")
                }
                if (looksLikeHtml(body)) {
                    throw ApiException("接口返回了网页而不是 JSON，登录地址或接口可能已经变更")
                }
                Log.debug(tag?.let { "[$it] HTTP ${response.code} ${request.url.encodedPath}" }
                    ?: "HTTP ${response.code} ${request.url.encodedPath}")
                Debug.http(request, response.code, elapsedMillis, body, tag)
                return body
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: IOException) {
            throw ApiException("网络请求失败：${e.message ?: e::class.simpleName}", e)
        }
    }

    fun parseObject(body: String, endpoint: String): JsonElement {
        return try {
            json.parseToJsonElement(body).also {
                if (it !is kotlinx.serialization.json.JsonObject) {
                    throw ApiException("$endpoint 返回的 JSON 顶层不是对象")
                }
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException("$endpoint 返回了无法解析的 JSON", e)
        }
    }

    fun errorMessage(body: String): String? {
        return runCatching {
            val obj = json.parseToJsonElement(body).jsonObject
            obj["errorMessage"]?.jsonPrimitive?.content
                ?: obj["message"]?.jsonPrimitive?.content
        }.getOrNull()
    }

    fun jsonBody(value: String) = value.toRequestBody(JSON_MEDIA_TYPE)

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun looksLikeHtml(body: String): Boolean {
            val start = body.trimStart().take(64).lowercase()
            return start.startsWith("<!doctype") || start.startsWith("<html") ||
                start.startsWith("<?xml") || start.startsWith("<head")
        }

        private fun errorSuffix(body: String): String {
            if (body.isBlank() || looksLikeHtml(body)) return ""
            return "：${body.trim().take(160)}"
        }
    }
}
