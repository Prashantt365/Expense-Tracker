package com.peyo.app.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The payloads below are the shapes Supabase actually returns, the error ones copied from live
 * responses from the configured project. Parsing them is where a sign-in quietly goes wrong: a
 * session read as null reads to the user as "it did nothing", and a refusal whose reason is
 * looked for under the wrong key reads as a blank error.
 */
class AccountParsingTest {

    private val now = 1_757_800_000_000L

    private fun tokenResponse(expiresIn: Long = 3600) = JSONObject(
        """
        {
          "access_token": "eyJhbGciOiJIUzI1NiJ9.header.signature",
          "token_type": "bearer",
          "expires_in": $expiresIn,
          "refresh_token": "kq3n2lkjr2",
          "user": {
            "id": "8f14e45f-ceea-467a-9f0b-4c2d6a1f9b77",
            "email": "someone@example.com",
            "role": "authenticated"
          }
        }
        """.trimIndent()
    )

    @Test fun `reads a session out of a sign-in`() {
        val session = sessionFrom(tokenResponse(), now)!!
        assertEquals("8f14e45f-ceea-467a-9f0b-4c2d6a1f9b77", session.userId)
        assertEquals("someone@example.com", session.email)
        assertEquals("kq3n2lkjr2", session.refreshToken)
        assertEquals(now + 3_600_000, session.expiresAt)
    }

    /**
     * A project that confirms signups by email answers with a user and no tokens. Reading that as
     * a failure would show an error for something that worked; reading it as success would leave
     * the user on a screen that never signs in.
     */
    @Test fun `a signup awaiting confirmation carries no session`() {
        val awaiting = JSONObject(
            """
            {
              "id": "8f14e45f-ceea-467a-9f0b-4c2d6a1f9b77",
              "email": "someone@example.com",
              "confirmation_sent_at": "2026-09-14T10:00:00Z",
              "user": { "id": "8f14e45f-ceea-467a-9f0b-4c2d6a1f9b77", "email": "someone@example.com" }
            }
            """.trimIndent()
        )
        assertNull(sessionFrom(awaiting, now))
    }

    @Test fun `a response missing the user is not a session`() {
        val headless = JSONObject("""{"access_token":"a","refresh_token":"b","expires_in":3600}""")
        assertNull(sessionFrom(headless, now))
    }

    @Test fun `a token with no stated life still gets a sane expiry`() {
        val json = JSONObject(
            """
            {"access_token":"a","refresh_token":"b",
             "user":{"id":"u","email":"e@x.com"}}
            """.trimIndent()
        )
        assertEquals(now + 3_600_000, sessionFrom(json, now)!!.expiresAt)
    }

    @Test fun `a token is treated as spent before it actually lapses`() {
        val session = sessionFrom(tokenResponse(expiresIn = 30), now)!!
        // Thirty seconds left is inside the leeway, so it must not be handed to a request that
        // could easily outlive it.
        assertFalse(session.isFresh(now))
        assertTrue(sessionFrom(tokenResponse(expiresIn = 3600), now)!!.isFresh(now))
    }

    @Test fun `reads the reason GoTrue refused a sign-in`() {
        val body = """{"code":400,"error_code":"invalid_credentials","msg":"Invalid login credentials"}"""
        assertEquals("Invalid login credentials", reasonIn(body, 400))
    }

    @Test fun `reads the reason a refresh token was rejected`() {
        val body = """{"code":400,"error_code":"validation_failed","msg":"Refresh token is not valid"}"""
        assertEquals("Refresh token is not valid", reasonIn(body, 400))
    }

    @Test fun `reads the reason PostgREST refused a write`() {
        val body = """
            {"code":"PGRST205","details":null,"hint":null,
             "message":"Could not find the table 'public.expenses' in the schema cache"}
        """.trimIndent()
        assertEquals(
            "Could not find the table 'public.expenses' in the schema cache",
            reasonIn(body, 404)
        )
    }

    /** details and hint arrive as JSON nulls, which org.json hands back as the string "null". */
    @Test fun `a null field is not mistaken for a reason`() {
        val body = """{"message":"","hint":null,"details":null}"""
        assertEquals("The server refused the request (409)", reasonIn(body, 409))
    }

    /**
     * Only a refresh token that is really dead may sign the user out. A struggling server used to
     * do it too, silently, leaving Settings showing an account that could no longer sync.
     */
    @Test fun `only a dead refresh token ends the session`() {
        listOf(400, 401, 403).forEach { assertTrue("$it should sign out", refreshIsFinal(it)) }
        listOf(408, 429, 500, 502, 503, 504, 200).forEach {
            assertFalse("$it should not sign out", refreshIsFinal(it))
        }
    }

    @Test fun `an unreadable body still says something`() {
        assertEquals("The server refused the request (502)", reasonIn("<html>bad gateway</html>", 502))
        assertEquals("The server refused the request (500)", reasonIn("", 500))
    }
}
