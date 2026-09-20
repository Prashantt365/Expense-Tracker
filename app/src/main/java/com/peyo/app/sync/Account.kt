package com.peyo.app.sync

import android.content.Context
import org.json.JSONObject

/**
 * Reads a session out of whatever GoTrue answered a sign-in, sign-up or refresh with.
 *
 * Null is not an error: a project that confirms signups by email answers a signup with a user and
 * no tokens, and the caller has to tell that apart from a failure.
 *
 * The life of a token arrives as a duration rather than an instant, so it is anchored to the
 * device clock here. That makes the expiry only as good as the clock, which is why a token that
 * turns out to be dead is also recovered from reactively rather than trusted to be caught early.
 */
internal fun sessionFrom(json: JSONObject, now: Long = System.currentTimeMillis()): Session? {
    val access = json.stringOrNull("access_token") ?: return null
    val refresh = json.stringOrNull("refresh_token") ?: return null
    val user = json.optJSONObject("user")
    val userId = user?.stringOrNull("id") ?: return null
    val expiresIn = json.optLong("expires_in", DEFAULT_TOKEN_LIFE_SECONDS)
    return Session(
        userId = userId,
        email = user.stringOrNull("email").orEmpty(),
        accessToken = access,
        refreshToken = refresh,
        expiresAt = now + expiresIn * 1000
    )
}

private const val DEFAULT_TOKEN_LIFE_SECONDS = 3600L

/** A signed-in user, as much of one as the app needs to know about. */
data class Session(
    val userId: String,
    val email: String,
    val accessToken: String,
    val refreshToken: String,
    /** Device-clock millis at which [accessToken] stops being accepted. */
    val expiresAt: Long
) {
    /**
     * Treated as expired a minute early, so a token cannot lapse between the check and the request
     * it was checked for.
     */
    fun isFresh(now: Long = System.currentTimeMillis()): Boolean = now < expiresAt - LEEWAY_MILLIS

    private companion object {
        const val LEEWAY_MILLIS = 60_000L
    }
}

/** What signing up can come back as, since the answer is not simply yes or no. */
sealed interface SignUpResult {
    data class SignedIn(val session: Session) : SignUpResult

    /**
     * The project requires a confirmation email, so the account exists but cannot be used until
     * the link is followed. Supabase answers a signup with no session rather than an error, and
     * mistaking that for success would leave the user staring at a screen that never signs in.
     */
    data class NeedsEmailConfirmation(val email: String) : SignUpResult

    data class Failed(val message: String) : SignUpResult
}

/**
 * Signing in, and remembering that it happened.
 *
 * The session lives in app-private SharedPreferences. That is the Android sandbox and nothing
 * else: a rooted phone can read it. A refresh token is worth exactly one account's own expense
 * history, which is already on the same device in the same sandbox, so encrypting it would guard
 * the copy while leaving the original in the open.
 */
class Account(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("peyo.account", Context.MODE_PRIVATE)

    fun stored(): Session? {
        val userId = prefs.getString(KEY_USER_ID, null) ?: return null
        val access = prefs.getString(KEY_ACCESS, null) ?: return null
        val refresh = prefs.getString(KEY_REFRESH, null) ?: return null
        return Session(
            userId = userId,
            email = prefs.getString(KEY_EMAIL, "").orEmpty(),
            accessToken = access,
            refreshToken = refresh,
            expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0)
        )
    }

    fun remember(session: Session) {
        prefs.edit()
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_EMAIL, session.email)
            .putString(KEY_ACCESS, session.accessToken)
            .putString(KEY_REFRESH, session.refreshToken)
            .putLong(KEY_EXPIRES_AT, session.expiresAt)
            .apply()
    }

    fun forget() = prefs.edit().clear().apply()

    suspend fun signUp(email: String, password: String): SignUpResult {
        val response = Supabase.request(
            method = "POST",
            path = "/auth/v1/signup",
            body = credentials(email, password)
        )
        return when (response) {
            is Response.Offline -> SignUpResult.Failed("No connection. Try again when you are online.")
            is Response.Rejected -> SignUpResult.Failed(response.message)
            is Response.Ok -> {
                val json = response.body.asJsonObject()
                    ?: return SignUpResult.Failed("The server sent something unreadable.")
                // A session comes back only when the project confirms signups automatically.
                val session = sessionFrom(json)
                if (session != null) {
                    remember(session)
                    SignUpResult.SignedIn(session)
                } else {
                    SignUpResult.NeedsEmailConfirmation(email.trim())
                }
            }
        }
    }

    suspend fun signIn(email: String, password: String): Response<Session> {
        val response = Supabase.request(
            method = "POST",
            path = "/auth/v1/token?grant_type=password",
            body = credentials(email, password)
        )
        return response.toSession()
    }

    /**
     * Swaps a refresh token for a live one. A refusal here is final -- the token has been revoked
     * or has aged out -- so the caller signs the user out rather than retrying.
     */
    suspend fun refresh(session: Session): Response<Session> {
        val body = JSONObject().put("refresh_token", session.refreshToken).toString()
        return Supabase.request("POST", "/auth/v1/token?grant_type=refresh_token", body).toSession()
    }

    /**
     * A token good for the next request, refreshing first if the one held has run out. Null means
     * the user has to sign in again; [Response.Offline] deliberately does not produce one, so a
     * lost connection never signs anybody out.
     */
    suspend fun freshToken(): String? {
        val held = stored() ?: return null
        if (held.isFresh()) return held.accessToken
        return when (val refreshed = refresh(held)) {
            is Response.Ok -> {
                remember(refreshed.body)
                refreshed.body.accessToken
            }
            is Response.Rejected -> {
                forget()
                null
            }
            is Response.Offline -> null
        }
    }

    suspend fun signOut() {
        stored()?.let { Supabase.request("POST", "/auth/v1/logout", "{}", it.accessToken) }
        forget()
    }

    private fun credentials(email: String, password: String) = JSONObject()
        .put("email", email.trim())
        .put("password", password)
        .toString()

    private fun Response<String>.toSession(): Response<Session> = when (this) {
        is Response.Offline -> this
        is Response.Rejected -> this
        is Response.Ok -> {
            val session = body.asJsonObject()?.let(::sessionFrom)
            if (session == null) Response.Rejected(200, "The server sent something unreadable.")
            else {
                remember(session)
                Response.Ok(session)
            }
        }
    }

    private companion object {
        const val KEY_USER_ID = "userId"
        const val KEY_EMAIL = "email"
        const val KEY_ACCESS = "accessToken"
        const val KEY_REFRESH = "refreshToken"
        const val KEY_EXPIRES_AT = "expiresAt"
    }
}
