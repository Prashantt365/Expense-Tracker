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
        // Given at signup and kept by GoTrue in the user's metadata, so it arrives with every
        // sign-in and refresh, on any phone.
        name = user.optJSONObject("user_metadata")?.stringOrNull("full_name")?.trim().orEmpty(),
        // Kept in the same metadata, already in E.164, for the same reason.
        phone = user.optJSONObject("user_metadata")?.stringOrNull("phone_number")?.trim().orEmpty(),
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
    /** What the user asked to be called. Blank for accounts made before names were asked for. */
    val name: String = "",
    /** The account's mobile number in E.164. Blank for accounts made before numbers were asked for. */
    val phone: String = "",
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
            name = prefs.getString(KEY_NAME, "").orEmpty(),
            phone = prefs.getString(KEY_PHONE, "").orEmpty(),
            accessToken = access,
            refreshToken = refresh,
            expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0)
        )
    }

    fun remember(session: Session) {
        prefs.edit()
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_EMAIL, session.email)
            .putString(KEY_NAME, session.name)
            .putString(KEY_PHONE, session.phone)
            .putString(KEY_ACCESS, session.accessToken)
            .putString(KEY_REFRESH, session.refreshToken)
            .putLong(KEY_EXPIRES_AT, session.expiresAt)
            .apply()
    }

    fun forget() = prefs.edit().clear().apply()

    /**
     * [phone] must already be in E.164 (see [com.peyo.app.PhoneNumbers]); it is what links this
     * account to the expenses other people record against that number.
     */
    suspend fun signUp(name: String, phone: String, email: String, password: String): SignUpResult {
        when (val taken = isPhoneTaken(phone)) {
            is Response.Ok -> if (taken.body) return SignUpResult.Failed(PHONE_TAKEN)
            is Response.Offline -> return SignUpResult.Failed("No connection. Try again when you are online.")
            // A project without the sharing schema yet: the signup itself still decides.
            is Response.Rejected -> Unit
        }
        val response = Supabase.request(
            method = "POST",
            // So the confirmation link opens the app, not the project's Site URL.
            path = "/auth/v1/signup?redirect_to=$AUTH_REDIRECT_PARAM",
            body = JSONObject(credentials(email, password))
                .put("data", JSONObject().put("full_name", name.trim()).put("phone_number", phone))
                .toString()
        )
        return when (response) {
            is Response.Offline -> SignUpResult.Failed("No connection. Try again when you are online.")
            // The database refusing the new user is, in practice, the number having been taken
            // between the check above and the signup -- GoTrue reports it only as a database error.
            is Response.Rejected -> SignUpResult.Failed(
                if (response.message.contains("Database error", ignoreCase = true)) PHONE_TAKEN
                else response.message
            )
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
     * Emails a password reset: a link that opens the app at [AUTH_REDIRECT], and -- once the
     * project's "Reset Password" template carries `{{ .Token }}` -- a code to type in instead, for
     * an email read on a different device.
     *
     * GoTrue answers the same whether or not the address has an account, so this cannot be used
     * to find out who has one, and the caller cannot either.
     */
    suspend fun sendPasswordReset(email: String): Response<Unit> = Supabase.request(
        method = "POST",
        path = "/auth/v1/recover?redirect_to=$AUTH_REDIRECT_PARAM",
        body = JSONObject().put("email", email.trim()).toString()
    ).map {}

    /**
     * Trades the code from a reset email for a session. The session is handed back rather than
     * remembered: it only becomes the phone's account once a new password is set with it.
     */
    suspend fun verifyResetCode(email: String, code: String): Response<Session> = Supabase.request(
        method = "POST",
        path = "/auth/v1/verify",
        body = JSONObject()
            .put("type", "recovery")
            .put("email", email.trim())
            .put("token", code.trim())
            .toString()
    ).toSession(keep = false)

    /**
     * The session a link from a Supabase email carries. The link holds only the tokens, so the
     * user they belong to is asked for. Not remembered, for the same reason as [verifyResetCode].
     */
    suspend fun sessionFromLink(link: AuthLink.Tokens): Response<Session> {
        val response = Supabase.request("GET", "/auth/v1/user", accessToken = link.accessToken)
        return when (response) {
            is Response.Offline -> response
            is Response.Rejected -> response
            is Response.Ok -> {
                val json = JSONObject()
                    .put("access_token", link.accessToken)
                    .put("refresh_token", link.refreshToken)
                    .put("expires_in", link.expiresIn)
                    .put("user", response.body.asJsonObject())
                sessionFrom(json)?.let { Response.Ok(it) }
                    ?: Response.Rejected(200, "The server sent something unreadable.")
            }
        }
    }

    /** Sets a new password with a session from a reset, then signs the phone in with it. */
    suspend fun setPassword(session: Session, password: String): Response<Session> {
        val response = Supabase.request(
            method = "PUT",
            path = "/auth/v1/user",
            body = JSONObject().put("password", password).toString(),
            accessToken = session.accessToken
        )
        if (response is Response.Ok) remember(session)
        return response.map { session }
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

    /**
     * Changes the name the account goes by. It lives in the user's GoTrue metadata, where signup
     * put it, so the next sign-in on any phone brings back the new one; the profiles trigger
     * mirrors it into public.profiles. Only the stored session is updated here -- the rest of the
     * session is the server's to change.
     */
    suspend fun updateName(name: String): Response<Unit> {
        val token = when (val fresh = freshToken()) {
            null -> return Response.Rejected(401, "Sign in again, then change your name.")
            is Response.Offline -> return fresh
            is Response.Rejected -> return fresh
            is Response.Ok -> fresh.body
        }
        val trimmed = name.trim()
        var response = putName(token, trimmed)
        // Same recovery as deleting: a token the device clock called live can still be refused.
        if (response is Response.Rejected && response.status == 401) {
            val retried = freshToken(force = true)
            if (retried is Response.Ok) response = putName(retried.body, trimmed)
        }
        if (response is Response.Ok) stored()?.let { remember(it.copy(name = trimmed)) }
        return response.map {}
    }

    private suspend fun putName(token: String, name: String) = Supabase.request(
        method = "PUT",
        path = "/auth/v1/user",
        body = JSONObject().put("data", JSONObject().put("full_name", name)).toString(),
        accessToken = token
    )

    /** Whether another account already holds [phone], an E.164 number. */
    suspend fun isPhoneTaken(phone: String): Response<Boolean> {
        val token = (freshToken() as? Response.Ok)?.body
        return Supabase.request(
            method = "POST",
            path = "/rest/v1/rpc/phone_is_taken",
            body = JSONObject().put("p_phone", phone).toString(),
            accessToken = token
        ).map { it.trim() == "true" }
    }

    /**
     * Sets or changes the account's mobile number. Checked against other accounts first, since
     * the server keeps the old number, rather than failing, when the new one turns out to be taken.
     */
    suspend fun updatePhone(phone: String): Response<Unit> {
        when (val taken = isPhoneTaken(phone)) {
            is Response.Ok -> if (taken.body) return Response.Rejected(409, PHONE_TAKEN)
            is Response.Offline -> return taken
            is Response.Rejected -> return taken
        }
        val token = when (val fresh = freshToken()) {
            null -> return Response.Rejected(401, "Sign in again, then change your number.")
            is Response.Offline -> return fresh
            is Response.Rejected -> return fresh
            is Response.Ok -> fresh.body
        }
        val body = JSONObject().put("data", JSONObject().put("phone_number", phone)).toString()
        var response = Supabase.request("PUT", "/auth/v1/user", body, token)
        if (response is Response.Rejected && response.status == 401) {
            val retried = freshToken(force = true)
            if (retried is Response.Ok) response = Supabase.request("PUT", "/auth/v1/user", body, retried.body)
        }
        if (response is Response.Ok) stored()?.let { remember(it.copy(phone = phone)) }
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

    private fun Response<String>.toSession(keep: Boolean = true): Response<Session> = when (this) {
        is Response.Offline -> this
        is Response.Rejected -> this
        is Response.Ok -> {
            val session = body.asJsonObject()?.let(::sessionFrom)
            if (session == null) Response.Rejected(200, "The server sent something unreadable.")
            else {
                if (keep) remember(session)
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
        const val KEY_NAME = "name"
        const val KEY_PHONE = "phone"

        const val PHONE_TAKEN = "That mobile number already has a Peyo account. Sign in to it instead."
        const val KEY_ACCESS = "accessToken"
        const val KEY_REFRESH = "refreshToken"
        const val KEY_EXPIRES_AT = "expiresAt"
    }
}
