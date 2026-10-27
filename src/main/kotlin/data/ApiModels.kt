package `fun`.eternalblue.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

@Serializable
data class ApiResponse(
    val status: Boolean = false,
    @SerialName("result_code")
    val resultCode: Int? = null,
    @SerialName("result_msg")
    val resultMessage: String? = null,
    val token: String? = null,
    val user: ApiUser? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val requestId: String? = null,
    val data: JoinedCoursesData? = null,
    val clazzCourses: List<ClazzCourse> = emptyList()
) {
    val isSuccess: Boolean get() = status || resultCode == 0
    fun courses(): List<ClazzCourse> =
        (data?.clazzCourses ?: emptyList()) + clazzCourses
}

@Serializable
data class JoinedCoursesData(
    val clazzCourses: List<ClazzCourse> = emptyList()
)

@Serializable
data class ApiUser(
    @SerialName("userId")
    val userId: String? = null,
    @SerialName("user_id")
    val legacyUserId: String? = null,
    @SerialName("accessId")
    val accessId: String? = null,
    @SerialName("access_id")
    val legacyAccessId: String? = null,
    @SerialName("accessSecret")
    val accessSecret: String? = null,
    @SerialName("access_secret")
    val legacyAccessSecret: String? = null,
    @SerialName("fullName")
    val fullName: String? = null,
    @SerialName("full_name")
    val legacyFullName: String? = null,
    @SerialName("lastSecUpdateTsS")
    val lastSecUpdateTsS: String? = null,
    @SerialName("last_sec_update_ts_s")
    val legacyLastSecUpdateTsS: String? = null,
    @SerialName("schoolName")
    val schoolName: String? = null,
    @SerialName("school_name")
    val legacySchoolName: String? = null,
    val school: School? = null
) {
    val resolvedUserId: String? get() = userId ?: legacyUserId
    val resolvedAccessId: String? get() = accessId ?: legacyAccessId
    val resolvedAccessSecret: String? get() = accessSecret ?: legacyAccessSecret
    val resolvedFullName: String? get() = fullName ?: legacyFullName
    val resolvedLastSecUpdateTsS: String? get() = lastSecUpdateTsS ?: legacyLastSecUpdateTsS
    val resolvedSchoolName: String? get() = school?.name ?: schoolName ?: legacySchoolName
}

@Serializable
data class School(
    val name: String? = null
)

@Serializable
data class ClazzCourse(
    val id: String,
    val status: String = "OPEN",
    val course: Course? = null,
    val creater: Creator? = null,
    val createTime: String? = null
) {
    val displayName: String get() = course?.name ?: "未命名课程"
}

@Serializable
data class Creator(
    @SerialName("fullName")
    val fullName: String? = null,
    @SerialName("full_name")
    val legacyFullName: String? = null
) {
    val displayName: String get() = fullName ?: legacyFullName ?: "未知教师"
}

@Serializable(with = CheckinCurrentSerializer::class)
data class CheckinCurrent(
    val status: JsonElement? = null,
    val checkin: CheckinInfo? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null
) {
    constructor(
        status: Boolean,
        checkin: CheckinInfo? = null,
        errorCode: String? = null,
        errorMessage: String? = null
    ) : this(JsonPrimitive(status), checkin, errorCode, errorMessage)

    val isOpen: Boolean get() = isActive && checkin != null

    /**
     * Accepts boolean, 0/1 and "true"/"false"/"Y"/"N" so an unknown status shape
     * cannot abort the whole polling round.
     */
    val isActive: Boolean
        get() = when (val value = status) {
            null -> false
            is JsonPrimitive -> value.booleanOrNull
                ?: value.contentOrNull?.trim()?.lowercase()?.let {
                    it == "1" || it == "true" || it == "y" || it == "yes"
                }
                ?: false
            else -> false
        }
}

@Serializable(with = CheckinInfoSerializer::class)
data class CheckinInfo(
    val id: String? = null,
    val type: String? = null,
    val openTime: JsonElement? = null,
    val deadlineTime: JsonElement? = null,
    val checkinFlag: String? = null,
    val checkinTime: JsonElement? = null,
    /**
     * Gesture check-in (POINT_9) pattern pushed by the server, e.g. "1-4-7-8".
     * The Android client only compares it locally in LocusPassWordView; the
     * submit endpoint never receives it, so it is kept for logging only.
     */
    val points: String? = null
) {
    val normalizedType: String? get() = type?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }

    /** Server sets checkinFlag=Y once this account has signed. */
    val alreadySigned: Boolean get() = checkinFlag?.trim()?.equals("Y", ignoreCase = true) == true
}

