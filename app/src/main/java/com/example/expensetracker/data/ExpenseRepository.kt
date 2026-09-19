package com.example.expensetracker.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.Flow

class ExpenseRepository(context: Context) {

    // Shared with the home screen widgets rather than opened again here, so that a save made in
    // the app invalidates the widget's query rather than leaving it on a stale total.
    private val db = AppDatabase.get(context)

    private val expenseDao = db.expenseDao()
    private val categoryDao = db.categoryDao()
    private val personDao = db.personDao()
    private val attachments = AttachmentStore(context)

    /** Exposed so the sync engine can be built over the same database this repository owns. */
    val syncDao: SyncDao = db.syncDao()

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
     */
    suspend fun save(
        expense: Expense,
        shares: List<ExpenseSplit>,
        newAttachments: List<Uri>,
        removedAttachmentIds: List<Long> = emptyList()
    ): Long {
        val now = System.currentTimeMillis()
        val id = if (expense.id == 0L) {
            expenseDao.insert(expense)
        } else {
            // The editor builds a fresh Expense on every save, which would mint a fresh remoteId
            // and orphan the row the server already holds. The stored identity survives an edit.
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

        expenseDao.clearSplits(id)
        val owned = shares.filter { it.amountPaise > 0 }.map { it.copy(expenseId = id, updatedAt = now) }
        if (owned.isNotEmpty()) expenseDao.insertSplits(owned)

        if (removedAttachmentIds.isNotEmpty()) {
            val held = expenseDao.attachmentsFor(id)
            attachments.delete(held.filter { it.id in removedAttachmentIds }.map { it.path })
            expenseDao.deleteAttachments(removedAttachmentIds)
        }

        val copied = newAttachments.mapNotNull { uri ->
            attachments.copyIn(uri)?.let { Attachment(expenseId = id, path = it, addedAt = now) }
        }
        if (copied.isNotEmpty()) expenseDao.insertAttachments(copied)
        return id
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

    suspend fun renameCategory(category: Category, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == category.name) return
        val now = stamp()
        categoryDao.update(category.copy(name = trimmed, updatedAt = now, syncedAt = null))
        categoryDao.renameOnExpenses(category.name, trimmed, now)
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

    suspend fun renamePerson(person: Person, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isNotEmpty() && trimmed != person.name) {
            personDao.update(person.copy(name = trimmed, updatedAt = stamp(), syncedAt = null))
        }
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

    private companion object {
        /** Same amount to the same payee inside a day reads as a re-entry rather than a repeat buy. */
        const val DUPLICATE_WINDOW_MILLIS = 24L * 60 * 60 * 1000
    }
}
