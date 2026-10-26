package `fun`.eternalblue.data

import java.io.File
import java.util.Locale
import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlTable

data class SignLocation(
    val longitude: Double,
    val latitude: Double
) {
    init {
        require(longitude in -180.0..180.0) { "经度必须在 -180 到 180 之间" }
        require(latitude in -90.0..90.0) { "纬度必须在 -90 到 90 之间" }
    }

    /** Same order as the config value: 经度,纬度. */
    val text: String get() = "%.6f,%.6f".format(Locale.ROOT, longitude, latitude)

    companion object {
        /** Parses "经度,纬度", the order used by 高德/腾讯 map coordinate pickers. */
        fun parse(raw: String, where: String): SignLocation {
            val parts = raw.split(',', '，').map { it.trim() }
            require(parts.size == 2 && parts.all { it.isNotEmpty() }) {
                "$where 必须是「经度,纬度」，例如 121.482388,29.885685"
            }
            val longitude = parts[0].toDoubleOrNull()
                ?: throw IllegalArgumentException("$where 的经度必须是数字")
            val latitude = parts[1].toDoubleOrNull()
                ?: throw IllegalArgumentException("$where 的纬度必须是数字")
            require(longitude in -180.0..180.0) { "$where 的经度必须在 -180 到 180 之间" }
            require(latitude in -90.0..90.0) { "$where 的纬度必须在 -90 到 90 之间" }
            return SignLocation(longitude = longitude, latitude = latitude)
        }
    }
}

data class CourseLocation(
    val location: SignLocation,
    val courseId: String? = null,
    val courseName: String? = null
) {
    init {
        require(listOfNotNull(courseId, courseName).size == 1) {
            "每个 [[locations]] 只能填写 course_id 或 course 中的一项"
        }
    }

    val selector: String
        get() = when {
            courseId != null -> "course_id=$courseId"
            else -> "course=$courseName"
        }
}

data class AccountConfig(
    val username: String,
    val password: String? = null,
    val name: String? = null,
    val location: SignLocation? = null,
    val locations: List<CourseLocation> = emptyList()
) {
    init {
        require(username.isNotBlank()) { "accounts 的 username 不能为空" }
    }
}

