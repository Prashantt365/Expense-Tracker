package com.peyo.app.sync

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/**
 * Whether a refused refresh means the session is over, rather than that the server is struggling.
 *
 * GoTrue answers a revoked, reused or expired refresh token with a 400 (invalid_grant,
 * refresh_token_not_found and the like), and 401 or 403 mean much the same. Anything else -- a 5xx,
 * a 429, a timeout at a proxy -- says nothing about the token, and signing the user out over it
 * would throw away a perfectly good session because of a bad minute.
 */
internal fun refreshIsFinal(status: Int): Boolean = status == 400 || status == 401 || status == 403

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
     * Swaps a refresh token for a live one. Only some refusals are final -- see
     * [refreshIsFinal] -- and it is the caller that decides what to do with one.
     */
    suspend fun refresh(session: Session): Response<Session> {
        val body = JSONObject().put("refresh_token", session.refreshToken).toString()
        return Supabase.request("POST", "/auth/v1/token?grant_type=refresh_token", body).toSession()
    }

    /**
     * A token good for the next request, refreshing first if the one held has run out -- or
     * regardless, when [force] says the server has just turned the held one down.
     *
     * Null means nobody is signed in, either because nobody was or because the refresh token has
     * been revoked or has aged out. [Response.Offline] and a server that is only struggling (a 5xx,
     * a 429) come back as themselves and leave the session exactly where it was: a flaky
     * connection or a bad minute at Supabase must never sign anybody out, and used to, silently,
     * while Settings went on showing them signed in.
     */
    suspend fun freshToken(force: Boolean = false): Response<String>? {
        val held = stored() ?: return null
        if (!force && held.isFresh()) return Response.Ok(held.accessToken)
        return refreshLock.withLock {
            // Somebody else may have refreshed while this waited, and a refresh token is spent by
            // use, so sending the old one again would at best waste a round trip.
            val current = stored() ?: return@withLock null
            if (current.accessToken != held.accessToken && current.isFresh()) {
                return@withLock Response.Ok(current.accessToken)
            }
            when (val refreshed = refresh(current)) {
                is Response.Ok -> Response.Ok(refreshed.body.accessToken)
                is Response.Offline -> refreshed
                is Response.Rejected ->
                    if (refreshIsFinal(refreshed.status)) {
                        forget()
                        null
                    } else {
                        refreshed
                    }
            }
        }
    }

    suspend fun signOut() {
        stored()?.let { Supabase.request("POST", "/auth/v1/logout", "{}", it.accessToken) }
        forget()
    }

    /**
     * Deletes the account and everything it owns on the backend, then forgets it locally.
     *
     * Calls the delete_own_account() function defined in supabase/schema.sql rather than any
     * Auth endpoint: GoTrue only exposes user deletion through its admin API, gated behind the
     * service_role key, and that key must never ship inside an app. The function runs as its own
     * owner and only ever touches auth.uid() -- the caller's own row -- so a signed-in user's own
     * token is enough to invoke it, and deleting that row cascades through every table that
     * references it.
     *
     * Local data is untouched either way: the account only ever backed it up, and losing the
     * connection partway through this leaves that backup exactly as current as it already was.
     */
    suspend fun deleteAccount(): Response<Unit> {
        // Only a session that is really gone is worth asking the user to sign in again for. Being
        // offline, or the server having a bad minute, is said as what it is.
        val token = when (val fresh = freshToken()) {
            null -> return Response.Rejected(401, "Sign in again, then try deleting your account.")
            is Response.Offline -> return fresh
            is Response.Rejected -> return fresh
            is Response.Ok -> fresh.body
        }
        var response = deleteOwnAccount(token)
        // The stored expiry is only as good as the device clock, so a token it called live can
        // still be turned down. One forced refresh and one retry is the recovery for that.
        if (response is Response.Rejected && response.status == 401) {
            val retried = freshToken(force = true)
            if (retried is Response.Ok) response = deleteOwnAccount(retried.body)
        }
        // The account is gone on the server the moment that call succeeds, so the local session is
        // forgotten regardless of what happens next -- there is nothing left for it to refer to.
        if (response is Response.Ok) forget()
        return response.map {}
    }

    private suspend fun deleteOwnAccount(token: String) = Supabase.request(
        method = "POST",
        path = "/rest/v1/rpc/delete_own_account",
        body = "{}",
        accessToken = token
    )

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
        /**
         * Shared by every Account, since several are made (one per screen and one for syncing)
         * and they all read and write the same stored session.
         */
        val refreshLock = Mutex()

        const val KEY_USER_ID = "userId"
        const val KEY_EMAIL = "email"
        const val KEY_ACCESS = "accessToken"
        const val KEY_REFRESH = "refreshToken"
        const val KEY_EXPIRES_AT = "expiresAt"
    }
}
