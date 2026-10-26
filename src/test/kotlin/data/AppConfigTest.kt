package `fun`.eternalblue.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Files

class AppConfigTest {
    @Test
    fun parsesAllOptions() {
        val config = AppConfig.parse(
            arrayOf(
                "--account", "user@example.com",
                "--interval", "15",
                "--once",
                "--no-auto-checkin"
            )
        )

        assertEquals("user@example.com", config.account)
        assertEquals(15, config.intervalSeconds)
        assertTrue(config.once)
        assertFalse(config.autoCheckin)
    }

    @Test
    fun usesSafeDefaults() {
        val config = AppConfig.parse(emptyArray())

        assertEquals(null, config.password)
        assertEquals(AppConfig.DEFAULT_INTERVAL_SECONDS, config.intervalSeconds)
        assertFalse(config.once)
        assertTrue(config.autoCheckin)
        assertFalse(config.debug)
        assertEquals(AppConfig.COURSE_DELAY_MILLIS, config.courseDelayMillis)
    }

    @Test
    fun loadsTomlConfig() {
        withToml(
            """
            username = "toml-user"
            password = "toml#password"
            check_interval = 45
            """.trimIndent()
        ) { file ->
            val config = AppConfig.load(emptyArray(), file)
            assertEquals("toml-user", config.account)
            assertEquals("toml#password", config.password)
            assertEquals(45, config.intervalSeconds)
        }
    }

    @Test
    fun handlesBomCommentsAndPasswordEscapes() {
        withToml(
            "\uFEFF" + """
            username = 'toml-user' # account comment
            password = "quote\"slash\\n#value" # password comment
            check_interval = 20 # seconds
            """.trimIndent()
        ) { file ->
            val config = AppConfig.load(emptyArray(), file)
            assertEquals("toml-user", config.account)
            assertEquals("quote\"slash\\n#value", config.password)
            assertEquals(20, config.intervalSeconds)
        }
    }

    @Test
    fun explicitIntervalOverridesFileWhilePreservingCredentials() {
        withToml(
            """
            username = "file-user"
            password = "file-password"
            check_interval = 20
            """.trimIndent()
        ) { file ->
            val config = AppConfig.load(arrayOf("--interval", "12"), file)
            assertEquals(12, config.intervalSeconds)
            assertEquals("file-user", config.account)
            assertEquals("file-password", config.password)
        }
    }

    @Test
    fun rejectsInvalidFileIntervalAndUnknownKeys() {
        for (value in listOf("9", "2.5", "true", "9223372036854775807", "\"bad-value\"")) {
            withToml("check_interval = $value") { file ->
                assertFailsWith<IllegalArgumentException> { AppConfig.load(emptyArray(), file) }
            }
        }
        withToml("check_intervel = 20") { file ->
            assertFailsWith<IllegalArgumentException> { AppConfig.load(emptyArray(), file) }
        }
    }

    @Test
    fun syntaxErrorsDoNotExposePassword() {
        withToml("password = \"private-test-password\ncheck_interval = 20") { file ->
            val error = assertFailsWith<IllegalArgumentException> {
                AppConfig.load(emptyArray(), file)
            }
            assertFalse(error.message.orEmpty().contains("private-test-password"))
        }
    }

    @Test
    fun loadsDebugFromTomlAndCommandLine() {
        withToml("debug = true") { file ->
            val config = AppConfig.load(emptyArray(), file)
            assertTrue(config.debug)
            assertEquals(AppConfig.DEBUG_COURSE_DELAY_MILLIS, config.courseDelayMillis)
        }
        withToml("debug = false") { file ->
            assertFalse(AppConfig.load(emptyArray(), file).debug)
            assertTrue(AppConfig.load(arrayOf("--debug"), file).debug)
        }
        withToml("debug = \"yes\"") { file ->
            assertTrue(AppConfig.load(emptyArray(), file).debug)
        }
        withToml("debug = maybe") { file ->
            assertFailsWith<IllegalArgumentException> { AppConfig.load(emptyArray(), file) }
        }
    }

    @Test
    fun loadsLocationFromTomlAndCommandLine() {
        withToml("location = \"121.482388,29.885685\"") { file ->
            val config = AppConfig.load(emptyArray(), file)
            assertTrue(config.hasLocation)
            assertEquals("121.482388,29.885685", config.locationText)
            assertEquals(121.482388, config.longitude)
            assertEquals(29.885685, config.latitude)
        }
        withToml("location = \" 120.5 ， 30.5 \"") { file ->
            val config = AppConfig.load(emptyArray(), file)
            assertEquals(30.5, config.latitude)
            assertEquals(120.5, config.longitude)
        }
        withToml("location = 30.0") { file ->
            assertFailsWith<IllegalArgumentException> { AppConfig.load(emptyArray(), file) }
        }
        withToml("location = \"120.0\"") { file ->
            assertFailsWith<IllegalArgumentException> { AppConfig.load(emptyArray(), file) }
        }
        withToml("location = \"120.0,91.0\"") { file ->
            assertFailsWith<IllegalArgumentException> { AppConfig.load(emptyArray(), file) }
        }
        withToml("location = \"north,30.0\"") { file ->
            assertFailsWith<IllegalArgumentException> { AppConfig.load(emptyArray(), file) }
        }
        val override = AppConfig.parse(arrayOf("--location", "114.0,22.5"))
        assertEquals(22.5, override.latitude)
        assertEquals(114.0, override.longitude)
    }

