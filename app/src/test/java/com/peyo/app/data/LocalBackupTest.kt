package com.peyo.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * A backup is only worth having if it can be read back, and the way it stops being readable is by
 * losing the links between the rows -- a split pointing at a local row number that means something
 * else on the phone it lands on. So these tests run the file through a second, independent
 * database rather than checking the JSON it contains.
 */
class LocalBackupTest {

    @Test
    fun `a backup taken on one phone restores onto an empty one`() = runBlocking {
        val source = FakeSyncDao()
        val alice = source.putPerson(Person(name = "Alice"))
        val food = source.putCategory(Category(name = "Food"))
        val lunch = source.putExpense(
            Expense(amountPaise = 45_000, category = "Food", note = "team lunch", merchant = "Cafe", paidAt = 1_000)
        )
        source.putSplit(ExpenseSplit(expenseId = lunch.id, personId = alice.id, amountPaise = 15_000))
        source.putSplit(ExpenseSplit(expenseId = lunch.id, personId = null, amountPaise = 30_000))

        val file = ByteArrayOutputStream()
        assertEquals(1, LocalBackup.export(source, file))

        val fresh = FakeSyncDao()
        val result = LocalBackup.merge(fresh, ByteArrayInputStream(file.toByteArray()))

        assertEquals(1, result.expenses)
        assertEquals(1, result.people)
        assertEquals(1, result.categories)
        assertEquals(2, result.splits)

        val restored = fresh.expenses.single()
        assertEquals(45_000, restored.amountPaise)
        assertEquals("team lunch", restored.note)
        assertEquals(lunch.remoteId, restored.remoteId)

        // The point of the exercise: the splits found the expense and the person again, under
        // whatever row numbers this database happened to hand out.
        assertTrue(fresh.splits.all { it.expenseId == restored.id })
        assertEquals(
            setOf(fresh.people.single().id, null),
            fresh.splits.map { it.personId }.toSet()
        )
        assertEquals(food.remoteId, fresh.categories.single().remoteId)
    }

    @Test
    fun `restored rows are unsynced, so an account picks them up`() = runBlocking {
        val source = FakeSyncDao()
        source.putExpense(Expense(amountPaise = 100, category = "Other", note = "", merchant = "", paidAt = 1, syncedAt = 99))

        val file = ByteArrayOutputStream()
        LocalBackup.export(source, file)
        val fresh = FakeSyncDao()
        LocalBackup.merge(fresh, ByteArrayInputStream(file.toByteArray()))

        // A row that came out of a file has not reached the server, whatever the phone it was
        // taken on believed.
        assertNull(fresh.expenses.single().syncedAt)
    }

    @Test
    fun `importing twice does not double anything up`() = runBlocking {
        val source = FakeSyncDao()
        source.putPerson(Person(name = "Alice"))
        source.putExpense(Expense(amountPaise = 100, category = "Other", note = "", merchant = "", paidAt = 1))

        val file = ByteArrayOutputStream()
        LocalBackup.export(source, file)
        val bytes = file.toByteArray()

        val target = FakeSyncDao()
        LocalBackup.merge(target, ByteArrayInputStream(bytes))
        val again = LocalBackup.merge(target, ByteArrayInputStream(bytes))

        assertEquals(0, again.total)
        assertEquals(1, target.expenses.size)
        assertEquals(1, target.people.size)
    }

    @Test
    fun `a person already here under the same name is matched rather than duplicated`() = runBlocking {
        val source = FakeSyncDao()
        source.putPerson(Person(name = "Alice", note = "from the backup", updatedAt = 2_000))

        val file = ByteArrayOutputStream()
        LocalBackup.export(source, file)

        // A different phone that happened to type the same name, so a different remote id.
        val target = FakeSyncDao()
        target.putPerson(Person(name = "Alice", note = "typed here", updatedAt = 1_000))

        LocalBackup.merge(target, ByteArrayInputStream(file.toByteArray()))

        // One Alice, not two, and the newer of the two versions of her.
        assertEquals(1, target.people.size)
        assertEquals("from the backup", target.people.single().note)
    }

