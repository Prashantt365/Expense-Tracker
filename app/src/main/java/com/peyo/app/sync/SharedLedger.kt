package com.peyo.app.sync

import android.content.Context
import androidx.room.withTransaction
import com.peyo.app.data.AppDatabase
import com.peyo.app.data.SharedShare
import org.json.JSONObject

/**
 * What other Peyo users have recorded against this account's mobile number.
 *
 * The rows belong to those other accounts, so they are fetched through the shared_with_me()
 * function in supabase/schema.sql rather than through the ordinary sync, which only ever touches
 * the signed-in account's own tables.
 */
class SharedLedger(private val context: Context) {

    private val db = AppDatabase.get(context)
    private val dao = db.sharedShareDao()
    private val account = Account(context)

    /**
     * Fetches the current set and stores it, returning the shares that are new, still open and not
     * yet notified -- what the background check tells the user about.
     */
    suspend fun refresh(): Response<List<SharedShare>> {
        val session = account.stored() ?: return Response.Ok(emptyList())
        if (session.phone.isBlank()) {
            // Without a number nothing can be shared with this account; clear anything left from
            // a number since removed.
            dao.clear()
            return Response.Ok(emptyList())
        }
        val response = call("shared_with_me", JSONObject())
        val body = when (response) {
            is Response.Ok -> response.body
            is Response.Offline -> return response
            is Response.Rejected -> return response
        }
        val now = System.currentTimeMillis()
        val incoming = body.asJsonArray()?.map(::shareFrom)?.filterNotNull().orEmpty()
        return db.withTransaction {
            val held = dao.all().associateBy { it.splitId }
            // A share reopened by its owner is news again; one already told about and unchanged
            // is not.
            val merged = incoming.map { row ->
                val before = held[row.splitId]
                val reopened = before != null && before.settledAt != null && row.settledAt == null
                row.copy(notifiedAt = if (reopened) null else before?.notifiedAt)
            }
            dao.upsert(merged)
            dao.deleteAllExcept(merged.map { it.splitId }.ifEmpty { listOf("") })
            merged.filter { it.open && it.notifiedAt == null }.also { fresh ->
                if (fresh.isNotEmpty()) dao.markNotified(fresh.map { it.splitId }, now)
            }
        }.let { fresh ->
            SharedNotifier.notify(context, fresh)
            Response.Ok(fresh)
        }
    }

    /** Tells the owner a share is paid, then re-reads so the screen shows it settled. */
    suspend fun markSettled(splitId: String): Response<Unit> {
        val response = call("mark_shared_settled", JSONObject().put("p_split", splitId))
        if (response is Response.Ok) refresh()
        return response.map { }
    }

    private suspend fun call(function: String, args: JSONObject): Response<String> {
        val token = when (val fresh = account.freshToken()) {
            null -> return Response.Rejected(401, "Sign in to see what friends have shared with you.")
            is Response.Offline -> return fresh
            is Response.Rejected -> return fresh
            is Response.Ok -> fresh.body
        }
        var response = Supabase.request("POST", "/rest/v1/rpc/$function", args.toString(), token)
        if (response is Response.Rejected && response.status == 401) {
            val retried = account.freshToken(force = true)
            if (retried is Response.Ok) {
                response = Supabase.request("POST", "/rest/v1/rpc/$function", args.toString(), retried.body)
            }
        }
        return response
    }

    companion object {
        /** One row of shared_with_me(), or null if it is missing what every row must have. */
        internal fun shareFrom(json: JSONObject): SharedShare? = SharedShare(
            splitId = json.stringOrNull("split_id") ?: return null,
            expenseId = json.stringOrNull("expense_id").orEmpty(),
            ownerId = json.stringOrNull("owner_id") ?: return null,
            ownerName = json.stringOrNull("owner_name") ?: "A Peyo user",
            ownerPhone = json.stringOrNull("owner_phone").orEmpty(),
            merchant = json.stringOrNull("merchant").orEmpty(),
            category = json.stringOrNull("category").orEmpty(),
            note = json.stringOrNull("note").orEmpty(),
            paidAt = Rows.fromIso(json.stringOrNull("paid_at")) ?: return null,
            amountPaise = json.optLong("amount_minor"),
            currency = json.stringOrNull("currency") ?: "INR",
            iOwe = json.optBoolean("i_owe", true),
            settledAt = Rows.fromIso(json.stringOrNull("settled_at")),
            removed = json.optBoolean("removed", false),
            updatedAt = Rows.fromIso(json.stringOrNull("updated_at")) ?: 0L
        )
    }
}
