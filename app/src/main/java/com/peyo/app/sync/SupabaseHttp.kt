package com.peyo.app.sync

import com.peyo.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * The whole Supabase client, which is small enough not to want a library.
 *
 * Only two surfaces are used: GoTrue for signing in and PostgREST for the rows. Both are plain
 * JSON over HTTP, so HttpURLConnection and org.json -- already in the platform -- do the job
 * without adding Ktor and kotlinx-serialization to an APK that has no other use for them.
 */

/** What a call came back with, so a caller can tell "no network" from "the server said no". */
sealed interface Response<out T> {
    data class Ok<T>(val body: T) : Response<T>

    /** The server answered and refused. [message] is fit to show a user. */
    data class Rejected(val status: Int, val message: String) : Response<Nothing>

    /** Nothing was reached. Worth retrying later, and never worth an error message. */
    data class Offline(val cause: String) : Response<Nothing>
}

inline fun <T, R> Response<T>.map(transform: (T) -> R): Response<R> = when (this) {
    is Response.Ok -> Response.Ok(transform(body))
    is Response.Rejected -> this
    is Response.Offline -> this
}

object Supabase {

    val url: String get() = BuildConfig.SUPABASE_URL.trimEnd('/')
    val anonKey: String get() = BuildConfig.SUPABASE_ANON_KEY

    /** False on a build whose local.properties carried no project, which stays wholly offline. */
    val isConfigured: Boolean get() = url.isNotBlank() && anonKey.isNotBlank()

    /**
     * One request. [accessToken] is a signed-in user's token; without it the call is made as the
     * anonymous role, which row level security allows almost nothing, so only sign-in uses that.
     *
     * Timeouts are short deliberately: every caller has already written to the local database and
     * is only pushing a copy, so waiting is worse than trying again later.
     */
    suspend fun request(
        method: String,
        path: String,
        body: String? = null,
        accessToken: String? = null,
        headers: Map<String, String> = emptyMap()
    ): Response<String> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext Response.Offline("No Supabase project is configured")
        var connection: HttpURLConnection? = null
        try {
            connection = (URL("$url$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                doInput = true
                setRequestProperty("apikey", anonKey)
                setRequestProperty("Authorization", "Bearer ${accessToken ?: anonKey}")
                setRequestProperty("Accept", "application/json")
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            val status = connection.responseCode
            // An error response still has a body, and it is the only place the reason is written.
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status in 200..299) Response.Ok(text) else Response.Rejected(status, reasonIn(text, status))
        } catch (e: IOException) {
            // Thrown for a flaky connection as readily as for no connection at all, and neither is
            // the user's problem to read about.
            Response.Offline(e.message ?: "Could not reach the server")
        } finally {
            connection?.disconnect()
        }
    }

    private const val CONNECT_TIMEOUT_MILLIS = 10_000
    private const val READ_TIMEOUT_MILLIS = 20_000
}

/**
 * Supabase reports a failure under a different key depending on which service answered -- GoTrue
 * uses "msg", PostgREST uses "message", the OAuth endpoints use "error_description" -- so each is
 * tried in turn before falling back to something that at least names the status.
 */
internal fun reasonIn(body: String, status: Int): String {
    val json = body.asJsonObject() ?: return "The server refused the request ($status)"
    listOf("error_description", "msg", "message", "error", "hint").forEach { key ->
        if (!json.isNull(key)) {
            json.optString(key).takeIf { it.isNotBlank() }?.let { return it }
        }
    }
    return "The server refused the request ($status)"
}

fun String.asJsonObject(): JSONObject? = runCatching { JSONObject(this) }.getOrNull()
fun String.asJsonArray(): JSONArray? = runCatching { JSONArray(this) }.getOrNull()

/** org.json returns the string "null" for a JSON null, which is never what a caller wants. */
fun JSONObject.stringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else optLong(key)

fun <T> JSONArray.map(transform: (JSONObject) -> T): List<T> =
    (0 until length()).mapNotNull { index -> optJSONObject(index)?.let(transform) }
