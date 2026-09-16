package com.example.expensetracker.data

import android.content.Context
import android.net.Uri
import com.example.expensetracker.AppCurrency
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * The whole local database as one file, and back again.
 *
 * This is the backup that needs nothing but the phone. An account keeps the history safe from a
 * lost phone; a file keeps it safe from a lost account, from a project whose free tier lapses, and
 * from the user simply wanting their own data somewhere they can see it. The two are not
 * alternatives, which is why both are offered.
 *
 * Attachments are deliberately absent, for the same reason they never reach the server: the
 * screenshots are the bulky part, and a backup that carried them would be too large to mail to
 * yourself. Everything else -- including tombstones, so that importing a backup does not resurrect
 * what has since been deleted -- is here.
 *
 * Rows are written under their remote ids rather than their local row numbers. A local id means
 * nothing outside the database it came from, so a file keyed on one could not be imported into a
 * phone that had ever recorded anything of its own.
 */
object LocalBackup {

    // Written into the file itself and checked on import, so it keeps the name it was first
    // published under -- renaming it would make every backup taken so far unreadable.
    const val FORMAT = "spendwise-backup"
    const val VERSION = 1
    const val MIME = "application/json"

    /** What an import came to, so the user is told something specific rather than "done". */
    data class Imported(
        val expenses: Int = 0,
        val people: Int = 0,
        val categories: Int = 0,
        val splits: Int = 0,
        val skipped: Int = 0,
        /** The currency the file was taken under, for a phone that has not chosen one yet. */
        val currency: String? = null
    ) {
        val total: Int get() = expenses + people + categories + splits
    }

    /** A file that is not one of ours, or is one we cannot read. */
    class NotABackup(message: String) : Exception(message)

