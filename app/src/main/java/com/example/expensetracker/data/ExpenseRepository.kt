package com.example.expensetracker.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import kotlinx.coroutines.flow.Flow

class ExpenseRepository(context: Context) {

    private val db = Room.databaseBuilder(context, AppDatabase::class.java, "spendwise.db")
        // No migrations are written yet, so bump AppDatabase.version and add one before shipping a
        // schema change to anyone whose history matters -- this recreates the database instead.
        .fallbackToDestructiveMigration()
        .addCallback(AppDatabase.seedCategories)
        .build()

    private val expenseDao = db.expenseDao()
    private val categoryDao = db.categoryDao()
    private val personDao = db.personDao()
    private val attachments = AttachmentStore(context)

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
        val id = if (expense.id == 0L) {
            expenseDao.insert(expense)
        } else {
            expenseDao.update(expense)
            expense.id
        }

        expenseDao.clearSplits(id)
        val owned = shares.filter { it.amountPaise > 0 }.map { it.copy(expenseId = id) }
        if (owned.isNotEmpty()) expenseDao.insertSplits(owned)

        if (removedAttachmentIds.isNotEmpty()) {
            val existing = expenseDao.attachmentsFor(id)
            attachments.delete(existing.filter { it.id in removedAttachmentIds }.map { it.path })
            expenseDao.deleteAttachments(removedAttachmentIds)
        }

        val copied = newAttachments.mapNotNull { uri ->
            attachments.copyIn(uri)?.let { Attachment(expenseId = id, path = it, addedAt = System.currentTimeMillis()) }
        }
        if (copied.isNotEmpty()) expenseDao.insertAttachments(copied)
        return id
    }

    suspend fun delete(expense: Expense) {
        // Room cascades the rows; the image files need removing by hand.
        attachments.delete(expenseDao.attachmentsFor(expense.id).map { it.path })
        expenseDao.delete(expense)
    }

    suspend fun settleShare(splitId: Long) = expenseDao.settleShare(splitId, System.currentTimeMillis())
    suspend fun settleEverything(personId: Long) = expenseDao.settleEverything(personId, System.currentTimeMillis())
    suspend fun reopenEverything(personId: Long) = expenseDao.reopenEverything(personId)

    suspend fun addCategory(name: String) =
        categoryDao.insert(Category(name = name.trim(), sortOrder = Int.MAX_VALUE))

    suspend fun renameCategory(category: Category, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == category.name) return
        categoryDao.update(category.copy(name = trimmed))
        categoryDao.renameOnExpenses(category.name, trimmed)
    }

    suspend fun deleteCategory(category: Category) = categoryDao.delete(category)
    suspend fun categoryUsage(name: String) = categoryDao.expenseCount(name)

    suspend fun addPerson(name: String) = personDao.insert(Person(name = name.trim()))
    suspend fun renamePerson(person: Person, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isNotEmpty() && trimmed != person.name) personDao.update(person.copy(name = trimmed))
    }

    suspend fun deletePerson(person: Person) = personDao.delete(person)
    suspend fun personOutstandingCount(id: Long) = personDao.outstandingCount(id)

    private companion object {
        /** Same amount to the same payee inside a day reads as a re-entry rather than a repeat buy. */
        const val DUPLICATE_WINDOW_MILLIS = 24L * 60 * 60 * 1000
    }
}
