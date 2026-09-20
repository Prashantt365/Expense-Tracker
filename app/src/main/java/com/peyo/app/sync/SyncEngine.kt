package com.peyo.app.sync

import android.content.Context
import android.util.Log
import com.peyo.app.AppCurrency
import com.peyo.app.data.Category
import com.peyo.app.data.Expense
import com.peyo.app.data.ExpenseSplit
import com.peyo.app.data.Person
import com.peyo.app.data.SyncConflict
import com.peyo.app.data.SyncDao
import com.peyo.app.data.SyncedTable
import com.peyo.app.data.Synced
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
        .getSharedPreferences("peyo.sync", Context.MODE_PRIVATE)

    /** When the last run finished, so the UI can say so across process restarts. */
    val lastSyncAt: Long get() = marks.getLong(KEY_LAST_SYNC_AT, 0L)

    suspend fun sync(): SyncOutcome = guarded { syncOrThrow(full = false) }

    /**
     * Re-reads the whole of the account's history rather than only what changed since last time.
     *
     * This is the restore a user wants after a reinstall: the watermarks are dropped so every row
     * comes back down, and everything held locally is re-offered so nothing typed while signed out
     * is lost on the way up.
     */
    suspend fun restore(): SyncOutcome = guarded { syncOrThrow(full = true) }

    /**
     * Nothing in here is allowed to take the app down with it.
     *
     * A row the local schema refuses is a bug worth reading in the log, but to the user it is a
     * backup that did not happen -- and the local database is still exactly what it was, because
     * every write went there first. Tapping "Back up now" used to crash the app outright for
     * precisely this reason, on the constraint violation a first restore after a reinstall raised.
     */
    private suspend fun guarded(block: suspend () -> SyncOutcome): SyncOutcome =
        runCatching { block() }.getOrElse { failure ->
            Log.w(TAG, "Sync failed", failure)
            SyncOutcome.Failed(failure.message ?: "Something went wrong while syncing.")
        }

    private suspend fun syncOrThrow(full: Boolean): SyncOutcome {
        if (!Supabase.isConfigured) return SyncOutcome.NotSignedIn
        val session = account.stored() ?: return SyncOutcome.NotSignedIn
        val token = account.freshToken() ?: return SyncOutcome.NotSignedIn

        // Signing in as somebody else must not hand them the previous account's rows, nor silently
        // merge the two. Everything local is re-offered to the new account instead.
        if (full || marks.getString(KEY_OWNER, null) != session.userId) {
            claimEverythingForNewOwner(session.userId)
        }

        var pushed = 0
        var pulled = 0
        var conflicts = 0

        // Pull before push, so that a conflict is found before the push overwrites the far side.
        for (table in ORDER) {
            when (val result = pullTable(table, token)) {
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

        // Best effort, and last. A project whose schema predates the profiles table should still
        // get its expenses backed up rather than have the whole run reported as a failure.
        Profiles.touch(token, session.userId, AppCurrency.code)

        marks.edit().putLong(KEY_LAST_SYNC_AT, System.currentTimeMillis()).apply()
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
        val lastSync = marks.getLong(KEY_LAST_SYNC_AT, 0L)
        marks.edit().clear().putString(KEY_OWNER, userId).putLong(KEY_LAST_SYNC_AT, lastSync).apply()
    }

    // --- pulling ---

    private data class Pulled(val applied: Int, val conflicts: Int)

    /**
     * Reads every row of one table that has changed since the watermark, a page at a time.
     *
     * The loop is what makes a restore whole. A page is capped, and stopping after one of them
     * would leave an account with more history than that cap permanently half-restored: the
     * watermark would advance, the next run would fetch the following page, and the user would be
     * told their history was back several times before it actually was.
     */
    private suspend fun pullTable(table: SyncedTable, token: String): Response<Pulled> {
        var applied = 0
        var conflicts = 0

        repeat(MAX_PAGES) {
            // The mark is held as an instant, not as the text the server sent. Postgres varies how
            // many fractional digits it writes, and comparing those strings is only sometimes the
            // same as comparing the times they mean, which would quietly strand rows on the wrong
            // side.
            val sinceMillis = marks.getLong(waterMarkKey(table), 0L)
            val since = Rows.toIso(sinceMillis)
            // Greater-or-equal rather than greater: two rows written in the same millisecond would
            // otherwise let the second one fall through the gap for good. Re-reading the boundary
            // row each time is cheap, and applying it again changes nothing.
            val path =
                "/rest/v1/" + table.remote + "?updated_at=gte." + since +
                    "&order=updated_at.asc&limit=" + PAGE
            val rows = when (val response = Supabase.request("GET", path, accessToken = token)) {
                is Response.Offline -> return response
                is Response.Rejected -> return response
                is Response.Ok -> response.body.asJsonArray() ?: JSONArray()
            }
            if (rows.length() == 0) return Response.Ok(Pulled(applied, conflicts))

            val page = applyPage(table, rows)
            applied += page.applied
            conflicts += page.conflicts

            // Only advanced once every row in the page has been dealt with, so a run that stops
            // halfway resumes from where it actually got to rather than from where it hoped to.
            if (page.newest > sinceMillis) {
                marks.edit().putLong(waterMarkKey(table), page.newest).apply()
            } else {
                // A full page written inside one millisecond, or a page nothing could be placed
                // from. Asking for it again would fetch the very same rows forever.
                return Response.Ok(Pulled(applied, conflicts))
            }
            if (rows.length() < PAGE) return Response.Ok(Pulled(applied, conflicts))
        }
        return Response.Ok(Pulled(applied, conflicts))
    }

    private data class Page(val applied: Int, val conflicts: Int, val newest: Long)

    private suspend fun applyPage(table: SyncedTable, rows: JSONArray): Page {
        var applied = 0
        var conflicts = 0
        var newest = 0L
        // A row that could not be written holds the watermark where it is, so the next run offers
        // it again rather than stepping over it and losing it for good.
        var blocked: Long? = null
        val now = System.currentTimeMillis()

        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val rowAt = Rows.fromIso(row.stringOrNull("updated_at")) ?: continue
            val decision = runCatching { apply(table, row, now) }.getOrElse { failure ->
                // One unwritable row must not cost the user the other 199 in the page.
                Log.w(TAG, "Could not apply a " + table.remote + " row", failure)
                Merge.BLOCKED
            }
            when (decision) {
                Merge.CONFLICT -> conflicts++
                Merge.INSERT, Merge.UPDATE -> applied++
                Merge.SKIP -> Unit
                Merge.BLOCKED -> blocked = minOf(blocked ?: rowAt, rowAt)
            }
            if (rowAt > newest) newest = rowAt
        }

        // The watermark stops just short of the first row that could not be placed.
        blocked?.let { if (it < newest) newest = it }
        return Page(applied, conflicts, newest)
    }

    private suspend fun apply(table: SyncedTable, row: JSONObject, now: Long): Merge {
        val remoteId = row.stringOrNull("id") ?: return Merge.SKIP
        val remoteUpdatedAt = Rows.fromIso(row.stringOrNull("updated_at")) ?: return Merge.SKIP
        val local: Synced? = byRemoteId(table, remoteId)

        // Nothing here carries this row's id, but something here may already *be* this row under
        // another id. Sorting that out before deciding anything is what stops a first restore
        // after a reinstall from colliding with the categories the fresh install just seeded.
        if (local == null) {
            unlinkedTwin(table, row)?.let { return adopt(table, row, it, remoteUpdatedAt, now) }
        }

        return when (val decision = mergeDecision(local, remoteUpdatedAt)) {
            Merge.SKIP, Merge.BLOCKED -> decision
            Merge.CONFLICT -> {
                dao.recordConflict(
                    SyncConflict(
                        entity = table.local,
                        remoteId = remoteId,
                        localJson = localJson(table, local!!).toString(),
                        remoteJson = row.toString(),
                        localUpdatedAt = local.updatedAt,
                        remoteUpdatedAt = remoteUpdatedAt,
                        detectedAt = now
                    )
                )
                decision
            }
            Merge.INSERT, Merge.UPDATE -> {
                if (write(table, row, local, now)) decision else Merge.BLOCKED
            }
        }
    }

    /**
     * A local row that is plainly the same thing as [row] but has never been linked to it.
     *
     * Only the two tables with a unique name can have one. A phone that has never synced mints its
     * own id for every person on it, and a fresh install seeds its own categories before it has
     * any idea what the account already holds, so "Food" exists on both sides under two ids.
     * Inserting the pulled copy beside the local one breaks the unique index on the name, and that
     * broken insert is what used to abort a restore and take the app down with it.
     *
     * Tombstoned rows count: a deleted name still occupies the index.
     */
    private suspend fun unlinkedTwin(table: SyncedTable, row: JSONObject): Synced? {
        val name = row.stringOrNull("name") ?: return null
        return when (table) {
            SyncedTable.CATEGORY -> dao.categoryByName(name)
            SyncedTable.PERSON -> dao.personByName(name)
            SyncedTable.EXPENSE, SyncedTable.SPLIT -> null
        }
    }

    /**
     * Gives the local row the server's identity, then lets the newer of the two say what it holds.
     *
     * Deliberately not a conflict. The two rows were never linked, so neither has diverged from
     * anything -- they are two devices that independently made a category called Food -- and
     * asking a user to adjudicate their own seeded defaults on first launch would be noise. When
     * the local row is the newer one it keeps its contents and stays unsynced, so the push that
     * follows carries it up under the id the server already knows it by.
     */
    private suspend fun adopt(
        table: SyncedTable,
        row: JSONObject,
        twin: Synced,
        remoteUpdatedAt: Long,
        now: Long
    ): Merge {
        if (remoteUpdatedAt > twin.updatedAt) {
            return if (write(table, row, twin, now)) Merge.UPDATE else Merge.BLOCKED
        }
        val remoteId = row.stringOrNull("id") ?: return Merge.SKIP
        when (twin) {
            is Category -> dao.updateCategory(twin.copy(remoteId = remoteId, syncedAt = null))
            is Person -> dao.updatePerson(twin.copy(remoteId = remoteId, syncedAt = null))
            else -> return Merge.SKIP
        }
        return Merge.SKIP
    }

    private suspend fun byRemoteId(table: SyncedTable, remoteId: String): Synced? = when (table) {
        SyncedTable.EXPENSE -> dao.expenseByRemoteId(remoteId)
        SyncedTable.PERSON -> dao.personByRemoteId(remoteId)
        SyncedTable.CATEGORY -> dao.categoryByRemoteId(remoteId)
        SyncedTable.SPLIT -> dao.splitByRemoteId(remoteId)
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
                path = "/rest/v1/" + table.remote,
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
        val local: Synced? = byRemoteId(table, conflict.remoteId)
        if (!write(table, row, local, System.currentTimeMillis())) return false
        dao.clearConflict(conflictId)
        return true
    }

    /**
     * The user id is only ever wanted for a row being sent, and a conflict record is not sent --
     * it is shown. Storing the signed-in id in it would date the moment it was recorded.
     */
    private fun localJson(table: SyncedTable, local: Synced): JSONObject {
        val userId = account.stored()?.userId.orEmpty()
        return when (table) {
            SyncedTable.EXPENSE -> Rows.expenseJson(local as Expense, userId, AppCurrency.code)
            SyncedTable.PERSON -> Rows.personJson(local as Person, userId)
            SyncedTable.CATEGORY -> Rows.categoryJson(local as Category, userId)
            SyncedTable.SPLIT -> {
                val split = local as ExpenseSplit
                Rows.splitJson(split, userId, expenseRemoteId = "", personRemoteId = null)
            }
        }
    }

    private fun waterMarkKey(table: SyncedTable) = "pulledThrough." + table.local

    private companion object {
        const val TAG = "SyncEngine"

        /** People and categories are referenced by nothing; splits reference everything. */
        val ORDER = listOf(
            SyncedTable.PERSON, SyncedTable.CATEGORY, SyncedTable.EXPENSE, SyncedTable.SPLIT
        )
        const val PAGE = 200

        /** A ceiling on the page loop, so a server that keeps answering cannot spin forever. */
        const val MAX_PAGES = 200
        const val KEY_OWNER = "ownerUserId"
        const val KEY_LAST_SYNC_AT = "lastSyncAt"
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
