package `fun`.eternalblue.data

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MultiAccountConfigTest {
    @Test
    fun singleAccountUsesTheSameAccountsPath() {
        withToml(
            """
            check_interval = 15

            [[accounts]]
            username = "only-user"
            password = "only-pass"
            location = "120.0,30.0"
            """.trimIndent()
        ) { file ->
            val config = AppConfig.load(emptyArray(), file)
            assertEquals(1, config.accounts.size)

            val account = config.effectiveAccounts().single()
            assertEquals("账号1", account.label)
            assertEquals("only-user", account.account)
            assertEquals("only-pass", account.password)
            assertEquals("120.000000,30.000000", account.locationText)
            assertEquals(15, account.intervalSeconds)
        }
    }

    @Test
    fun parsesMultipleAccountsWithIndependentLocations() {
        withToml(
            """
            check_interval = 15

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
            assertEquals(15, config.intervalSeconds)

            val accounts = config.effectiveAccounts()
            assertEquals(3, accounts.size)

            val first = accounts[0]
            assertEquals("主账号", first.label)
            assertEquals("user-a", first.account)
            assertEquals("pass-a", first.password)
            assertEquals("未配置", first.locationText)

            val second = accounts[1]
            assertEquals("账号2", second.label)
            assertEquals("121.000000,31.000000", second.locationText)
            val course = ClazzCourse(id = "c1", course = Course(name = "算法设计与分析"))
            assertEquals("122.000000,32.000000", second.locationFor(course)?.text)

            val third = accounts[2]
            assertEquals("账号3", third.label)
            assertEquals("未配置", third.locationText)
            assertEquals(null, third.locationFor(course))
        }
    }

    @Test
    fun accountLocationsStayScopedToTheirAccount() {
        withToml(
            """
            [[accounts]]
            username = "user-a"
            password = "pass-a"
            location = "120.0,30.0"
            [[accounts.locations]]
            course = "算法设计与分析"
            location = "123.0,33.0"

            [[accounts]]
            username = "user-b"
            password = "pass-b"
            location = "121.0,31.0"
            """.trimIndent()
        ) { file ->
            val accounts = AppConfig.load(emptyArray(), file).effectiveAccounts()
            val match = ClazzCourse(id = "c1", course = Course(name = "算法设计与分析"))

            assertEquals("123.000000,33.000000", accounts[0].locationFor(match)?.text)
            assertEquals("120.000000,30.000000", accounts[0].locationText)
            // The second account must not inherit the first account's course rule.
            assertEquals("121.000000,31.000000", accounts[1].locationFor(match)?.text)
            assertEquals(0, accounts[1].locations.size)
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
            // empty password
            """
            [[accounts]]
            username = "user"
            password = ""
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
    fun emptyAccountsArrayIsRejected() {
        withToml("accounts = []") { file ->
            assertFailsWith<IllegalArgumentException> { AppConfig.load(emptyArray(), file) }
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
    fun commandLineAccountAndPasswordAreRejected() {
        withToml(
            """
            [[accounts]]
            username = "a"
            password = "pa"
            """.trimIndent()
        ) { file ->
            for (option in listOf("--account", "--password")) {
                val error = assertFailsWith<IllegalArgumentException> {
                    AppConfig.load(arrayOf(option, "other"), file)
                }
                assertTrue(error.message.orEmpty().contains("[[accounts]]"))
            }
        }
    }

    @Test
    fun commandLineLocationOverridesEveryAccount() {
        withToml(
            """
            [[accounts]]
            username = "a"
            password = "pa"
            location = "121.0,31.0"
            [[accounts]]
            username = "b"
            password = "pb"
            location = "122.0,32.0"
            """.trimIndent()
        ) { file ->
            val accounts = AppConfig
                .load(arrayOf("--location", "120.5,30.5"), file)
                .effectiveAccounts()
            assertEquals(
                listOf("120.500000,30.500000", "120.500000,30.500000"),
                accounts.map { it.locationText }
            )
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