/**
 * The current-checkin endpoint changes field types between app releases and while a
 * check-in opens. Decode only the fields needed to act, and ignore the rest.
 */
object CheckinInfoSerializer : kotlinx.serialization.KSerializer<CheckinInfo> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("CheckinInfo")

    override fun deserialize(decoder: Decoder): CheckinInfo {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("CheckinInfo 需要 JSON 解码器")
        val obj = input.decodeJsonElement().asJsonObjectOrNull()
            ?: return CheckinInfo()
        return CheckinInfo(
            id = obj.text("id"),
            type = obj.text("type"),
            openTime = obj["openTime"],
            deadlineTime = obj["deadlineTime"],
            checkinFlag = obj.text("checkinFlag"),
            checkinTime = obj["checkinTime"],
            points = obj.text("points")
        )
    }

    override fun serialize(encoder: Encoder, value: CheckinInfo) {
        val output = encoder as? JsonEncoder
            ?: throw SerializationException("CheckinInfo 需要 JSON 编码器")
        val obj = buildJsonObject {
            value.id?.let { put("id", it) }
            value.type?.let { put("type", it) }
            value.openTime?.let { put("openTime", it) }
            value.deadlineTime?.let { put("deadlineTime", it) }
            value.checkinFlag?.let { put("checkinFlag", it) }
            value.checkinTime?.let { put("checkinTime", it) }
            value.points?.let { put("points", it) }
        }
        output.encodeJsonElement(obj)
    }
}

object CheckinCurrentSerializer : kotlinx.serialization.KSerializer<CheckinCurrent> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("CheckinCurrent")

    override fun deserialize(decoder: Decoder): CheckinCurrent {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("CheckinCurrent 需要 JSON 解码器")
        val obj = input.decodeJsonElement().asJsonObjectOrNull()
            ?: return CheckinCurrent()
        val checkinElement = obj["checkin"]
        val checkin = when (checkinElement) {
            null -> null
            is JsonObject -> Json.decodeFromJsonElement(CheckinInfoSerializer, checkinElement)
            is JsonPrimitive -> checkinElement.contentOrNull
                ?.trim()
                ?.takeIf { it.startsWith("{") }
                ?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
                ?.asJsonObjectOrNull()
                ?.let { Json.decodeFromJsonElement(CheckinInfoSerializer, it) }
            else -> null
        }
        return CheckinCurrent(
            status = obj["status"],
            checkin = checkin,
            errorCode = obj.text("errorCode"),
            errorMessage = obj.text("errorMessage")
        )
    }

    override fun serialize(encoder: Encoder, value: CheckinCurrent) {
        val output = encoder as? JsonEncoder
            ?: throw SerializationException("CheckinCurrent 需要 JSON 编码器")
        val obj = buildJsonObject {
            value.status?.let { put("status", it) }
            value.checkin?.let { put("checkin", CheckinInfoSerializer.serializeToElement(it)) }
            value.errorCode?.let { put("errorCode", it) }
            value.errorMessage?.let { put("errorMessage", it) }
        }
        output.encodeJsonElement(obj)
    }
}

private fun CheckinInfoSerializer.serializeToElement(value: CheckinInfo): JsonElement =
    buildJsonObject {
        value.id?.let { put("id", it) }
        value.type?.let { put("type", it) }
        value.openTime?.let { put("openTime", it) }
        value.deadlineTime?.let { put("deadlineTime", it) }
        value.checkinFlag?.let { put("checkinFlag", it) }
        value.checkinTime?.let { put("checkinTime", it) }
        value.points?.let { put("points", it) }
    }

private fun JsonElement.asJsonObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

@Serializable
data class SubmitResult(
    val status: Boolean = false,
    val result_code: Int? = null,
    @SerialName("resultCode")
    val resultCode: Int? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val message: String? = null
) {
    val code: Int? get() = result_code ?: resultCode
}
