package `fun`.eternalblue.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DebugRedactionTest {
    @Test
    fun removesSecretsFromJsonAndFormBodies() {
        val raw = """
            {"account":"18700000000","password":"PrivatePass1!","token":"tok-123",
             "accessSecret":"secret-123","lastSecUpdateTsS":"1700000000",
             "X-signature":"sig-abc","nested":{"access_secret":"s2"}}
            password=FormPass2!&access_secret=secret-456&cc_id=course-1
            X-mssvc-signature: sig-def
            X-mssvc-sec-ts: 1700000001
        """.trimIndent()

        val redacted = Debug.redact(raw)

        for (secret in listOf(
            "PrivatePass1!", "tok-123", "secret-123", "sig-abc",
            "FormPass2!", "secret-456", "sig-def"
        )) {
            assertFalse(redacted.contains(secret), "leaked: $secret")
        }
        assertTrue(redacted.contains("18700000000"))
        assertTrue(redacted.contains("course-1"))
        assertTrue(redacted.contains("1700000001"))
    }
}
