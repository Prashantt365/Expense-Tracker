package com.peyo.app.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

class ExpenseRepository(
    context: Context,
    // Shared with the home screen widgets rather than opened again here, so that a save made in
    // the app invalidates the widget's query rather than leaving it on a stale total. A parameter
    // only so that a test can hand in an in-memory database instead.
    private val db: AppDatabase = AppDatabase.get(context)
) {

    private val expenseDao = db.expenseDao()
    private val categoryDao = db.categoryDao()
    private val personDao = db.personDao()
    private val attachments = AttachmentStore(context)

    /** Exposed so the sync engine can be built over the same database this repository owns. */
    val syncDao: SyncDao = db.syncDao()

    /** For work that goes through [syncDao] but has to land all at once or not at all. */
    suspend fun <R> transaction(block: suspend () -> R): R = db.withTransaction { block() }

    val conflicts: Flow<List<SyncConflict>> = syncDao.observeConflicts()
    /** Rows written here that the server has not taken yet, which is what auto backup watches. */
    val pendingUpload: Flow<Int> = syncDao.observePendingCount()

    val expenses: Flow<List<ExpenseDetails>> = expenseDao.observeAll()
    val categories: Flow<List<Category>> = categoryDao.observeAll()
    val people: Flow<List<Person>> = personDao.observeAll()
    val balances: Flow<List<PersonBalance>> = expenseDao.observeBalances()

    fun outstandingFor(personId: Long): Flow<List<OutstandingShare>> = expenseDao.observeOutstanding(personId)

    /** Returns the expense this one would duplicate, or null. */
    suspend fun findDuplicate(expense: Expense): Expense? {
        expense.sourceUri?.let { source ->
            expenseDao.findBySource(source, expense.id)?.let { return it }
        }
        if (expense.merchant.isBlank()) return null
        return expenseDao.findSimilar(
            amountPaise = expense.amountPaise,
            merchant = expense.merchant,
            paidAt = expense.paidAt,
            windowMillis = DUPLICATE_WINDOW_MILLIS,
            ignoreId = expense.id
        )
    }

    /**
     * Writes [expense] with its splits and attachments. [newAttachments] are copied into private
     * storage first; [removedAttachmentIds] are deleted from disk as well as from the table.
     *
     * The database writes happen in one transaction, so a failure partway leaves the expense as it
     * was rather than saved with no shares. Files are copied in before it and deleted after it,
     * because neither can be rolled back: a copy that is never referenced is only wasted space,
     * whereas a screenshot deleted for a save that then failed would be gone for good.
     */
    suspend fun save(
        expense: Expense,
        shares: List<ExpenseSplit>,
        newAttachments: List<Uri>,
        removedAttachmentIds: List<Long> = emptyList()
    ): Long {
        val now = System.currentTimeMillis()
        val copied = newAttachments.mapNotNull { attachments.copyIn(it) }
        var removedPaths = emptyList<String>()

        val id = db.withTransaction {
            val id = if (expense.id == 0L) {
                expenseDao.insert(expense)
            } else {
                // The editor builds a fresh Expense on every save, which would mint a fresh
                // remoteId and orphan the row the server already holds. The stored identity
                // survives an edit.
                val stored = expenseDao.byId(expense.id)
                expenseDao.update(
                    expense.copy(
                        remoteId = stored?.remoteId ?: expense.remoteId,
                        updatedAt = now,
                        syncedAt = null
                    )
                )
                expense.id
            }

            reconcileSplits(id, shares.filter { it.amountPaise > 0 }, now)

            if (removedAttachmentIds.isNotEmpty()) {
                removedPaths = expenseDao.attachmentsFor(id)
                    .filter { it.id in removedAttachmentIds }.map { it.path }
                expenseDao.deleteAttachments(removedAttachmentIds)
            }
            if (copied.isNotEmpty()) {
                expenseDao.insertAttachments(copied.map { Attachment(expenseId = id, path = it, addedAt = now) })
            }
            id
        }

        attachments.delete(removedPaths)
        return id
    }

    /**
     * Brings the stored shares of expense [id] in line with [wanted], one person at a time.
     *
     * A share whose person is still on the bill keeps its row, so it keeps its remoteId and, if
     * the amount is unchanged, whether it was settled -- correcting the note on a bill somebody
     * has already paid back must not put their debt back. A changed amount reopens the share,
     * because what was settled is no longer what is owed. A person taken off the bill leaves a
     * tombstone, which is what tells the server and every other phone that the share has gone.
     */
    private suspend fun reconcileSplits(id: Long, wanted: List<ExpenseSplit>, now: Long) {
        val stored = expenseDao.liveSplitsFor(id).groupBy { it.personId }.toMutableMap()
        val changed = mutableListOf<ExpenseSplit>()
        val added = mutableListOf<ExpenseSplit>()

        wanted.forEach { share ->
            val existing = stored[share.personId]?.firstOrNull()
            if (existing == null) {
                added += share.copy(expenseId = id, updatedAt = now)
                return@forEach
            }
            stored[share.personId] = stored.getValue(share.personId).drop(1)
            if (existing.amountPaise != share.amountPaise) {
                changed += existing.copy(
                    amountPaise = share.amountPaise,
                    settledAt = null,
                    updatedAt = now,
                    syncedAt = null
                )
            }
        }
        // Whatever was not claimed above is no longer on the bill, including any second row one
        // person had picked up under the old delete-and-reinsert save.
        stored.values.flatten().forEach {
            changed += it.copy(deletedAt = now, updatedAt = now, syncedAt = null)
        }

        if (changed.isNotEmpty()) expenseDao.updateSplits(changed)
        if (added.isNotEmpty()) expenseDao.insertSplits(added)
    }

    /**
     * The row is marked deleted rather than removed, so that the deletion can reach other devices.
     * The screenshots go for good either way: they never leave this phone, so nothing is waiting
     * on them and keeping them would only hold on to the bulkiest part of a deleted expense.
     */
    suspend fun delete(expense: Expense) {
        val held = expenseDao.attachmentsFor(expense.id)
        attachments.delete(held.map { it.path })
        expenseDao.deleteAttachments(held.map { it.id })
        expenseDao.tombstone(expense.id, System.currentTimeMillis())
    }

    suspend fun settleShare(splitId: Long) = expenseDao.settleShare(splitId, System.currentTimeMillis())
    suspend fun settleEverything(personId: Long) = expenseDao.settleEverything(personId, System.currentTimeMillis())
    suspend fun reopenEverything(personId: Long) = expenseDao.reopenEverything(personId, System.currentTimeMillis())

    /**
     * A deleted name still occupies the unique index, so adding it back has to revive that row.
     * Inserting beside it would be ignored on conflict and the add would silently do nothing.
     */
    suspend fun addCategory(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val buried = categoryDao.deletedByName(trimmed)
        if (buried != null) {
            categoryDao.update(buried.copy(deletedAt = null, updatedAt = stamp(), syncedAt = null))
            return
        }
        categoryDao.insert(Category(name = trimmed, sortOrder = Int.MAX_VALUE))
    }

    /**
     * Returns false, and changes nothing, when another category already has that name.
     *
     * A deleted category holding the name is moved out of the way instead: it is invisible, so
     * the user cannot know it is there, and the unique index would otherwise refuse the rename
     * with an exception rather than an answer.
     */
    suspend fun renameCategory(category: Category, newName: String): Boolean {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == category.name) return true
        val holder = categoryDao.activeByName(trimmed)
        if (holder != null && holder.id != category.id) return false
        val now = stamp()
        db.withTransaction {
            categoryDao.deletedByName(trimmed)?.let {
                categoryDao.update(it.copy(name = buriedName(it.name, it.remoteId), updatedAt = now, syncedAt = null))
            }
            categoryDao.update(category.copy(name = trimmed, updatedAt = now, syncedAt = null))
            categoryDao.renameOnExpenses(category.name, trimmed, now)
        }
        return true
    }

    suspend fun deleteCategory(category: Category) = categoryDao.tombstone(category.id, stamp())

    suspend fun categoryUsage(name: String) = categoryDao.expenseCount(name)

    suspend fun addPerson(name: String): Long {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return 0L
        val active = personDao.activeByName(trimmed)
        if (active != null) return active.id
        val buried = personDao.deletedByName(trimmed)
        if (buried != null) {
            personDao.update(buried.copy(deletedAt = null, updatedAt = stamp(), syncedAt = null))
            return buried.id
        }
        return personDao.insert(Person(name = trimmed))
    }

    /** Returns false, and changes nothing, when somebody else already has that name. See [renameCategory]. */
    suspend fun renamePerson(person: Person, newName: String): Boolean {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == person.name) return true
        val holder = personDao.activeByName(trimmed)
        if (holder != null && holder.id != person.id) return false
        val now = stamp()
        db.withTransaction {
            personDao.deletedByName(trimmed)?.let {
                personDao.update(it.copy(name = buriedName(it.name, it.remoteId), updatedAt = now, syncedAt = null))
            }
            personDao.update(person.copy(name = trimmed, updatedAt = now, syncedAt = null))
        }
        return true
    }

    suspend fun deletePerson(person: Person) = personDao.tombstone(person.id, stamp())
    suspend fun personOutstandingCount(id: Long) = personDao.outstandingCount(id)

    /** Bulk import from a statement. Returns how many rows were actually written. */
    suspend fun importExpenses(expenses: List<Expense>, skipDuplicates: Boolean = true): Int {
        var written = 0
        expenses.forEach { expense ->
            if (skipDuplicates && findDuplicate(expense) != null) return@forEach
            val id = expenseDao.insert(expense)
            // An imported row is entirely mine until it is edited and split.
            expenseDao.insertSplits(
                listOf(ExpenseSplit(expenseId = id, personId = null, amountPaise = expense.amountPaise))
            )
            written++
        }
        return written
    }

    suspend fun addPeople(names: List<String>): Int {
        var added = 0
        names.forEach { name ->
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return@forEach
            val buried = personDao.deletedByName(trimmed)
            if (buried != null) {
                personDao.update(buried.copy(deletedAt = null, updatedAt = stamp(), syncedAt = null))
                added++
            } else if (personDao.insert(Person(name = trimmed)) != -1L) {
                // insert ignores on conflict, so a -1 means the name was already taken.
                added++
            }
        }
        return added
    }

    private fun stamp() = System.currentTimeMillis()

    /** A name nobody will type, unique per row, for a tombstone that has to give its name up. */
    private fun buriedName(name: String, remoteId: String) = "$name ~deleted ${remoteId.take(8)}"

    private companion object {
        /** Same amount to the same payee inside a day reads as a re-entry rather than a repeat buy. */
        const val DUPLICATE_WINDOW_MILLIS = 24L * 60 * 60 * 1000
    }
}
