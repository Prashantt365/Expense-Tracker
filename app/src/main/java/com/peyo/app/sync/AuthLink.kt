package com.peyo.app.sync

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Where Supabase sends the browser after somebody follows a link in one of its emails. The
 * manifest routes it to the app, so a reset or confirmation link opens Peyo rather than the
 * project's Site URL -- which, left at its default, is a localhost page that a phone cannot reach.
 *
 * GoTrue only honours it once it is in the project's Redirect URLs allowlist; until then it falls
 * back to the Site URL without complaint, so sending it is never what breaks an email.
 */
const val AUTH_REDIRECT = "peyo://auth"

/** [AUTH_REDIRECT] as GoTrue wants it in a `redirect_to` query parameter. */
internal val AUTH_REDIRECT_PARAM: String = URLEncoder.encode(AUTH_REDIRECT, "UTF-8")

/** What a link from a Supabase email brought back with it. */
sealed interface AuthLink {
    /**
     * A live session. [type] says which email it came from: "recovery" for a password reset,
     * "signup" for a confirmed address, and so on.
     */
    data class Tokens(
        val type: String,
        val accessToken: String,
        val refreshToken: String,
        val expiresIn: Long
    ) : AuthLink

    /** The link was refused -- most often because it had expired or already been used. */
    data class Failed(val message: String) : AuthLink
}

/**
 * Reads a link Supabase redirected to [AUTH_REDIRECT], or null for any other address.
 *
 * Supabase writes the session into the fragment rather than the query, so it never reaches a
 * server log on the way, and writes a refusal into either one depending on where it was refused,
 * so both are read.
 */
fun authLinkFrom(uri: String): AuthLink? {
    val address = uri.substringBefore('#').substringBefore('?').trimEnd('/')
    if (!address.equals(AUTH_REDIRECT, ignoreCase = true)) return null
    val params = paramsIn(uri.substringBefore('#').substringAfter('?', "")) +
        paramsIn(uri.substringAfter('#', ""))

    if (params["error"] != null || params["error_code"] != null) {
        return AuthLink.Failed(
            if (params["error_code"] == "otp_expired") EXPIRED
            else params["error_description"] ?: params["error"] ?: EXPIRED
        )
    }
    val access = params["access_token"]
    val refresh = params["refresh_token"]
    if (access.isNullOrBlank() || refresh.isNullOrBlank()) {
        return AuthLink.Failed("That link did not carry a sign-in. Ask for a new one from the sign-in screen.")
    }
    return AuthLink.Tokens(
        type = params["type"].orEmpty(),
        accessToken = access,
        refreshToken = refresh,
        expiresIn = params["expires_in"]?.toLongOrNull() ?: 3600L
    )
}

private const val EXPIRED =
    "That link has expired or has already been used. Ask for a new one from the sign-in screen."

private fun paramsIn(encoded: String): Map<String, String> = encoded.split('&')
    .filter { '=' in it }
    .associate { pair ->
        decode(pair.substringBefore('=')) to decode(pair.substringAfter('='))
    }

private fun decode(part: String): String = URLDecoder.decode(part, StandardCharsets.UTF_8.name())