    @Test
    fun `what is already here and newer survives the import`() = runBlocking {
        val source = FakeSyncDao()
        val stale = source.putExpense(
            Expense(amountPaise = 100, category = "Other", note = "old", merchant = "", paidAt = 1, updatedAt = 1_000)
        )
        val file = ByteArrayOutputStream()
        LocalBackup.export(source, file)

        val target = FakeSyncDao()
        target.putExpense(stale.copy(id = 0, note = "edited since", updatedAt = 5_000))

        val result = LocalBackup.merge(target, ByteArrayInputStream(file.toByteArray()))

        assertEquals("edited since", target.expenses.single().note)
        assertEquals(1, result.skipped)
    }

    @Test
    fun `deleted rows travel as deletions rather than being dropped`() = runBlocking {
        val source = FakeSyncDao()
        source.putExpense(
            Expense(amountPaise = 100, category = "Other", note = "", merchant = "", paidAt = 1, deletedAt = 7_000)
        )

        val file = ByteArrayOutputStream()
        // The count reported to the user is of live transactions, not of rows written.
        assertEquals(0, LocalBackup.export(source, file))

        val fresh = FakeSyncDao()
        LocalBackup.merge(fresh, ByteArrayInputStream(file.toByteArray()))
        // Importing a backup must not resurrect what the user deleted before taking it.
        assertEquals(7_000L, fresh.expenses.single().deletedAt)
    }

    @Test(expected = LocalBackup.NotABackup::class)
    fun `something that is not a backup is refused by name`(): Unit = runBlocking {
        LocalBackup.merge(FakeSyncDao(), ByteArrayInputStream("""{"hello":"world"}""".toByteArray()))
    }

    @Test(expected = LocalBackup.NotABackup::class)
    fun `an unreadable file is refused rather than half applied`(): Unit = runBlocking {
        LocalBackup.merge(FakeSyncDao(), ByteArrayInputStream("not json at all".toByteArray()))
    }

    @Test
    fun `a split whose expense is missing is counted rather than orphaned`() = runBlocking {
        val source = FakeSyncDao()
        val alice = source.putPerson(Person(name = "Alice"))
        val expense = source.putExpense(
            Expense(amountPaise = 100, category = "Other", note = "", merchant = "", paidAt = 1)
        )
        source.putSplit(ExpenseSplit(expenseId = expense.id, personId = alice.id, amountPaise = 100))

        val file = ByteArrayOutputStream()
        LocalBackup.export(source, file)
        // A file edited by hand, or written by a version that recorded the expense differently:
        // the split now names an expense that is nowhere in the file.
        val text = String(file.toByteArray())
            .replace(Regex("(\"expenseRemoteId\"\\s*:\\s*\")[^\"]+"), "$1missing-expense-id")

        val fresh = FakeSyncDao()
        val result = LocalBackup.merge(fresh, ByteArrayInputStream(text.toByteArray()))

        assertTrue(fresh.splits.isEmpty())
        assertEquals(1, result.skipped)
        // The rest of the file still lands: one unplaceable row does not cost the user the others.
        assertNotNull(fresh.people.singleOrNull())
    }
}

/**
 * Room, minus the database.
 *
 * Only the handful of calls the backup makes are implemented with any care; the rest are here
 * because the interface has them. Ids are handed out the way SQLite hands them out, one higher
 * each time, because a backup that only works when both phones number their rows identically is
 * exactly the bug these tests are looking for.
 */
private class FakeSyncDao : SyncDao {

    val expenses = mutableListOf<Expense>()
    val people = mutableListOf<Person>()
    val categories = mutableListOf<Category>()
    val splits = mutableListOf<ExpenseSplit>()

    private var nextId = 1L
    private fun id() = nextId++

