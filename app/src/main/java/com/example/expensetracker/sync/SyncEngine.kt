package com.example.expensetracker.sync

import android.content.Context
import com.example.expensetracker.AppCurrency
import com.example.expensetracker.data.Category
import com.example.expensetracker.data.Expense
import com.example.expensetracker.data.ExpenseSplit
import com.example.expensetracker.data.Person
import com.example.expensetracker.data.SyncConflict
import com.example.expensetracker.data.SyncDao
import com.example.expensetracker.data.SyncedTable
import com.example.expensetracker.data.Synced
import org.json.JSONArray
import org.json.JSONObject

/** What one run of [SyncEngine.sync] came to. */
sealed interface SyncOutcome {
    data class Done(val pushed: Int, val pulled: Int, val conflicts: Int) : SyncOutcome

    /** Nothing was reached. The local database is untouched and trying later is the whole fix. */
    data class Offline(val reason: String) : SyncOutcome

    /** The server refused. Worth showing, because it usually means the schema or the account. */
    data class Failed(val message: String) : SyncOutcome

    data object NotSignedIn : SyncOutcome
}

/**
 * Pushes what changed here and pulls what changed elsewhere.
 *
 * Offline first throughout: every write has already gone into the local database before this runs,
 * so a failed sync costs the backup and never the data. That is also why nothing here throws --
 * the caller gets an outcome, and a lost connection is not an error the user should read about.
 *
 * Order matters in both directions. People and categories carry no references, expenses reference
 * neither, and splits reference both, so splits go last and arrive last. A split whose expense has
 * not reached this device yet is left alone rather than guessed at; the next run finds it.
 */