    fun suggestedFileName(now: Long = System.currentTimeMillis()): String {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(now))
        return "peyo-backup-$stamp.json"
    }

    // --- writing ---

    suspend fun export(dao: SyncDao, out: OutputStream): Int {
        val expenses = dao.allExpenses()
        val people = dao.allPeople()
        val categories = dao.allCategories()
        val splits = dao.allSplits()

        // Splits point at their expense and person by remote id, so the numbering on the phone the
        // file came from never has to mean anything on the phone it goes to.
        val expenseRemote = expenses.associate { it.id to it.remoteId }
        val personRemote = people.associate { it.id to it.remoteId }

        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exportedAt", System.currentTimeMillis())
            .put("currency", AppCurrency.code)
            .put("categories", JSONArray().apply { categories.forEach { put(it.toJson()) } })
            .put("people", JSONArray().apply { people.forEach { put(it.toJson()) } })
            .put("expenses", JSONArray().apply { expenses.forEach { put(it.toJson()) } })
            .put("splits", JSONArray().apply {
                splits.forEach { split ->
                    // A split whose expense is not in the file would import as an orphan, and a
                    // share owed to a person who is not there cannot be shown to anybody.
                    val expense = expenseRemote[split.expenseId] ?: return@forEach
                    val person = split.personId?.let { personRemote[it] ?: return@forEach }
                    put(split.toJson(expense, person))
                }
            })

        out.bufferedWriter(StandardCharsets.UTF_8).use { it.write(root.toString(2)) }
        return expenses.count { it.deletedAt == null }
    }

    // --- reading ---

    /**
     * Merges a backup file into the database.
     *
     * A merge rather than a replace, on purpose. Importing is something people do when they have
     * already started using the app again, and a restore that wiped what they had typed since
     * would be the surprise this feature exists to avoid. Each row is matched by its remote id --
     * and, for a person or a category, by its name as well, since those are unique -- and the
     * newer of the two versions wins.
     */
    suspend fun merge(dao: SyncDao, input: InputStream): Imported {
        val text = input.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: throw NotABackup("That file is not readable as a Peyo backup.")
        if (root.optString("format") != FORMAT) {
            throw NotABackup("That file was not written by Peyo.")
        }
        if (root.optInt("version", 0) > VERSION) {
            throw NotABackup("That backup was written by a newer version of Peyo.")
        }

        var imported = Imported()

        root.optJSONArray("categories")?.forEachObject { row ->
            val remoteId = row.optString("remoteId").ifBlank { return@forEachObject }
            val name = row.optString("name").ifBlank { return@forEachObject }
            val incoming = Category(
                name = name,
                sortOrder = row.optInt("sortOrder"),
                remoteId = remoteId,
                updatedAt = row.optLong("updatedAt"),
                deletedAt = row.longOrNull("deletedAt"),
                // Never marked as synced: a row that arrived from a file has not reached the
                // server, and saying otherwise would keep it off the next push for good.
                syncedAt = null
            )
            val held = dao.categoryByRemoteId(remoteId) ?: dao.categoryByName(name)
            when {
                held == null -> { dao.insertCategory(incoming); imported = imported.copy(categories = imported.categories + 1) }
                incoming.updatedAt > held.updatedAt -> {
                    dao.updateCategory(incoming.copy(id = held.id))
                    imported = imported.copy(categories = imported.categories + 1)
                }
                else -> imported = imported.copy(skipped = imported.skipped + 1)
            }
        }

        root.optJSONArray("people")?.forEachObject { row ->
            val remoteId = row.optString("remoteId").ifBlank { return@forEachObject }
            val name = row.optString("name").ifBlank { return@forEachObject }
            val incoming = Person(
                name = name,
                note = row.optString("note"),
                remoteId = remoteId,
                updatedAt = row.optLong("updatedAt"),
                deletedAt = row.longOrNull("deletedAt"),
                syncedAt = null
            )
            val held = dao.personByRemoteId(remoteId) ?: dao.personByName(name)
            when {
                held == null -> { dao.insertPerson(incoming); imported = imported.copy(people = imported.people + 1) }
                incoming.updatedAt > held.updatedAt -> {
                    dao.updatePerson(incoming.copy(id = held.id))
                    imported = imported.copy(people = imported.people + 1)
                }
                else -> imported = imported.copy(skipped = imported.skipped + 1)
            }
        }

        root.optJSONArray("expenses")?.forEachObject { row ->
            val remoteId = row.optString("remoteId").ifBlank { return@forEachObject }
            val incoming = Expense(
                amountPaise = row.optLong("amountPaise"),
                category = row.optString("category"),
                note = row.optString("note"),
                merchant = row.optString("merchant"),
                paidAt = row.optLong("paidAt"),
                // The screenshot itself never left the phone the backup came from, so the Uri that
                // pointed at it would be a promise this phone cannot keep.
                sourceUri = null,
                remoteId = remoteId,
                updatedAt = row.optLong("updatedAt"),
                deletedAt = row.longOrNull("deletedAt"),
                syncedAt = null
            )
            val held = dao.expenseByRemoteId(remoteId)
            when {
                held == null -> { dao.insertExpense(incoming); imported = imported.copy(expenses = imported.expenses + 1) }
                incoming.updatedAt > held.updatedAt -> {
                    dao.updateExpense(incoming.copy(id = held.id, sourceUri = held.sourceUri))
                    imported = imported.copy(expenses = imported.expenses + 1)
                }
                else -> imported = imported.copy(skipped = imported.skipped + 1)
            }
        }

        root.optJSONArray("splits")?.forEachObject { row ->
            val remoteId = row.optString("remoteId").ifBlank { return@forEachObject }
            val expenseRemote = row.optString("expenseRemoteId").ifBlank { return@forEachObject }
            val expenseId = dao.expenseByRemoteId(expenseRemote)?.id ?: run {
                imported = imported.copy(skipped = imported.skipped + 1); return@forEachObject
            }
            val personRemote = row.stringOrNull("personRemoteId")
            val personId = if (personRemote == null) null else {
                dao.personByRemoteId(personRemote)?.id ?: run {
                    imported = imported.copy(skipped = imported.skipped + 1); return@forEachObject
                }
            }
            val incoming = ExpenseSplit(
                expenseId = expenseId,
                personId = personId,
                amountPaise = row.optLong("amountPaise"),
                settledAt = row.longOrNull("settledAt"),
                remoteId = remoteId,
                updatedAt = row.optLong("updatedAt"),
                deletedAt = row.longOrNull("deletedAt"),
                syncedAt = null
            )
            val held = dao.splitByRemoteId(remoteId)
            when {
                held == null -> { dao.insertSplit(incoming); imported = imported.copy(splits = imported.splits + 1) }
                incoming.updatedAt > held.updatedAt -> {
                    dao.updateSplit(incoming.copy(id = held.id))
                    imported = imported.copy(splits = imported.splits + 1)
                }
                else -> imported = imported.copy(skipped = imported.skipped + 1)
            }
        }

        // The currency the backup was taken under is reported rather than applied: only the
        // screen knows whether this user has already chosen one, and relabelling amounts somebody
        // already trusts is not something an import gets to do on its own.
        return imported.copy(currency = root.stringOrNull("currency"))
    }

    // --- row shapes ---

    private fun Expense.toJson() = JSONObject()
        .put("remoteId", remoteId)
        .put("amountPaise", amountPaise)
        .put("category", category)
        .put("note", note)
        .put("merchant", merchant)
        .put("paidAt", paidAt)
        .put("updatedAt", updatedAt)
        .putOrNull("deletedAt", deletedAt)

    private fun Person.toJson() = JSONObject()
        .put("remoteId", remoteId)
        .put("name", name)
        .put("note", note)
        .put("updatedAt", updatedAt)
        .putOrNull("deletedAt", deletedAt)

    private fun Category.toJson() = JSONObject()
        .put("remoteId", remoteId)
        .put("name", name)
        .put("sortOrder", sortOrder)
        .put("updatedAt", updatedAt)
        .putOrNull("deletedAt", deletedAt)

    private fun ExpenseSplit.toJson(expenseRemoteId: String, personRemoteId: String?) = JSONObject()
        .put("remoteId", remoteId)
        .put("expenseRemoteId", expenseRemoteId)
        .putOrNull("personRemoteId", personRemoteId)
        .put("amountPaise", amountPaise)
        .putOrNull("settledAt", settledAt)
        .put("updatedAt", updatedAt)
        .putOrNull("deletedAt", deletedAt)

    private fun JSONObject.putOrNull(key: String, value: Any?): JSONObject =
        put(key, value ?: JSONObject.NULL)

    private fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else optLong(key)

    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private inline fun JSONArray.forEachObject(action: (JSONObject) -> Unit) {
        for (index in 0 until length()) {
            val row = optJSONObject(index) ?: continue
            action(row)
        }
    }
}

/** Opening the file the user picked, which is a Context job rather than a database one. */
class BackupFiles(private val context: Context) {
    fun writeTo(uri: Uri): OutputStream? =
        context.contentResolver.openOutputStream(uri, "wt")

    fun readFrom(uri: Uri): InputStream? = context.contentResolver.openInputStream(uri)
}
