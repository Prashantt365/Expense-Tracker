package com.example.expensetracker.sync

import org.json.JSONObject

/**
 * The account's own row in public.profiles.
 *
 * The email and the auth_completed flag are written by a trigger on auth.users, not by the app --
 * an address the client could set is an address the client could set to somebody else's, and a
 * "this user authenticated" flag a client could raise would mean nothing. What the app owns is
 * [currency], which is a preference, and the fact that it was here, which is [Profiles.touch].
 */
data class Profile(
    val id: String,
    val email: String,
    val authCompleted: Boolean,
    val currency: String
)

object Profiles {

    /**
     * The profile of whoever [accessToken] belongs to. Row level security is what scopes this:
     * the filter below is a courtesy, the policy is the guarantee.
     */
    suspend fun mine(accessToken: String, userId: String): Response<Profile?> {
        val path = "/rest/v1/profiles?id=eq.$userId&select=id,email,auth_completed,currency&limit=1"
        return Supabase.request("GET", path, accessToken = accessToken).map { body ->
            val row = body.asJsonArray()?.optJSONObject(0) ?: return@map null
            Profile(
                id = row.stringOrNull("id").orEmpty(),
                email = row.stringOrNull("email").orEmpty(),
                authCompleted = row.optBoolean("auth_completed"),
                currency = row.stringOrNull("currency") ?: "INR"
            )
        }
    }

    /**
     * Records that this account is in use, and which currency it is using.
     *
     * A PATCH rather than an upsert: the row is created by the signup trigger, and inserting one
     * here would only paper over a project whose trigger was never installed while writing an
     * email the client is not entitled to decide.
     */
    suspend fun touch(accessToken: String, userId: String, currency: String): Response<Unit> {
        val body = JSONObject()
            .put("currency", currency)
            .put("last_seen_at", Rows.toIso(System.currentTimeMillis()))
            .toString()
        return Supabase.request(
            method = "PATCH",
            path = "/rest/v1/profiles?id=eq.$userId",
            body = body,
            accessToken = accessToken,
            headers = mapOf("Prefer" to "return=minimal")
        ).map { }
    }
}