class SyncEngine(
    context: Context,
    private val dao: SyncDao,
    private val account: Account = Account(context)
) {

    private val marks = context.applicationContext
        .getSharedPreferences("spendwise.sync", Context.MODE_PRIVATE)

    suspend fun sync(): SyncOutcome {
        if (!Supabase.isConfigured) return SyncOutcome.NotSignedIn
        val session = account.stored() ?: return SyncOutcome.NotSignedIn
        val token = account.freshToken() ?: return SyncOutcome.NotSignedIn

        // Signing in as somebody else must not hand them the previous account's rows, nor silently
        // merge the two. Everything local is re-offered to the new account instead.
        if (marks.getString(KEY_OWNER, null) != session.userId) {
            claimEverythingForNewOwner(session.userId)
        }

        var pushed = 0
        var pulled = 0
        var conflicts = 0

        // Pull before push, so that a conflict is found before the push overwrites the far side.
        for (table in ORDER) {
            when (val result = pullTable(table, token, session.userId)) {
                is Response.Offline -> return SyncOutcome.Offline(result.cause)
                is Response.Rejected -> return SyncOutcome.Failed(result.message)
                is Response.Ok -> {
                    pulled += result.body.applied
                    conflicts += result.body.conflicts
                }
            }
        }

        for (table in ORDER) {
            when (val result = pushTable(table, token, session.userId)) {
                is Response.Offline -> return SyncOutcome.Offline(result.cause)
                is Response.Rejected -> return SyncOutcome.Failed(result.message)
                is Response.Ok -> pushed += result.body
            }
        }

        return SyncOutcome.Done(pushed, pulled, conflicts)
    }

    /**
     * Everything held locally becomes unsynced and the watermarks are dropped, so the new account
     * receives it all and reads the whole of its own history back. Conflicts belonging to the old
     * account are meaningless to the new one and go.
     */
    private suspend fun claimEverythingForNewOwner(userId: String) {
        dao.markAllExpensesUnsynced()
        dao.markAllPeopleUnsynced()
        dao.markAllCategoriesUnsynced()
        dao.markAllSplitsUnsynced()
        dao.clearAllConflicts()
        marks.edit().clear().putString(KEY_OWNER, userId).apply()
    }

    // --- pulling ---

    private data class Pulled(val applied: Int, val conflicts: Int)

    private suspend fun pullTable(
        table: SyncedTable,
        token: String,
        userId: String
    ): Response<Pulled> {
        // The mark is held as an instant, not as the text the server sent. Postgres varies how
        // many fractional digits it writes, and comparing those strings is only sometimes the same
        // as comparing the times they mean, which would quietly strand rows on the wrong side.
        val sinceMillis = marks.getLong(waterMarkKey(table), 0L)
        val since = Rows.toIso(sinceMillis)
        // Greater-or-equal rather than greater: two rows written in the same millisecond would
        // otherwise let the second one fall through the gap for good. Re-reading the boundary row
        // each time is cheap, and applying it again changes nothing.
        val path = "/rest/v1/${table.remote}?updated_at=gte.$since&order=updated_at.asc&limit=$PAGE"
        val response = Supabase.request("GET", path, accessToken = token)
        val rows = when (response) {
            is Response.Offline -> return response
            is Response.Rejected -> return response
            is Response.Ok -> response.body.asJsonArray() ?: JSONArray()
        }

        var applied = 0
        var conflicts = 0
        var newest = sinceMillis
        val now = System.currentTimeMillis()

        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            Rows.fromIso(row.stringOrNull("updated_at"))?.let { if (it > newest) newest = it }
            when (apply(table, row, userId, now)) {
                Merge.CONFLICT -> conflicts++
                Merge.INSERT, Merge.UPDATE -> applied++
                Merge.SKIP -> Unit
            }
        }

        // Only advanced once every row in the page has been dealt with, so a run that stops
        // halfway resumes from where it actually got to rather than from where it hoped to.
        if (newest > sinceMillis) marks.edit().putLong(waterMarkKey(table), newest).apply()
        return Response.Ok(Pulled(applied, conflicts))
    }

    private suspend fun apply(
        table: SyncedTable,
        row: JSONObject,
        userId: String,
        now: Long
    ): Merge {
        val remoteId = row.stringOrNull("id") ?: return Merge.SKIP
        val remoteUpdatedAt = Rows.fromIso(row.stringOrNull("updated_at")) ?: return Merge.SKIP
        val local: Synced? = when (table) {
            SyncedTable.EXPENSE -> dao.expenseByRemoteId(remoteId)
            SyncedTable.PERSON -> dao.personByRemoteId(remoteId)
            SyncedTable.CATEGORY -> dao.categoryByRemoteId(remoteId)
            SyncedTable.SPLIT -> dao.splitByRemoteId(remoteId)
        }

        return when (val decision = mergeDecision(local, remoteUpdatedAt)) {
            Merge.SKIP -> decision
            Merge.CONFLICT -> {
                dao.recordConflict(
                    SyncConflict(
                        entity = table.local,
                        remoteId = remoteId,
                        localJson = localJson(table, local!!, userId).toString(),
                        remoteJson = row.toString(),
                        localUpdatedAt = local.updatedAt,
                        remoteUpdatedAt = remoteUpdatedAt,
                        detectedAt = now
                    )
                )
                decision
            }
            Merge.INSERT, Merge.UPDATE -> {
                val wrote = write(table, row, local, now)
                if (wrote) decision else Merge.SKIP
            }
        }
    }

    /** Returns false when the row cannot be placed yet, which only a split can be. */
    private suspend fun write(table: SyncedTable, row: JSONObject, local: Synced?, now: Long): Boolean {
        // Zero when there is no local row, which is what tells Room to allocate one. Anything else
        // would update a row at random or insert a duplicate of one already here.
        val localId = local?.localId ?: 0L
        when (table) {
            SyncedTable.EXPENSE -> {
                val expense = Rows.expenseFrom(row, localId, now) ?: return false
                // A pulled row carries no screenshot, so an existing local one is kept rather than
                // blanked: the attachment never left this phone and is still perfectly good.
                val keepSource = (local as? Expense)?.sourceUri
                if (local == null) dao.insertExpense(expense.copy(sourceUri = keepSource))
                else dao.updateExpense(expense.copy(sourceUri = keepSource))
            }

            SyncedTable.PERSON -> {
                val person = Rows.personFrom(row, localId, now) ?: return false
                if (local == null) dao.insertPerson(person) else dao.updatePerson(person)
            }

            SyncedTable.CATEGORY -> {
                val category = Rows.categoryFrom(row, localId, now) ?: return false
                if (local == null) dao.insertCategory(category) else dao.updateCategory(category)
            }

            SyncedTable.SPLIT -> {
                val expenseRemote = row.stringOrNull("expense_id") ?: return false
                val expenseId = dao.expenseByRemoteId(expenseRemote)?.id ?: return false
                val personRemote = row.stringOrNull("person_id")
                val personId = personRemote?.let { dao.personByRemoteId(it)?.id ?: return false }
                val split = Rows.splitFrom(row, localId, expenseId, personId, now) ?: return false
                if (local == null) dao.insertSplit(split) else dao.updateSplit(split)
            }
        }
        return true
    }

    // --- pushing ---

    private suspend fun pushTable(table: SyncedTable, token: String, userId: String): Response<Int> {
        val currency = AppCurrency.code
        val expenseIds = if (table == SyncedTable.SPLIT) dao.expenseIds().associate { it.id to it.remoteId } else emptyMap()
        val personIds = if (table == SyncedTable.SPLIT) dao.personIds().associate { it.id to it.remoteId } else emptyMap()

        val pending: List<Pair<Long, JSONObject>> = when (table) {
            SyncedTable.EXPENSE ->
                dao.expensesToPush().map { it.id to Rows.expenseJson(it, userId, currency) }

            SyncedTable.PERSON ->
                dao.peopleToPush().map { it.id to Rows.personJson(it, userId) }

            SyncedTable.CATEGORY ->
                dao.categoriesToPush().map { it.id to Rows.categoryJson(it, userId) }

            SyncedTable.SPLIT -> dao.splitsToPush().mapNotNull { split ->
                // A split is worthless without the rows it points at, and a foreign key the server
                // does not recognise would be refused outright and stall every other table with it.
                val expenseRemote = expenseIds[split.expenseId] ?: return@mapNotNull null
                val personRemote = split.personId?.let { personIds[it] ?: return@mapNotNull null }
                split.id to Rows.splitJson(split, userId, expenseRemote, personRemote)
            }
        }
        if (pending.isEmpty()) return Response.Ok(0)

        var sent = 0
        // Sent in batches so that one enormous first sync is not one enormous request.
        for (batch in pending.chunked(PAGE)) {
            val body = JSONArray().apply { batch.forEach { put(it.second) } }.toString()
            val response = Supabase.request(
                method = "POST",
                path = "/rest/v1/${table.remote}",
                body = body,
                accessToken = token,
                // An upsert: the row may already be there from another device or an earlier run.
                headers = mapOf("Prefer" to "resolution=merge-duplicates,return=minimal")
            )
            when (response) {
                is Response.Offline -> return response
                is Response.Rejected -> return response
                is Response.Ok -> {
                    val ids = batch.map { it.first }
                    val at = System.currentTimeMillis()
                    when (table) {
                        SyncedTable.EXPENSE -> dao.markExpensesSynced(ids, at)
                        SyncedTable.PERSON -> dao.markPeopleSynced(ids, at)
                        SyncedTable.CATEGORY -> dao.markCategoriesSynced(ids, at)
                        SyncedTable.SPLIT -> dao.markSplitsSynced(ids, at)
                    }
                    sent += ids.size
                }
            }
        }
        return Response.Ok(sent)
    }

    // --- conflict resolution ---

    /**
     * Keeping the local version means only forgetting the disagreement: the row is already what
     * the user wants and is already unsynced, so the next push carries it up and the server takes
     * it. Keeping the remote version writes it down as an ordinary pulled row.
     */
    suspend fun resolve(conflictId: Long, keepLocal: Boolean): Boolean {
        val conflict = dao.conflict(conflictId) ?: return false
        if (keepLocal) {
            dao.clearConflict(conflictId)
            return true
        }
        val table = conflict.table ?: return false
        val row = conflict.remoteJson.asJsonObject() ?: return false
        val local: Synced? = when (table) {
            SyncedTable.EXPENSE -> dao.expenseByRemoteId(conflict.remoteId)
            SyncedTable.PERSON -> dao.personByRemoteId(conflict.remoteId)
            SyncedTable.CATEGORY -> dao.categoryByRemoteId(conflict.remoteId)
            SyncedTable.SPLIT -> dao.splitByRemoteId(conflict.remoteId)
        }
        if (!write(table, row, local, System.currentTimeMillis())) return false
        dao.clearConflict(conflictId)
        return true
    }

    private fun localJson(table: SyncedTable, local: Synced, userId: String): JSONObject =
        when (table) {
            SyncedTable.EXPENSE -> Rows.expenseJson(local as Expense, userId, AppCurrency.code)
            SyncedTable.PERSON -> Rows.personJson(local as Person, userId)
            SyncedTable.CATEGORY -> Rows.categoryJson(local as Category, userId)
            SyncedTable.SPLIT -> {
                val split = local as ExpenseSplit
                Rows.splitJson(split, userId, expenseRemoteId = "", personRemoteId = null)
            }
        }

    private fun waterMarkKey(table: SyncedTable) = "pulledThrough.${table.local}"

    private companion object {
        /** People and categories are referenced by nothing; splits reference everything. */
        val ORDER = listOf(
            SyncedTable.PERSON, SyncedTable.CATEGORY, SyncedTable.EXPENSE, SyncedTable.SPLIT
        )
        const val PAGE = 200
        const val KEY_OWNER = "ownerUserId"
    }
}

/** The remote table each local one is stored in. */
val SyncedTable.remote: String
    get() = when (this) {
        SyncedTable.EXPENSE -> "expenses"
        SyncedTable.PERSON -> "people"
        SyncedTable.CATEGORY -> "categories"
        SyncedTable.SPLIT -> "expense_splits"
    }

/** Lets the engine read a local row number off any synced entity without four branches for it. */
private val Synced.localId: Long
    get() = when (this) {
        is Expense -> id
        is Person -> id
        is Category -> id
        is ExpenseSplit -> id
        else -> 0L
    }
