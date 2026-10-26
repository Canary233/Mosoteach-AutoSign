package `fun`.eternalblue.data

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MultiAccountConfigTest {
    @Test
    fun singleAccountConfigStaysCompatible() {
        withToml(
            """
            username = "single"
            password = "single-pass"
            location = "120.0,30.0"
            """.trimIndent()
        ) { file ->
            val config = AppConfig.load(emptyArray(), file)
            assertFalse(config.isMultiAccount)
            val accounts = config.effectiveAccounts()
            assertEquals(1, accounts.size)
            assertEquals("single", accounts.single().account)
            assertEquals("120.000000,30.000000", accounts.single().locationText)
        }
    }

    @Test
    fun parsesMultipleAccountsWithIndependentLocations() {
        withToml(
            """
            check_interval = 15
            location = "120.0,30.0"

            [[locations]]
            course = "共享课程"
            location = "119.0,29.0"

            [[accounts]]
            name = "主账号"
            username = "user-a"
            password = "pass-a"

            [[accounts]]
            username = "user-b"
            password = "pass-b"
            location = "121.0,31.0"

            [[accounts.locations]]
            course = "算法设计与分析"
            location = "122.0,32.0"

            [[accounts]]
            username = "user-c"
            password = "pass-c"
            """.trimIndent()
        ) { file ->
            val config = AppConfig.load(emptyArray(), file)
            assertTrue(config.isMultiAccount)
            assertEquals(15, config.intervalSeconds)

            val accounts = config.effectiveAccounts()
            assertEquals(3, accounts.size)

            val first = accounts[0]
            assertEquals("主账号", first.label)
            assertEquals("user-a", first.account)
            assertEquals("pass-a", first.password)
            assertEquals("120.000000,30.000000", first.locationText)
            assertEquals(1, first.locations.size)

            val second = accounts[1]
            assertEquals("账号2", second.label)
            assertEquals("121.000000,31.000000", second.locationText)
            assertEquals(2, second.locations.size)
            val course = ClazzCourse(id = "c1", course = Course(name = "算法设计与分析"))
            assertEquals("122.000000,32.000000", second.locationFor(course)?.text)

            val third = accounts[2]
            assertEquals("120.000000,30.000000", third.locationText)
            assertFalse(third.isMultiAccount)
        }
    }

    @Test
    fun accountLocationOverridesGlobalForMatchingCourse() {
        withToml(
            """
            location = "120.0,30.0"
            [[accounts]]
            username = "user-a"
            password = "pass-a"
            [[accounts.locations]]
            course = "算法设计与分析"
            location = "123.0,33.0"
            """.trimIndent()
        ) { file ->
            val account = AppConfig.load(emptyArray(), file).effectiveAccounts().single()
            val match = ClazzCourse(id = "c1", course = Course(name = "算法设计与分析"))
            val other = ClazzCourse(id = "c2", course = Course(name = "数据结构"))
            assertEquals("123.000000,33.000000", account.locationFor(match)?.text)
            assertEquals("120.000000,30.000000", account.locationFor(other)?.text)
        }
    }

    @Test
    fun rejectsInvalidAccountBlocks() {
        val invalid = listOf(
            // missing username
            """
            [[accounts]]
            password = "pass"
            """,
            // missing password
            """
            [[accounts]]
            username = "user"
            """,
            // duplicate account
            """
            [[accounts]]
            username = "user"
            password = "p1"
            [[accounts]]
            username = " USER "
            password = "p2"
            """,
            // mixed single and multi
            """
            username = "single"
            password = "p"
            [[accounts]]
            username = "user"
            password = "p"
            """,
            // legacy split coordinates
            """
            [[accounts]]
            username = "user"
            password = "p"
            latitude = 30.0
            """,
            // malformed value
            """
            [[accounts]]
            username = "user"
            password = "p"
            location = "120.0"
            """,
            // out of range
            """
            [[accounts]]
            username = "user"
            password = "p"
            location = "181.0,30.0"
            """,
            // unknown key
            """
            [[accounts]]
            username = "user"
            password = "p"
            token = "x"
            """
        )
        invalid.forEach { content ->
            withToml(content) { file ->
                assertFailsWith<IllegalArgumentException> { AppConfig.load(emptyArray(), file) }
            }
        }
    }

    @Test
    fun commandLineIntervalAppliesToAllAccounts() {
        withToml(
            """
            [[accounts]]
            username = "a"
            password = "pa"
            [[accounts]]
            username = "b"
            password = "pb"
            """.trimIndent()
        ) { file ->
            val accounts = AppConfig.load(arrayOf("--interval", "12"), file).effectiveAccounts()
            assertEquals(listOf(12L, 12L), accounts.map { it.intervalSeconds })
        }
    }

    @Test
    fun rejectsAccountOptionWhenAccountsAreConfigured() {
        withToml(
            """
            [[accounts]]
            username = "a"
            password = "pa"
            """.trimIndent()
        ) { file ->
            assertFailsWith<IllegalArgumentException> {
                AppConfig.load(arrayOf("--account", "other"), file)
            }
        }
    }

    @Test
    fun commandLineCoordinatesOverrideAccountCoordinates() {
        withToml(
            """
            [[accounts]]
            username = "a"
            password = "pa"
            location = "121.0,31.0"
            """.trimIndent()
        ) { file ->
            val account = AppConfig
                .load(arrayOf("--location", "120.5,30.5"), file)
                .effectiveAccounts()
                .single()
            assertEquals("120.500000,30.500000", account.locationText)
        }
    }

    private fun withToml(content: String, block: (java.io.File) -> Unit) {
        val file = Files.createTempFile("autosign-multi", ".toml").toFile()
        try {
            file.writeText(content)
            block(file)
        } finally {
            file.delete()
        }
    }
}
