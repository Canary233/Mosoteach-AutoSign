package `fun`.eternalblue.data

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppConfigTest {
    @Test
    fun parsesAllOptions() {
        val config = AppConfig.parse(
            arrayOf("--interval", "15", "--once", "--no-auto-checkin")
        )

        assertEquals(15, config.intervalSeconds)
        assertTrue(config.once)
        assertFalse(config.autoCheckin)
    }

    @Test
    fun usesSafeDefaults() {
        val config = AppConfig.parse(emptyArray())

        assertTrue(config.accounts.isEmpty())
        assertEquals(AppConfig.DEFAULT_INTERVAL_SECONDS, config.intervalSeconds)
        assertFalse(config.once)
        assertTrue(config.autoCheckin)
        assertFalse(config.debug)
        assertEquals(
            AppConfig.COURSE_DELAY_MILLIS,
            AccountOptions(label = "账号1", account = "u", password = "p").courseDelayMillis
        )
    }

    @Test
    fun loadsSingleAccountFromToml() {
        withToml(
            """
            check_interval = 45

            [[accounts]]
            username = "toml-user"
            password = "toml#password"
            """.trimIndent()
        ) { file ->
            val config = AppConfig.load(emptyArray(), file)
            assertEquals(45, config.intervalSeconds)
            val account = config.effectiveAccounts().single()
            assertEquals("账号1", account.label)
            assertEquals("toml-user", account.account)
            assertEquals("toml#password", account.password)
        }
    }

    @Test
    fun handlesBomCommentsAndPasswordEscapes() {
        withToml(
            "\uFEFF" + """
            check_interval = 20 # seconds

            [[accounts]]
            username = 'toml-user' # account comment
            password = "quote\"slash\\n#value" # password comment
            """.trimIndent()
        ) { file ->
            val config = AppConfig.load(emptyArray(), file)
            assertEquals(20, config.intervalSeconds)
            assertEquals("quote\"slash\\n#value", config.effectiveAccounts().single().password)
        }
    }

    @Test
    fun explicitIntervalOverridesFileWhilePreservingCredentials() {
        withToml(
            """
            check_interval = 20

            [[accounts]]
            username = "file-user"
            password = "file-password"
            """.trimIndent()
        ) { file ->
            val config = AppConfig.load(arrayOf("--interval", "12"), file)
            assertEquals(12, config.intervalSeconds)
            val account = config.effectiveAccounts().single()
            assertEquals("file-user", account.account)
            assertEquals("file-password", account.password)
            assertEquals(12, account.intervalSeconds)
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
            assertTrue(AppConfig.load(emptyArray(), file).debug)
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
        withToml(accountsToml("""location = "121.482388,29.885685"""")) { file ->
            val account = AppConfig.load(emptyArray(), file).effectiveAccounts().single()
            assertTrue(account.hasLocation)
            assertEquals("121.482388,29.885685", account.locationText)
            assertEquals(121.482388, account.longitude)
            assertEquals(29.885685, account.latitude)
        }
        withToml(accountsToml("""location = " 120.5 ， 30.5 """")) { file ->
            val account = AppConfig.load(emptyArray(), file).effectiveAccounts().single()
            assertEquals(30.5, account.latitude)
            assertEquals(120.5, account.longitude)
        }
        withToml(accountsToml("location = 30.0")) { file ->
            assertFailsWith<IllegalArgumentException> { AppConfig.load(emptyArray(), file) }
        }
        withToml(accountsToml("""location = "120.0"""")) { file ->
            assertFailsWith<IllegalArgumentException> { AppConfig.load(emptyArray(), file) }
        }
        withToml(accountsToml("""location = "120.0,91.0"""")) { file ->
            assertFailsWith<IllegalArgumentException> { AppConfig.load(emptyArray(), file) }
        }
        withToml(accountsToml("""location = "north,30.0"""")) { file ->
            assertFailsWith<IllegalArgumentException> { AppConfig.load(emptyArray(), file) }
        }

        withToml(accountsToml("""location = "121.0,30.0"""")) { file ->
            val account = AppConfig
                .load(arrayOf("--location", "114.0,22.5"), file)
                .effectiveAccounts()
                .single()
            assertEquals("114.000000,22.500000", account.locationText)
        }
    }

    @Test
    fun resolvesPerCourseLocationByPriority() {
        withToml(
            """
            [[accounts]]
            username = "user"
            password = "pass"
            location = "120.0,30.0"

            [[accounts.locations]]
            course = "算法设计与分析"
            location = "121.0,31.0"

            [[accounts.locations]]
            course_id = "F9CF052C-AD94-11F1-BAE9-A088C2A30E68"
            location = "123.0,33.0"
            """.trimIndent()
        ) { file ->
            val account = AppConfig.load(emptyArray(), file).effectiveAccounts().single()
            assertEquals(2, account.locations.size)

            val byId = course(
                id = "f9cf052c-ad94-11f1-bae9-a088c2a30e68",
                name = "算法设计与分析"
            )
            assertEquals("123.000000,33.000000", account.locationFor(byId)?.text)
            assertEquals(
                "course_id=F9CF052C-AD94-11F1-BAE9-A088C2A30E68",
                account.locationSourceFor(byId)
            )

            val byName = course(id = "OTHER", name = " 算法设计与分析 ")
            assertEquals("121.000000,31.000000", account.locationFor(byName)?.text)
            assertEquals("course=算法设计与分析", account.locationSourceFor(byName))

            val fallback = course(id = "OTHER-3", name = "大学物理")
            assertEquals("120.000000,30.000000", account.locationFor(fallback)?.text)
            assertEquals("账户默认", account.locationSourceFor(fallback))
        }
    }

    @Test
    fun noLocationMeansEmptyCoordinates() {
        val account = AccountOptions(label = "账号1", account = "user", password = "pass")
        assertEquals(null, account.locationFor(course("X", "任意课程")))
        assertEquals(null, account.locationSourceFor(course("X", "任意课程")))
    }

    @Test
    fun rejectsInvalidLocationBlocks() {
        val invalid = listOf(
            """
            [[accounts.locations]]
            course = "A"
            """,
            """
            [[accounts.locations]]
            course = "A"
            course_id = "B"
            location = "120.0,30.0"
            """,
            """
            [[accounts.locations]]
            course = "A"
            location = "120.0,91.0"
            """,
            """
            [[accounts.locations]]
            course = "A"
            location = "north,30.0"
            """,
            """
            [[accounts.locations]]
            course = "A"
            location = "120.0,30.0"
            [[accounts.locations]]
            course = " a "
            location = "121.0,31.0"
            """,
            """
            [[accounts.locations]]
            course = "A"
            location = "120.0,30.0"
            unknown = 1
            """,
            """
            [[accounts.locations]]
            class_name = "A"
            location = "120.0,30.0"
            """,
            """
            [[accounts.locations]]
            course = "A"
            latitude = 30.0
            longitude = 120.0
            """
        )
        invalid.forEach { block ->
            withToml(accountsToml(block)) { file ->
                assertFailsWith<IllegalArgumentException> { AppConfig.load(emptyArray(), file) }
            }
        }
        withToml(accountsToml("latitude = 30.0\nlongitude = 120.0")) { file ->
            val error = assertFailsWith<IllegalArgumentException> {
                AppConfig.load(emptyArray(), file)
            }
            assertTrue(error.message.orEmpty().contains("location"))
        }
    }

    @Test
    fun rejectsRemovedSingleAccountConfigWithMigrationHint() {
        for (content in listOf(
            """
            username = "single"
            password = "pass"
            """,
            """
            location = "120.0,30.0"
            [[accounts]]
            username = "user"
            password = "pass"
            """,
            """
            [[locations]]
            course = "A"
            location = "120.0,30.0"
            """
        )) {
            withToml(content.trimIndent()) { file ->
                val error = assertFailsWith<IllegalArgumentException> {
                    AppConfig.load(emptyArray(), file)
                }
                assertTrue(
                    error.message.orEmpty().contains("accounts"),
                    "message should point at [[accounts]]: ${error.message}"
                )
            }
        }
    }

    @Test
    fun rejectsAccountAndPasswordOnCommandLine() {
        for (option in listOf("--account", "--password")) {
            val error = assertFailsWith<IllegalArgumentException> {
                AppConfig.parse(arrayOf(option, "value"))
            }
            assertTrue(error.message.orEmpty().contains("config.toml"))
        }
    }

    @Test
    fun rejectsShortIntervalAndMissingValues() {
        assertFailsWith<IllegalArgumentException> {
            AppConfig.parse(arrayOf("--interval", "9"))
        }
        assertFailsWith<IllegalArgumentException> {
            AppConfig.parse(arrayOf("--interval"))
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
    }

    @Test
    fun rejectsLocationOverrideWithoutAccounts() {
        val error = assertFailsWith<IllegalArgumentException> {
            AppConfig.parse(arrayOf("--location", "120.0,30.0"))
        }
        assertTrue(error.message.orEmpty().contains("[[accounts]]"))
    }

    private fun accountsToml(extra: String) = """
        [[accounts]]
        username = "user"
        password = "pass"
        ${extra.trim()}
    """.trimIndent()

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