    @Test
    fun resolvesPerCourseLocationByPriority() {
        withToml(
            """
            location = "120.0,30.0"

            [[locations]]
            course = "算法设计与分析"
            location = "121.0,31.0"

            [[locations]]
            course_id = "F9CF052C-AD94-11F1-BAE9-A088C2A30E68"
            location = "123.0,33.0"
            """.trimIndent()
        ) { file ->
            val config = AppConfig.load(emptyArray(), file)
            assertEquals(2, config.locations.size)

            val byId = course(
                id = "f9cf052c-ad94-11f1-bae9-a088c2a30e68",
                name = "算法设计与分析"
            )
            assertEquals("123.000000,33.000000", config.locationFor(byId)?.text)
            assertEquals("course_id=F9CF052C-AD94-11F1-BAE9-A088C2A30E68", config.locationSourceFor(byId))

            val byName = course(
                id = "OTHER",
                name = " 算法设计与分析 "
            )
            assertEquals("121.000000,31.000000", config.locationFor(byName)?.text)
            assertEquals("course=算法设计与分析", config.locationSourceFor(byName))

            val fallback = course(id = "OTHER-3", name = "大学物理")
            assertEquals("120.000000,30.000000", config.locationFor(fallback)?.text)
            assertEquals("全局默认", config.locationSourceFor(fallback))
        }
    }

    @Test
    fun noLocationMeansEmptyCoordinates() {
        val config = AppConfig()
        assertEquals(null, config.locationFor(course("X", "任意课程")))
        assertEquals(null, config.locationSourceFor(course("X", "任意课程")))
    }

    @Test
    fun rejectsInvalidLocationBlocks() {
        val invalid = listOf(
            """
            [[locations]]
            course = "A"
            """,
            """
            [[locations]]
            course = "A"
            course_id = "B"
            location = "120.0,30.0"
            """,
            """
            [[locations]]
            course = "A"
            location = "120.0,91.0"
            """,
            """
            [[locations]]
            course = "A"
            location = "north,30.0"
            """,
            """
            [[locations]]
            course = "A"
            location = "120.0,30.0"
            [[locations]]
            course = " a "
            location = "121.0,31.0"
            """,
            """
            [[locations]]
            course = "A"
            location = "120.0,30.0"
            unknown = 1
            """,
            """
            [[locations]]
            class_name = "A"
            location = "120.0,30.0"
            """,
            """
            [[locations]]
            course = "A"
            latitude = 30.0
            longitude = 120.0
            """
        )
        invalid.forEach { content ->
            withToml(content) { file ->
                assertFailsWith<IllegalArgumentException> { AppConfig.load(emptyArray(), file) }
            }
        }
        withToml(
            """
            [[locations]]
            course = "A"
            latitude = 30.0
            longitude = 120.0
            """.trimIndent()
        ) { file ->
            val error = assertFailsWith<IllegalArgumentException> {
                AppConfig.load(emptyArray(), file)
            }
            assertTrue(error.message.orEmpty().contains("location"))
        }
    }

    @Test
    fun rejectsShortIntervalAndMissingValues() {
        assertFailsWith<IllegalArgumentException> {
            AppConfig.parse(arrayOf("--interval", "9"))
        }
        assertFailsWith<IllegalArgumentException> {
            AppConfig.parse(arrayOf("--account"))
        }
        assertFailsWith<IllegalArgumentException> {
            AppConfig.parse(arrayOf("--unknown"))
        }
        assertFailsWith<IllegalArgumentException> {
            AppConfig.parse(arrayOf("--location"))
        }
        assertFailsWith<IllegalArgumentException> {
            AppConfig.parse(arrayOf("--location", "120.0"))
        }
        val legacyFlag = assertFailsWith<IllegalArgumentException> {
            AppConfig.parse(arrayOf("--lat", "30.0", "--lng", "120.0"))
        }
        assertTrue(legacyFlag.message.orEmpty().contains("--location"))
        val passwordError = assertFailsWith<IllegalArgumentException> {
            AppConfig.parse(arrayOf("--password", "secret"))
        }
        assertTrue(passwordError.message.orEmpty().contains("命令行"))
    }

    private fun withToml(content: String, block: (java.io.File) -> Unit) {
        val file = Files.createTempFile("autosign", ".toml").toFile()
        try {
            file.writeText(content)
            block(file)
        } finally {
            file.delete()
        }
    }

    private fun course(id: String, name: String) = ClazzCourse(
        id = id,
        course = Course(name = name)
    )
}
