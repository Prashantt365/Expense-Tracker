package com.peyo.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The addresses below are the shapes GoTrue redirects to after a link in one of its emails is
 * followed: the session in the fragment on success, the refusal in the fragment or the query.
 */
class AuthLinkTest {

    @Test fun `reads the session a reset link carries`() {
        val link = authLinkFrom(
            "peyo://auth#access_token=eyJ.a.b&expires_at=1757803600&expires_in=3600" +
                "&refresh_token=kq3n2lkjr2&token_type=bearer&type=recovery"
        )
        assertEquals(AuthLink.Tokens("recovery", "eyJ.a.b", "kq3n2lkjr2", 3600), link)
    }

    @Test fun `reads a confirmed signup the same way`() {
        val link = authLinkFrom("peyo://auth/#access_token=a&refresh_token=b&type=signup") as AuthLink.Tokens
        assertEquals("signup", link.type)
    }

    @Test fun `an expired link says so in words`() {
        val link = authLinkFrom(
            "peyo://auth#error=access_denied&error_code=otp_expired" +
                "&error_description=Email+link+is+invalid+or+has+expired"
        )
        assertTrue(link is AuthLink.Failed)
        assertTrue((link as AuthLink.Failed).message.contains("expired"))
    }

    @Test fun `a refusal in the query is read too`() {
        val link = authLinkFrom("peyo://auth?error=server_error&error_description=Something+broke")
        assertEquals(AuthLink.Failed("Something broke"), link)
    }

    @Test fun `a link without both tokens is not a sign-in`() {
        assertTrue(authLinkFrom("peyo://auth#access_token=a&type=recovery") is AuthLink.Failed)
    }

    @Test fun `other addresses are left alone`() {
        assertNull(authLinkFrom("content://com.android.providers.downloads/document/42"))
        assertNull(authLinkFrom("peyo://authority#access_token=a&refresh_token=b"))
        assertNull(authLinkFrom("https://example.com/auth#access_token=a&refresh_token=b"))
    }

    @Test fun `the redirect is encoded for a query parameter`() {
        assertEquals("peyo%3A%2F%2Fauth", AUTH_REDIRECT_PARAM)
    }
}