data class AppConfig(
    val account: String? = null,
    val password: String? = null,
    val label: String? = null,
    val intervalSeconds: Long = DEFAULT_INTERVAL_SECONDS,
    val location: SignLocation? = null,
    val locations: List<CourseLocation> = emptyList(),
    val accounts: List<AccountConfig> = emptyList(),
    val once: Boolean = false,
    val autoCheckin: Boolean = true,
    val debug: Boolean = false,
    val help: Boolean = false,
    val version: Boolean = false
) {
    /** Debug mode should not waste 300ms per course while hunting a check-in. */
    val courseDelayMillis: Long get() = if (debug) DEBUG_COURSE_DELAY_MILLIS else COURSE_DELAY_MILLIS

    init {
        require(intervalSeconds >= MIN_INTERVAL_SECONDS && intervalSeconds <= Long.MAX_VALUE / 1000) {
            "检查间隔必须是大于等于 $MIN_INTERVAL_SECONDS 的整数秒，且不能超过 ${Long.MAX_VALUE / 1000}"
        }
    }

    val latitude: Double? get() = location?.latitude

    val longitude: Double? get() = location?.longitude

    val hasLocation: Boolean get() = location != null

    val locationText: String
        get() = location?.text ?: "未配置"

    val isMultiAccount: Boolean get() = accounts.isNotEmpty()

    /**
     * Expands [[accounts]] into independent per-account configs. Account-level
     * coordinates and locations override the global defaults.
     */
    fun effectiveAccounts(): List<AppConfig> {
        if (accounts.isEmpty()) return listOf(this)
        return accounts.mapIndexed { index, account ->
            copy(
                account = account.username,
                password = account.password,
                label = account.name?.takeIf { it.isNotBlank() } ?: "账号${index + 1}",
                location = account.location ?: location,
                locations = account.locations + locations,
                accounts = emptyList()
            )
        }
    }

    /**
     * Per-course coordinates take precedence over the global latitude/longitude.
     * Matching order: course_id, then course name.
     */
    fun locationFor(course: ClazzCourse): SignLocation? {
        val id = course.id.trim()
        locations.firstOrNull {
            it.courseId != null && it.courseId.equals(id, ignoreCase = true)
        }?.let { return it.location }

        val courseName = normalizeName(course.displayName)
        locations.firstOrNull {
            it.courseName != null && normalizeName(it.courseName) == courseName
        }?.let { return it.location }

        return location
    }

    fun locationSourceFor(course: ClazzCourse): String? {
        val id = course.id.trim()
        locations.firstOrNull {
            it.courseId != null && it.courseId.equals(id, ignoreCase = true)
        }?.let { return it.selector }
        val courseName = normalizeName(course.displayName)
        locations.firstOrNull {
            it.courseName != null && normalizeName(it.courseName) == courseName
        }?.let { return it.selector }
        return if (hasLocation) "全局默认" else null
    }

    companion object {
        const val DEFAULT_INTERVAL_SECONDS = 30L
        const val MIN_INTERVAL_SECONDS = 10L
        const val COURSE_DELAY_MILLIS = 300L
        const val DEBUG_COURSE_DELAY_MILLIS = 100L

        val USAGE = """
            云班课自动签到 2.0.0

            用法：
              java -jar autosign.jar [选项]

            默认读取 JAR 同目录下的 config.toml（修改后重启生效）：
              username = "你的账号"
              password = "你的密码"
              check_interval = 30
              debug = false

            选项：
              --account <账号>       手机号或邮箱账号
              --interval <秒>        覆盖配置文件的轮询间隔，最小 10 秒
              --once                 只检查一轮，不持续轮询
              --no-auto-checkin      只查询签到状态，不自动签到
              --debug               调试模式：记录每个班课状态和脱敏抓包
              --location <经度,纬度>  覆盖签到定位
              --help                 显示帮助
              --version              显示版本

            密码不会从命令行参数读取。程序会在需要时安全提示输入；
            也可使用环境变量 YBK_ACCOUNT、YBK_PASSWORD。配置文件中的密码不会打印到日志。

            可选：为普通签到（SIMPLE / POINT_9）设置自定义定位。
            一个值写「经度,纬度」，坐标使用 GCJ-02（高德/腾讯地图）：
              location = "121.482388,29.885685"

            可选：为不同班课分别设置普通签到定位。
            每个 [[locations]] 用 course_id 或 course（课程名）指定一个班课，
            匹配优先级为 course_id、course：
              [[locations]]
              course = "算法设计与分析"
              location = "121.482388,29.885685"

            可选：多账户。与单账户 username/password 二选一，不能同时配置。
            每个 [[accounts]] 必须有 username 和 password；name 仅用于控制台
            和日志标识，默认“账号1”“账号2”。各账户可单独设置 location，
            以及 [[accounts.locations]] 班课定位：
              [[accounts]]
              name = "主账号"
              username = "账号1"
              password = "密码1"

              [[accounts]]
              name = "小号"
              username = "账号2"
              password = "密码2"
              location = "121.473701,31.230416"

              [[accounts.locations]]
              course = "算法设计与分析"
              location = "121.000000,31.000000"
        """.trimIndent()

        fun load(args: Array<String>, configFile: File? = null): AppConfig {
            val file = configFile ?: defaultConfigFile()
            return parse(args, readToml(file))
        }

        fun parse(args: Array<String>, defaults: AppConfig = AppConfig()): AppConfig {
            var account = defaults.account
            val password = defaults.password
            val label = defaults.label
            var interval = defaults.intervalSeconds
            var location = defaults.location
            val locations = defaults.locations
            val accounts = defaults.accounts
            var once = defaults.once
            var autoCheckin = defaults.autoCheckin
            var debug = defaults.debug
            var help = false
            var version = false
            var accountOverridden = false
            var locationOverridden = false
            var index = 0

            fun valueFor(option: String): String {
                if (index + 1 >= args.size || args[index + 1].startsWith("--")) {
                    throw IllegalArgumentException("$option 缺少参数")
                }
                index += 1
                return args[index]
            }

            while (index < args.size) {
                when (val option = args[index]) {
                    "--account" -> {
                        account = valueFor(option).trim().takeIf { it.isNotEmpty() }
                            ?: throw IllegalArgumentException("--account 不能为空")
                        accountOverridden = true
                    }
                    "--password" -> throw IllegalArgumentException(
                        "不支持在命令行中传递密码，请使用 config.toml、安全提示输入或 YBK_PASSWORD"
                    )
                    "--interval" -> {
                        val raw = valueFor(option)
                        interval = raw.toLongOrNull()
                            ?: throw IllegalArgumentException("--interval 必须是整数秒")
                        require(interval >= MIN_INTERVAL_SECONDS) {
                            "--interval 不能小于 $MIN_INTERVAL_SECONDS 秒"
                        }
                    }
                    "--location", "--latlng" -> {
                        location = SignLocation.parse(valueFor(option), option)
                        locationOverridden = true
                    }
                    "--lat", "--latitude", "--lng", "--lon", "--longitude" ->
                        throw IllegalArgumentException(
                            "$option 已合并为 --location，请写成 --location 经度,纬度"
                        )
                    "--once" -> once = true
                    "--no-auto-checkin" -> autoCheckin = false
                    "--debug" -> debug = true
                    "--help", "-h" -> help = true
                    "--version", "-v" -> version = true
                    else -> throw IllegalArgumentException("未知参数：$option")
                }
                index += 1
            }

            require(!(accountOverridden && accounts.isNotEmpty())) {
                "已配置 [[accounts]] 时不能再用 --account 指定单个账号"
            }

            return AppConfig(
                account = account,
                password = password,
                label = label,
                intervalSeconds = interval,
                location = location,
                locations = locations,
                // An explicit --location wins over every account's own value.
                accounts = if (locationOverridden) {
                    accounts.map { it.copy(location = location) }
                } else {
                    accounts
                },
                once = once,
                autoCheckin = autoCheckin,
                debug = debug,
                help = help,
                version = version
            )
        }

        private fun readToml(file: File): AppConfig {
            if (!file.exists()) return AppConfig()

            val parsed = Toml.parse(file.readText(Charsets.UTF_8).removePrefix("\uFEFF"))
            require(!parsed.hasErrors()) {
                // Parser errors may contain credentials, so only report their position.
                "${file.name} 格式错误，位置：${parsed.errors().first().position()}"
            }
            val allowedKeys = setOf(
                "username", "account", "password", "passwd",
                "check_interval", "interval", "interval_seconds", "debug",
                "location", "locations", "accounts"
            )
            val unknownTopLevel = parsed.keySet().filter { it !in allowedKeys }
            require(unknownTopLevel.isEmpty()) {
                val legacy = unknownTopLevel.filter { it in LEGACY_COORDINATE_KEYS }
                if (legacy.isNotEmpty()) {
                    "${file.name} 的 ${legacy.joinToString()} 已合并为 location，" +
                        "请写成 location = \"经度,纬度\"，例如 location = \"121.482388,29.885685\""
                } else {
                    "${file.name} 含有未知配置项：${unknownTopLevel.joinToString()}，请参照 config.example.toml"
                }
            }

            fun value(vararg keys: String): Any? {
                val presentKeys = keys.filter { parsed.contains(it) }
                require(presentKeys.size <= 1) { "${file.name} 的同一配置项不能重复设置" }
                return presentKeys.singleOrNull()?.let { parsed.get(it) }
            }

            fun string(vararg keys: String): String? {
                val value = value(*keys) ?: return null
                require(value is String) { "${file.name} 的 ${keys.first()} 必须是带引号的字符串" }
                return value.takeIf { it.isNotEmpty() }
            }

            val intervalValue = value("check_interval", "interval", "interval_seconds")
            val interval = when (intervalValue) {
                null -> DEFAULT_INTERVAL_SECONDS
                is Long -> intervalValue
                is String -> intervalValue.toLongOrNull()
                else -> null
            } ?: throw IllegalArgumentException("${file.name} 的 check_interval 必须是整数秒")

            val debugValue = value("debug")
            val debug = when (debugValue) {
                null -> false
                is Boolean -> debugValue
                is String -> when (debugValue.trim().lowercase()) {
                    "true", "1", "yes", "y", "on" -> true
                    "false", "0", "no", "n", "off" -> false
                    else -> throw IllegalArgumentException("${file.name} 的 debug 必须是 true 或 false")
                }
                else -> throw IllegalArgumentException("${file.name} 的 debug 必须是 true 或 false")
            }

            val locations = parseLocations(parsed.getArray("locations"), file)
            val accounts = parseAccounts(parsed.getArray("accounts"), file)
            val singleAccount = string("username", "account")?.trim()?.takeIf { it.isNotEmpty() }
            val singlePassword = string("password", "passwd")
            require(accounts.isEmpty() || singleAccount == null) {
                "${file.name} 不能同时配置 username/password 和 [[accounts]]，请只保留一种"
            }
            require(accounts.isEmpty() || singlePassword == null) {
                "${file.name} 不能同时配置 username/password 和 [[accounts]]，请只保留一种"
            }

            return AppConfig(
                account = singleAccount,
                password = singlePassword,
                intervalSeconds = interval,
                location = parseLocation(value("location"), file, "location"),
                locations = locations,
                accounts = accounts,
                debug = debug
            )
        }

        private fun parseAccounts(array: TomlArray?, file: File): List<AccountConfig> {
            if (array == null) return emptyList()
            require(array.size() > 0) { "${file.name} 的 [[accounts]] 不能为空" }
            val result = mutableListOf<AccountConfig>()
            for (index in 0 until array.size()) {
                val where = "第 ${index + 1} 个 [[accounts]]"
                val table = array.getTable(index)
                    ?: throw IllegalArgumentException("${file.name} 的 $where 必须是表")
                val allowed = setOf(
                    "name", "username", "account", "password", "passwd",
                    "location", "locations"
                )
                require(table.keySet().all { it in allowed }) {
                    val legacy = table.keySet().filter { it in LEGACY_COORDINATE_KEYS }
                    if (legacy.isNotEmpty()) {
                        "${file.name} 的 $where 的 ${legacy.joinToString()} 已合并为 location，" +
                            "请写成 location = \"经度,纬度\""
                    } else {
                        "${file.name} 的 $where 含有未知配置项"
                    }
                }
                fun text(vararg keys: String): String? {
                    val present = keys.filter { table.contains(it) }
                    require(present.size <= 1) { "${file.name} 的 $where 的 ${keys.first()} 重复设置" }
                    val raw = present.singleOrNull()?.let { table.get(it) } ?: return null
                    require(raw is String) { "${file.name} 的 $where 的 ${keys.first()} 必须是字符串" }
                    return raw.trim().takeIf { it.isNotEmpty() }
                }
                val username = text("username", "account")
                    ?: throw IllegalArgumentException("${file.name} 的 $where 缺少 username")
                val password = text("password", "passwd")
                    ?: throw IllegalArgumentException("${file.name} 的 $where 缺少 password")
                result += AccountConfig(
                    username = username,
                    password = password,
                    name = text("name"),
                    location = parseLocation(table.get("location"), file, where),
                    locations = parseLocations(table.getArray("locations"), file, where)
                )
            }
            val duplicate = result.groupBy { it.username.trim().lowercase(Locale.ROOT) }
                .entries.firstOrNull { it.value.size > 1 }
            require(duplicate == null) {
                "${file.name} 的 [[accounts]] 重复配置了账号 ${duplicate?.value?.first()?.username}"
            }
            return result
        }

        private fun parseLocations(
            array: TomlArray?,
            file: File,
            scope: String? = null
        ): List<CourseLocation> {
            if (array == null) return emptyList()
            val result = mutableListOf<CourseLocation>()
            for (index in 0 until array.size()) {
                val table = array.getTable(index)
                    ?: throw IllegalArgumentException(
                        "${file.name} 的${scope?.let { "$it 中" } ?: ""}第 ${index + 1} 个 " +
                            "[[locations]] 必须是表"
                    )
                val where = "${scope?.let { "$it 中" } ?: ""}第 ${index + 1} 个 [[locations]]"
                val allowed = setOf("course_id", "course", "location")
                require(table.keySet().all { it in allowed }) {
                    val legacy = table.keySet().filter { it in LEGACY_COORDINATE_KEYS }
                    if (legacy.isNotEmpty()) {
                        "${file.name} 的 $where 的 ${legacy.joinToString()} 已合并为 location，" +
                            "请写成 location = \"经度,纬度\""
                    } else {
                        "${file.name} 的 $where 含有未知配置项"
                    }
                }
                val location = parseLocation(table.get("location"), file, where)
                    ?: throw IllegalArgumentException("${file.name} 的 $where 缺少 location")
                result += CourseLocation(
                    location = location,
                    courseId = locationText(table, "course_id", file, where),
                    courseName = locationText(table, "course", file, where)
                )
            }
            val duplicate = result.groupBy {
                when {
                    it.courseId != null -> "course_id:${it.courseId.lowercase()}"
                    it.courseName != null -> "course:${normalizeName(it.courseName)}"
                    else -> "invalid"
                }
            }.entries.firstOrNull { it.value.size > 1 }
            require(duplicate == null) {
                "${file.name} 的 [[locations]] 重复配置了 ${duplicate?.value?.first()?.selector}"
            }
            return result
        }

        private fun locationText(
            table: TomlTable,
            key: String,
            file: File,
            where: String
        ): String? {
            val raw = table.get(key) ?: return null
            require(raw is String) { "${file.name} 的 $where 的 $key 必须是字符串" }
            return raw.trim().takeIf { it.isNotEmpty() }
        }

        private fun parseLocation(raw: Any?, file: File, where: String): SignLocation? {
            if (raw == null) return null
            require(raw is String) {
                "${file.name} 的 $where 必须是「经度,纬度」字符串，例如 \"121.482388,29.885685\""
            }
            return SignLocation.parse(raw, "${file.name} 的 $where")
        }

        private val LEGACY_COORDINATE_KEYS = setOf(
            "latitude", "longitude", "lat", "lng"
        )

        private fun normalizeName(value: String): String =
            value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

        fun defaultConfigFile(): File {
            val codeSource = runCatching {
                File(AppConfig::class.java.protectionDomain.codeSource.location.toURI())
            }.getOrNull()
            return if (codeSource?.isFile == true) {
                File(codeSource.parentFile, "config.toml")
            } else {
                File("config.toml")
            }.absoluteFile
        }
    }
}
