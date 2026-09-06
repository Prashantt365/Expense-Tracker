package com.example.expensetracker.data

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

    @Test fun deletingAnExpenseCascadesToItsSplitsAndAttachments() = runBlocking {
        val rahul = people.insert(Person(name = "Rahul"))
        val id = dao.insert(expense())
        dao.insertSplits(listOf(ExpenseSplit(expenseId = id, personId = rahul, amountPaise = 20000)))
        dao.insertAttachments(listOf(Attachment(expenseId = id, path = "/tmp/x.jpg", addedAt = now)))

        dao.delete(dao.byId(id)!!)

        assertEquals(0, dao.observeOutstanding(rahul).first().size)
        assertEquals(0, dao.attachmentsFor(id).size)
        assertEquals(0, dao.observeBalances().first().single { it.personId == rahul }.owedPaise)
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
}
