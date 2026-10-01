package com.peyo.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExpenseDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ExpenseDao
    private lateinit var people: PersonDao

    private val now = System.currentTimeMillis()

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.expenseDao()
        people = db.personDao()
    }

    @After fun close() = db.close()

    private fun expense(
        amountPaise: Long = 50000,
        merchant: String = "Blue Tokai",
        paidAt: Long = now,
        sourceUri: String? = null
    ) = Expense(
        amountPaise = amountPaise,
        category = "Food",
        note = "",
        merchant = merchant,
        paidAt = paidAt,
        sourceUri = sourceUri
    )

    @Test fun sharingTheSameScreenshotTwiceIsADuplicate() = runBlocking {
        dao.insert(expense(sourceUri = "content://media/42"))
        assertNotNull(dao.findBySource("content://media/42", ignoreId = 0))
        assertNull(dao.findBySource("content://media/99", ignoreId = 0))
    }

    @Test fun theSameAmountAndPayeeWithinTheWindowIsADuplicate() = runBlocking {
        dao.insert(expense())
        val window = 24L * 60 * 60 * 1000

        // Same payee, different capitalisation and padding, an hour later.
        assertNotNull(
            dao.findSimilar(50000, "  blue tokai ", now + 3_600_000, window, ignoreId = 0)
        )
        // Beyond the window a genuine repeat purchase must still be recordable.
        assertNull(dao.findSimilar(50000, "Blue Tokai", now + window + 1000, window, ignoreId = 0))
        // A different amount is a different expense.
        assertNull(dao.findSimilar(60000, "Blue Tokai", now, window, ignoreId = 0))
    }

    @Test fun editingAnExpenseDoesNotFlagItselfAsItsOwnDuplicate() = runBlocking {
        val id = dao.insert(expense(sourceUri = "content://media/42"))
        assertNull(dao.findBySource("content://media/42", ignoreId = id))
        assertNull(dao.findSimilar(50000, "Blue Tokai", now, 24L * 60 * 60 * 1000, ignoreId = id))
    }

    @Test fun balancesCountOnlyUnsettledSharesAndSettlingClearsThem() = runBlocking {
        val rahul = people.insert(Person(name = "Rahul"))
        val id = dao.insert(expense(amountPaise = 50000))
        dao.insertSplits(
            listOf(
                ExpenseSplit(expenseId = id, personId = rahul, amountPaise = 20000),
                ExpenseSplit(expenseId = id, personId = null, amountPaise = 30000)
            )
        )

        // My own share is never owed to anyone, so only Rahul's 200 counts.
        assertEquals(20000, dao.observeBalances().first().single { it.personId == rahul }.owedPaise)

        val share = dao.observeOutstanding(rahul).first().single()
        assertEquals(20000, share.amountPaise)
        assertEquals("Blue Tokai", share.merchant)

        dao.settleShare(share.splitId, System.currentTimeMillis())
        assertEquals(0, dao.observeBalances().first().single { it.personId == rahul }.owedPaise)
        assertEquals(0, dao.observeOutstanding(rahul).first().size)
    }

    @Test fun settleEverythingClearsEveryOutstandingShareForOnePerson() = runBlocking {
        val rahul = people.insert(Person(name = "Rahul"))
        val priya = people.insert(Person(name = "Priya"))
        listOf(10000L, 20000L).forEach { amount ->
            val id = dao.insert(expense(amountPaise = amount * 2))
            dao.insertSplits(
                listOf(
                    ExpenseSplit(expenseId = id, personId = rahul, amountPaise = amount),
                    ExpenseSplit(expenseId = id, personId = priya, amountPaise = amount)
                )
            )
        }
        assertEquals(30000, dao.observeBalances().first().single { it.personId == rahul }.owedPaise)

        dao.settleEverything(rahul, System.currentTimeMillis())
        val balances = dao.observeBalances().first()
        assertEquals(0, balances.single { it.personId == rahul }.owedPaise)
        // Settling with one person must not touch anybody else's balance.
        assertEquals(30000, balances.single { it.personId == priya }.owedPaise)
    }

    /**
     * Deleting marks the row so the removal can be pushed to another device, which means the row
     * outlives the delete and every read has to be the thing that hides it. The splits are still
     * physically there; what matters is that nothing surfaces them.
     */
    @Test fun deletingAnExpenseHidesItAndEverythingCountingOnIt() = runBlocking {
        val rahul = people.insert(Person(name = "Rahul"))
        val id = dao.insert(expense())
        dao.insertSplits(listOf(ExpenseSplit(expenseId = id, personId = rahul, amountPaise = 20000)))

        dao.tombstone(id, now)

        assertEquals(0, dao.observeAll().first().count { it.expense.id == id })
        assertEquals(0, dao.observeOutstanding(rahul).first().size)
        assertEquals(0, dao.observeBalances().first().single { it.personId == rahul }.owedPaise)
        // The row is still on disk, carrying the mark that will travel.
        assertEquals(now, dao.byId(id)!!.deletedAt)
    }

    @Test fun aDeletedExpenseStopsBlockingItsOwnDuplicate() = runBlocking {
        val first = dao.insert(expense(merchant = "Swiggy", amountPaise = 18200))
        dao.tombstone(first, now)
        val again = expense(merchant = "Swiggy", amountPaise = 18200)
        assertEquals(null, dao.findSimilar(again.amountPaise, again.merchant, again.paidAt, 86_400_000L, 0))
    }

    @Test fun expenseDetailsCarriesItsSplitsAndAttachments() = runBlocking {
        val rahul = people.insert(Person(name = "Rahul"))
        val id = dao.insert(expense(amountPaise = 50000))
        dao.insertSplits(
            listOf(
                ExpenseSplit(expenseId = id, personId = rahul, amountPaise = 20000),
                ExpenseSplit(expenseId = id, personId = null, amountPaise = 30000)
            )
        )
        dao.insertAttachments(listOf(Attachment(expenseId = id, path = "/tmp/x.jpg", addedAt = now)))

        val details = dao.observeAll().first().single()
        assertEquals(2, details.splits.size)
        assertEquals(1, details.attachments.size)
        assertEquals(20000, details.outstandingPaise)
    }

    /** A bill [payer] paid for me, of which [mine] was my share. */
    private suspend fun paidFor(payer: Long, total: Long, mine: Long): Long {
        val id = dao.insert(expense(amountPaise = total).copy(paidByPersonId = payer))
        dao.insertSplits(
            listOf(
                ExpenseSplit(expenseId = id, personId = null, amountPaise = mine),
                ExpenseSplit(expenseId = id, personId = payer, amountPaise = total - mine)
            )
        )
        return id
    }

    /** A bill I paid, of which [theirs] is what [person] owes me. */
    private suspend fun paidByMe(person: Long, total: Long, theirs: Long): Long {
        val id = dao.insert(expense(amountPaise = total))
        dao.insertSplits(
            listOf(
                ExpenseSplit(expenseId = id, personId = person, amountPaise = theirs),
                ExpenseSplit(expenseId = id, personId = null, amountPaise = total - theirs)
            )
        )
        return id
    }

    @Test fun aBillSomeoneElsePaidIsWhatIOweThem() = runBlocking {
        val asha = people.insert(Person(name = "Asha"))
        paidFor(asha, total = 90000, mine = 30000)

        val balance = dao.observeBalances().first().single { it.personId == asha }
        // Their own 600 is theirs; only my 300 is a debt, and it runs from me to them.
        assertEquals(-30000, balance.owedPaise)
        assertEquals(0, balance.owedToMePaise)
        assertEquals(30000, balance.iOwePaise)

        val open = dao.observeOutstanding(asha).first().single()
        assertEquals(30000, open.amountPaise)
        assertEquals(true, open.iOwe)
    }

    @Test fun moneyRunningBothWaysIsNetted() = runBlocking {
        val asha = people.insert(Person(name = "Asha"))
        paidByMe(asha, total = 100000, theirs = 50000)
        paidFor(asha, total = 60000, mine = 20000)

        val balance = dao.observeBalances().first().single { it.personId == asha }
        assertEquals(50000, balance.owedToMePaise)
        assertEquals(20000, balance.iOwePaise)
        assertEquals(30000, balance.owedPaise)
        assertEquals(2, dao.observeOutstanding(asha).first().size)
        assertEquals(2, people.outstandingCount(asha))
    }

    @Test fun settlingUpSquaresBothDirectionsAndReopeningBringsBothBack() = runBlocking {
        val asha = people.insert(Person(name = "Asha"))
        val rahul = people.insert(Person(name = "Rahul"))
        paidByMe(asha, total = 100000, theirs = 50000)
        paidFor(asha, total = 60000, mine = 20000)
        // Somebody else's balance must be left exactly as it was.
        paidFor(rahul, total = 40000, mine = 10000)

        dao.settleEverything(asha, now)
        val after = dao.observeBalances().first().associateBy { it.personId }
        assertEquals(0, after.getValue(asha).owedPaise)
        assertEquals(0, dao.observeOutstanding(asha).first().size)
        assertEquals(-10000, after.getValue(rahul).owedPaise)

        dao.reopenEverything(asha, now + 1)
        assertEquals(30000, dao.observeBalances().first().single { it.personId == asha }.owedPaise)
    }

    /**
     * The payer's own share sits on the bill as a split with their id. On a bill they paid that
     * must never read as them owing me, or every bill a friend covered would show as money owed.
     */
    @Test fun thePayersOwnShareIsNotADebt() = runBlocking {
        val asha = people.insert(Person(name = "Asha"))
        paidFor(asha, total = 90000, mine = 0)
        val balance = dao.observeBalances().first().single { it.personId == asha }
        assertEquals(0, balance.owedPaise)
        assertEquals(0, dao.observeOutstanding(asha).first().size)
        assertEquals(0, dao.observeAll().first().single().outstandingPaise)
    }
}
