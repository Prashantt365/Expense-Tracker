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
import com.peyo.app.data.newRemoteId
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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

    private val idMemory = IdMemory(File(context.applicationContext.filesDir, "sync-account-ids.json"))

    /** When the last run finished, so the UI can say so across process restarts. */
    val lastSyncAt: Long get() = marks.getLong(KEY_LAST_SYNC_AT, 0L)

    suspend fun sync(): SyncOutcome = guarded { syncOrThrow(full = false) }

    /**
     * Re-reads the whole of the account's history rather than only what changed since last time.
     *
     * This is the restore a user wants after a reinstall: the watermarks are dropped so every row
     * comes back down. Anything held locally that the server turns out not to have is re-offered on
     * the way up. Rows the server does have are left in whatever state they were in, rather than all
     * being marked unsynced as they once were: an unsynced row meeting a server copy stamped later
     * by the server's clock read as a conflict, so a restore used to bury the user in choices
     * between two identical versions.
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
        // An expired token with no connection to refresh it over is being offline, not being
        // signed out, and a server that is only struggling is a failure worth retrying.
        val link = when (val fresh = account.freshToken()) {
            null -> return SyncOutcome.NotSignedIn
            is Response.Offline -> return SyncOutcome.Offline(fresh.cause)
            is Response.Rejected -> return SyncOutcome.Failed(fresh.message)
            is Response.Ok -> Link(fresh.body)
        }

        val previousOwner = marks.getString(KEY_OWNER, null)
        val sameOwner = previousOwner == session.userId
        if (!sameOwner) {
            claimEverythingForNewOwner(previousOwner, session.userId)
        } else if (full) {
            forgetWatermarks()
        }
        // Only a restore into the account these rows already belong to needs to learn which of
        // them the server lacks. After claiming for a new owner everything is being offered anyway.
        val seen = if (full && sameOwner) HashMap<SyncedTable, MutableSet<String>>() else null

        var pushed = 0
        var pulled = 0
        var conflicts = 0
        var pulledEverything = true

        // Pull before push, so that a conflict is found before the push could overwrite the far
        // side. A row found in conflict is then held back from the push until the user chooses.
        for (table in ORDER) {
            when (val result = pullTable(table, link, seen?.getOrPut(table) { HashSet() })) {
                is Response.Offline -> return SyncOutcome.Offline(result.cause)
                is Response.Rejected -> return refused(result)
                is Response.Ok -> {
                    pulled += result.body.applied
                    conflicts += result.body.conflicts
                    pulledEverything = pulledEverything && result.body.complete
                }
            }
        }

        // Re-offering needs the whole of the server's side to compare against. A pull that stopped
        // short would make everything beyond where it stopped look missing.
        if (seen != null && pulledEverything) reofferMissing(seen)

        for (table in ORDER) {
            when (val result = pushTable(table, link, session.userId)) {
                is Response.Offline -> return SyncOutcome.Offline(result.cause)
                is Response.Rejected -> return refused(result)
                is Response.Ok -> pushed += result.body
            }
        }

        // Best effort, and last. A project whose schema predates the profiles table should still
        // get its expenses backed up rather than have the whole run reported as a failure.
        Profiles.touch(link.token, session.userId, AppCurrency.code)

        marks.edit().putLong(KEY_LAST_SYNC_AT, System.currentTimeMillis()).apply()
        return SyncOutcome.Done(pushed, pulled, conflicts)
    }

    /** A refusal that turned out to be the session ending is reported as that, not as an error. */
    private fun refused(result: Response.Rejected): SyncOutcome =
        if (result.status == 401 && account.stored() == null) SyncOutcome.NotSignedIn
        else SyncOutcome.Failed(result.message)

    /**
     * The run's access token, and the one recovery from its being turned down.
     *
     * The expiry a session carries is anchored to the device clock, so a token it calls live can
     * still be refused with a 401. That is answered by forcing one refresh and repeating the
     * request, once per run: a token refused again straight after being refreshed is not going to
     * be fixed by refreshing it a third time. Repeating is safe because a 401 means the request
     * was never carried out, and every write here is an upsert anyway.
     */
    private inner class Link(var token: String) {
        private var refreshed = false

        suspend fun request(
            method: String,
            path: String,
            body: String? = null,
            headers: Map<String, String> = emptyMap()
        ): Response<String> {
            val first = Supabase.request(method, path, body, token, headers)
            if (first !is Response.Rejected || first.status != 401 || refreshed) return first
            refreshed = true
            return when (val fresh = account.freshToken(force = true)) {
                is Response.Ok -> {
                    token = fresh.body
                    Supabase.request(method, path, body, token, headers)
                }
                // Signed out by the refresh itself: the original 401 is what says so.
                null -> first
                is Response.Offline -> fresh
                is Response.Rejected -> fresh
            }
        }
    }

    /**
     * Everything held locally becomes unsynced and the watermarks are dropped, so the new account
     * receives it all and reads the whole of its own history back. Conflicts belonging to the old
     * account are meaningless to the new one and go.
     *
     * Rows coming from another account are given ids of their own in this one first -- see
     * [reassignIds] for why, and for why switching back hands the old ones back. A phone that has
     * never been signed in to anything keeps its ids: nothing it holds can be on the server under
     * anybody else yet.
     */
    private suspend fun claimEverythingForNewOwner(previousOwner: String?, userId: String) {
        if (previousOwner != null) carryIntoAnotherAccount(previousOwner, userId)
        dao.markAllExpensesUnsynced()
        dao.markAllPeopleUnsynced()
        dao.markAllCategoriesUnsynced()
        dao.markAllSplitsUnsynced()
        dao.clearAllConflicts()
        val lastSync = marks.getLong(KEY_LAST_SYNC_AT, 0L)
        val offset = marks.getLong(KEY_CLOCK_OFFSET, 0L)
        // The owner is written last, so a run that dies partway through claims again next time.
        marks.edit().clear()
            .putString(KEY_OWNER, userId)
            .putLong(KEY_LAST_SYNC_AT, lastSync)
            .putLong(KEY_CLOCK_OFFSET, offset)
            .apply()
    }

    /**
     * Renames every row for the account being moved into.
     *
     * Splits point at their expense and person by local row number, and are sent with whatever
     * remote ids those rows hold at push time, so renaming the rows is all it takes for every
     * reference to follow. Each row is read again just before it is written, so that an edit made
     * while this runs is carried along rather than overwritten by the copy read at the start.
     */
    private suspend fun carryIntoAnotherAccount(from: String, to: String) {
        val current = dao.allPeople().map { it.remoteId } + dao.allCategories().map { it.remoteId } +
            dao.allExpenses().map { it.remoteId } + dao.allSplits().map { it.remoteId }
        val plan = reassignIds(current, idMemory.load(), from, to, ::newRemoteId)
        for ((old, new) in plan.ids) {
            runCatching { rename(old, new) }.getOrElse { failure ->
                // A remembered id that somehow clashes with another row is not worth failing the
                // switch over. A fresh one does the job, at the cost of that row being copied if
                // the phone ever goes back.
                Log.w(TAG, "Could not hand a row its remembered id", failure)
                rename(old, newRemoteId())
            }
        }
        idMemory.save(plan.memory)
    }

    private suspend fun rename(old: String, new: String) {
        dao.personByRemoteId(old)?.let { return dao.updatePerson(it.copy(remoteId = new)) }
        dao.categoryByRemoteId(old)?.let { return dao.updateCategory(it.copy(remoteId = new)) }
        dao.expenseByRemoteId(old)?.let { return dao.updateExpense(it.copy(remoteId = new)) }
        dao.splitByRemoteId(old)?.let { return dao.updateSplit(it.copy(remoteId = new)) }
    }

    private fun forgetWatermarks() {
        val editor = marks.edit()
        SyncedTable.entries.forEach { editor.remove(waterMarkKey(it)) }
        editor.apply()
    }

    /**
     * After a whole-history pull, anything held here as in step with the server that the server
     * did not send back is something the server has lost -- a project reset, a row removed by
     * hand -- and is marked to go up again.
     */
    private suspend fun reofferMissing(seen: Map<SyncedTable, Set<String>>) {
        fun missing(table: SyncedTable, row: Synced) =
            row.syncedAt != null && row.remoteId !in seen[table].orEmpty()

        dao.allPeople().filter { missing(SyncedTable.PERSON, it) }.forEach { held ->
            dao.personByRemoteId(held.remoteId)?.let { dao.updatePerson(it.copy(syncedAt = null)) }
        }
        dao.allCategories().filter { missing(SyncedTable.CATEGORY, it) }.forEach { held ->
            dao.categoryByRemoteId(held.remoteId)?.let { dao.updateCategory(it.copy(syncedAt = null)) }
        }
        dao.allExpenses().filter { missing(SyncedTable.EXPENSE, it) }.forEach { held ->
            dao.expenseByRemoteId(held.remoteId)?.let { dao.updateExpense(it.copy(syncedAt = null)) }
        }
        dao.allSplits().filter { missing(SyncedTable.SPLIT, it) }.forEach { held ->
            dao.splitByRemoteId(held.remoteId)?.let { dao.updateSplit(it.copy(syncedAt = null)) }
        }
    }

    // --- pulling ---

    /** [complete] is whether the pull reached the end of the table rather than stopping short. */
    private data class Pulled(val applied: Int, val conflicts: Int, val complete: Boolean = true)

    /**
     * Reads every row of one table that has changed since the watermark, a page at a time.
     *
     * The loop is what makes a restore whole. A page is capped, and stopping after one of them
     * would leave an account with more history than that cap permanently half-restored: the
     * watermark would advance, the next run would fetch the following page, and the user would be
     * told their history was back several times before it actually was.
     *
     * [seen] collects the id of every row read, for a restore to tell what the server lacks.
     */
    private suspend fun pullTable(
        table: SyncedTable,
        link: Link,
        seen: MutableSet<String>?
    ): Response<Pulled> {
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
            val rows = when (val response = link.request("GET", path)) {
                is Response.Offline -> return response
                is Response.Rejected -> return response
                is Response.Ok -> response.body.asJsonArray() ?: JSONArray()
            }
            if (rows.length() == 0) return Response.Ok(Pulled(applied, conflicts))

            if (seen != null) rows.map { it.stringOrNull("id") }.forEach { id -> id?.let(seen::add) }
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
                return Response.Ok(Pulled(applied, conflicts, complete = rows.length() < PAGE))
            }
            if (rows.length() < PAGE) return Response.Ok(Pulled(applied, conflicts))
        }
        return Response.Ok(Pulled(applied, conflicts, complete = false))
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

        // Only asked when it can change the answer: a row with edits waiting to go up.
        val same = local != null && local.syncedAt == null && holdsSame(table, row, local)
        val offset = marks.getLong(KEY_CLOCK_OFFSET, 0L)
        return when (val decision = mergeDecision(local, remoteUpdatedAt, same, offset)) {
            Merge.SKIP, Merge.BLOCKED -> decision
            Merge.CONFLICT -> {
                // The same disagreement is met again whenever the row sits on a pull's boundary.
                // Recording it afresh would give it a new id under a screen that may be showing
                // the old one, so an open record keeps its id and is only refreshed.
                val open = dao.observeConflicts().first()
                    .firstOrNull { it.entity == table.local && it.remoteId == remoteId }
                dao.recordConflict(
                    SyncConflict(
                        id = open?.id ?: 0L,
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
                val (expenseId, personId) = splitRefs(row) ?: return false
                val split = Rows.splitFrom(row, localId, expenseId, personId, now) ?: return false
                if (local == null) dao.insertSplit(split) else dao.updateSplit(split)
            }
        }
        return true
    }

    /** A pulled split's expense and person as local row numbers, or null if either is not here yet. */
    private suspend fun splitRefs(row: JSONObject): Pair<Long, Long?>? {
        val expenseRemote = row.stringOrNull("expense_id") ?: return null
        val expenseId = dao.expenseByRemoteId(expenseRemote)?.id ?: return null
        val personRemote = row.stringOrNull("person_id")
        val personId = personRemote?.let { dao.personByRemoteId(it)?.id ?: return null }
        return expenseId to personId
    }

    /** Whether the pulled [row] says the same thing as [local], read into local terms first. */
    private suspend fun holdsSame(table: SyncedTable, row: JSONObject, local: Synced): Boolean {
        val remote: Synced = when (table) {
            SyncedTable.EXPENSE -> Rows.expenseFrom(row, 0L, 0L)
            SyncedTable.PERSON -> Rows.personFrom(row, 0L, 0L)
            SyncedTable.CATEGORY -> Rows.categoryFrom(row, 0L, 0L)
            SyncedTable.SPLIT -> splitRefs(row)?.let { (expenseId, personId) ->
                Rows.splitFrom(row, 0L, expenseId, personId, 0L)
            }
        } ?: return false
        return sameContent(local, remote)
    }

    // --- pushing ---

    /** A row about to be sent, with what is needed to settle it afterwards. */
    private class Outgoing(val remoteId: String, val sentAt: Long, val json: JSONObject)

    private suspend fun pushTable(table: SyncedTable, link: Link, userId: String): Response<Int> {
        val currency = AppCurrency.code
        val expenseIds = if (table == SyncedTable.SPLIT) dao.expenseIds().associate { it.id to it.remoteId } else emptyMap()
        val personIds = if (table == SyncedTable.SPLIT) dao.personIds().associate { it.id to it.remoteId } else emptyMap()

        // Rows with an open conflict are not among these: the queries hold them back until the
        // user has chosen, so the server's side of a disagreement is never overwritten unasked.
        val pending: List<Outgoing> = when (table) {
            SyncedTable.EXPENSE -> dao.expensesToPush().map {
                Outgoing(it.remoteId, it.updatedAt, Rows.expenseJson(it, userId, currency))
            }

            SyncedTable.PERSON -> dao.peopleToPush().map {
                Outgoing(it.remoteId, it.updatedAt, Rows.personJson(it, userId))
            }

            SyncedTable.CATEGORY -> dao.categoriesToPush().map {
                Outgoing(it.remoteId, it.updatedAt, Rows.categoryJson(it, userId))
            }

            SyncedTable.SPLIT -> dao.splitsToPush().mapNotNull { split ->
                // A split is worthless without the rows it points at, and a foreign key the server
                // does not recognise would be refused outright and stall every other table with it.
                val expenseRemote = expenseIds[split.expenseId] ?: return@mapNotNull null
                val personRemote = split.personId?.let { personIds[it] ?: return@mapNotNull null }
                Outgoing(split.remoteId, split.updatedAt, Rows.splitJson(split, userId, expenseRemote, personRemote))
            }
        }
        if (pending.isEmpty()) return Response.Ok(0)

        var sent = 0
        // Sent in batches so that one enormous first sync is not one enormous request.
        for (batch in pending.chunked(PAGE)) {
            val body = JSONArray().apply { batch.forEach { put(it.json) } }.toString()
            val before = System.currentTimeMillis()
            val response = link.request(
                method = "POST",
                // Asking for each row's id and stamp back is what lets the row take the server's
                // clock, and lets this device learn how far its own clock is from that one.
                path = "/rest/v1/" + table.remote + "?select=id,updated_at",
                body = body,
                // An upsert: the row may already be there from another device or an earlier run.
                headers = mapOf("Prefer" to "resolution=merge-duplicates,return=representation")
            )
            when (response) {
                is Response.Offline -> return response
                is Response.Rejected -> return response
                is Response.Ok -> {
                    val after = System.currentTimeMillis()
                    val stamps = serverStamps(response.body)
                    // The whole batch is one statement, so every row carries the same now(): the
                    // moment the server wrote it, somewhere between sending and hearing back.
                    stamps.values.firstOrNull()?.let { serverAt ->
                        marks.edit().putLong(KEY_CLOCK_OFFSET, serverAt - (before + after) / 2).apply()
                    }
                    batch.forEach { settle(table, it, stamps[it.remoteId] ?: it.sentAt, after) }
                    sent += batch.size
                }
            }
        }
        return Response.Ok(sent)
    }

    private fun serverStamps(body: String): Map<String, Long> {
        val rows = body.asJsonArray() ?: return emptyMap()
        return rows.map { row ->
            val id = row.stringOrNull("id")
            val at = Rows.fromIso(row.stringOrNull("updated_at"))
            if (id != null && at != null) id to at else null
        }.filterNotNull().toMap()
    }

    /**
     * Marks a sent row as in step with the server, if it is still the version that was sent.
     *
     * A row edited while the request was in flight has to stay unsynced, or that edit would never
     * be sent at all. The row takes the server's stamp for the write, so from here on its clock is
     * the server's, and the echo of this push reads back as the same version rather than a change.
     */
    private suspend fun settle(table: SyncedTable, row: Outgoing, serverAt: Long, at: Long) {
        when (table) {
            SyncedTable.EXPENSE -> dao.expenseByRemoteId(row.remoteId)
                ?.takeIf { it.updatedAt == row.sentAt }
                ?.let { dao.updateExpense(it.copy(updatedAt = serverAt, syncedAt = at)) }

            SyncedTable.PERSON -> dao.personByRemoteId(row.remoteId)
                ?.takeIf { it.updatedAt == row.sentAt }
                ?.let { dao.updatePerson(it.copy(updatedAt = serverAt, syncedAt = at)) }

            SyncedTable.CATEGORY -> dao.categoryByRemoteId(row.remoteId)
                ?.takeIf { it.updatedAt == row.sentAt }
                ?.let { dao.updateCategory(it.copy(updatedAt = serverAt, syncedAt = at)) }

            SyncedTable.SPLIT -> dao.splitByRemoteId(row.remoteId)
                ?.takeIf { it.updatedAt == row.sentAt }
                ?.let { dao.updateSplit(it.copy(updatedAt = serverAt, syncedAt = at)) }
        }
    }

    // --- conflict resolution ---

    /**
     * Settles a conflict the way the user chose, leaving the row in a state the next sync will not
     * undo.
     *
     * Keeping the local version re-stamps the row as a fresh edit and leaves it unsynced, so the
     * next push -- which held it back while the conflict was open -- carries it up and the server
     * takes it. The fresh stamp is what stops the server copy that caused the conflict from reading
     * as newer again should the next pull meet it.
     *
     * Keeping the remote version writes it down as an ordinary pulled row: the server's contents
     * and stamp, marked in step, so nothing is pushed for it and its own echo is not a change.
     */
    suspend fun resolve(conflictId: Long, keepLocal: Boolean): Boolean {
        val conflict = dao.conflict(conflictId) ?: return false
        val table = conflict.table ?: return false
        val local: Synced? = byRemoteId(table, conflict.remoteId)
        val now = System.currentTimeMillis()
        if (keepLocal) {
            when (local) {
                is Expense -> dao.updateExpense(local.copy(updatedAt = now, syncedAt = null))
                is Person -> dao.updatePerson(local.copy(updatedAt = now, syncedAt = null))
                is Category -> dao.updateCategory(local.copy(updatedAt = now, syncedAt = null))
                is ExpenseSplit -> dao.updateSplit(local.copy(updatedAt = now, syncedAt = null))
            }
            dao.clearConflict(conflictId)
            return true
        }
        val row = conflict.remoteJson.asJsonObject() ?: return false
        if (!write(table, row, local, now)) return false
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

        /**
         * How far the server's clock runs ahead of this device's, in millis, as last measured by
         * a push. Server stamps and device stamps are only comparable once one is shifted by it.
         */
        const val KEY_CLOCK_OFFSET = "serverClockOffset"
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