    fun putExpense(expense: Expense): Expense =
        expense.copy(id = id()).also { expenses += it }

    fun putPerson(person: Person): Person =
        person.copy(id = id()).also { people += it }

    fun putCategory(category: Category): Category =
        category.copy(id = id()).also { categories += it }

    fun putSplit(split: ExpenseSplit): ExpenseSplit =
        split.copy(id = id()).also { splits += it }

    override suspend fun allExpenses() = expenses.toList()
    override suspend fun allPeople() = people.toList()
    override suspend fun allCategories() = categories.toList()
    override suspend fun allSplits() = splits.toList()

    override suspend fun expenseByRemoteId(remoteId: String) = expenses.firstOrNull { it.remoteId == remoteId }
    override suspend fun personByRemoteId(remoteId: String) = people.firstOrNull { it.remoteId == remoteId }
    override suspend fun categoryByRemoteId(remoteId: String) = categories.firstOrNull { it.remoteId == remoteId }
    override suspend fun splitByRemoteId(remoteId: String) = splits.firstOrNull { it.remoteId == remoteId }

    override suspend fun categoryByName(name: String) = categories.firstOrNull { it.name == name }
    override suspend fun personByName(name: String) = people.firstOrNull { it.name == name }

    override suspend fun insertExpense(expense: Expense) = putExpense(expense).id
    override suspend fun insertPerson(person: Person) = putPerson(person).id
    override suspend fun insertCategory(category: Category) = putCategory(category).id
    override suspend fun insertSplit(split: ExpenseSplit) = putSplit(split).id

    override suspend fun updateExpense(expense: Expense) { expenses.replace(expense.id, expense) { it.id } }
    override suspend fun updatePerson(person: Person) { people.replace(person.id, person) { it.id } }
    override suspend fun updateCategory(category: Category) { categories.replace(category.id, category) { it.id } }
    override suspend fun updateSplit(split: ExpenseSplit) { splits.replace(split.id, split) { it.id } }

    override suspend fun expensesToPush() = expenses.filter { it.syncedAt == null }
    override suspend fun peopleToPush() = people.filter { it.syncedAt == null }
    override suspend fun categoriesToPush() = categories.filter { it.syncedAt == null }
    override suspend fun splitsToPush() = splits.filter { it.syncedAt == null }

    override suspend fun expenseIds() = expenses.map { IdPair(it.id, it.remoteId) }
    override suspend fun personIds() = people.map { IdPair(it.id, it.remoteId) }

    override fun observePendingExpenses(): Flow<Int> = flowOf(expenses.count { it.syncedAt == null })
    override fun observePendingCount(): Flow<Int> = flowOf(0)

    override suspend fun markExpensesSynced(ids: List<Long>, at: Long) = Unit
    override suspend fun markPeopleSynced(ids: List<Long>, at: Long) = Unit
    override suspend fun markCategoriesSynced(ids: List<Long>, at: Long) = Unit
    override suspend fun markSplitsSynced(ids: List<Long>, at: Long) = Unit

    override suspend fun markAllExpensesUnsynced() = Unit
    override suspend fun markAllPeopleUnsynced() = Unit
    override suspend fun markAllCategoriesUnsynced() = Unit
    override suspend fun markAllSplitsUnsynced() = Unit

    override suspend fun recordConflict(conflict: SyncConflict) = Unit
    override fun observeConflicts(): Flow<List<SyncConflict>> = flowOf(emptyList())
    override fun observeConflictCount(): Flow<Int> = flowOf(0)
    override suspend fun conflict(id: Long): SyncConflict? = null
    override suspend fun clearConflict(id: Long) = Unit
    override suspend fun clearAllConflicts() = Unit
}

private fun <T> MutableList<T>.replace(id: Long, value: T, key: (T) -> Long) {
    val index = indexOfFirst { key(it) == id }
    if (index >= 0) set(index, value) else add(value)
}
