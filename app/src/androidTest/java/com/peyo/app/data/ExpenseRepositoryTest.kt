package com.peyo.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** What saving and renaming do to the rows underneath, which the screens cannot show directly. */
@RunWith(AndroidJUnit4::class)
class ExpenseRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: ExpenseRepository

    @Before fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = ExpenseRepository(context, db)
    }

    @After fun close() = db.close()

    private fun dinner(id: Long = 0) = Expense(
        id = id, amountPaise = 90000, category = "Food", note = "", merchant = "Toit", paidAt = 1L
    )

    private fun share(personId: Long?, paise: Long) =
        ExpenseSplit(expenseId = 0, personId = personId, amountPaise = paise)

    private suspend fun splitsOf(expenseId: Long) =
        db.syncDao().allSplits().filter { it.expenseId == expenseId }

    @Test fun editingAnExpenseKeepsASettledShareSettled() = runBlocking {
        val asha = repository.addPerson("Asha")
        val id = repository.save(dinner(), listOf(share(null, 60000), share(asha, 30000)), emptyList())
        val ashaShare = splitsOf(id).single { it.personId == asha }
        repository.settleShare(ashaShare.id)

        // Fixing the note is not a reason for Asha to owe the money again.
        repository.save(
            dinner(id).copy(note = "Birthday"),
            listOf(share(null, 60000), share(asha, 30000)),
            emptyList()
        )

        val after = splitsOf(id).filter { it.deletedAt == null }
        assertEquals(2, after.size)
        val kept = after.single { it.personId == asha }
        assertEquals(ashaShare.remoteId, kept.remoteId)
        assertNotNull(kept.settledAt)
        assertEquals(0L, repository.balances.first().single { it.personId == asha }.owedPaise)
    }

    @Test fun changingAShareReopensItAndRemovingOneLeavesATombstone() = runBlocking {
        val asha = repository.addPerson("Asha")
        val ravi = repository.addPerson("Ravi")
        val id = repository.save(
            dinner(),
            listOf(share(null, 30000), share(asha, 30000), share(ravi, 30000)),
            emptyList()
        )
        splitsOf(id).forEach { repository.settleShare(it.id) }

        repository.save(dinner(id), listOf(share(null, 45000), share(asha, 45000)), emptyList())

        val all = splitsOf(id)
        val gone = all.single { it.personId == ravi }
        assertNotNull("Ravi's share has to reach the server as a deletion", gone.deletedAt)
        assertNull(gone.syncedAt)
        val changed = all.single { it.personId == asha }
        assertEquals(45000L, changed.amountPaise)
        assertNull("what was settled is no longer what is owed", changed.settledAt)

        val balances = repository.balances.first().associate { it.personId to it.owedPaise }
        assertEquals(45000L, balances[asha])
        assertEquals(0L, balances[ravi])
    }

    @Test fun renamingOntoATakenNameIsRefusedRatherThanCrashing() = runBlocking {
        repository.addPerson("Asha")
        val raviId = repository.addPerson("Ravi")
        val ravi = repository.people.first().single { it.id == raviId }

        assertFalse(repository.renamePerson(ravi, "Asha"))
        assertEquals(setOf("Asha", "Ravi"), repository.people.first().map { it.name }.toSet())
    }

    @Test fun renamingOntoADeletedNameFreesItFirst() = runBlocking {
        val oldId = repository.addPerson("Asha")
        repository.deletePerson(repository.people.first().single { it.id == oldId })
        val raviId = repository.addPerson("Ravi")
        val ravi = repository.people.first().single { it.id == raviId }

        assertTrue(repository.renamePerson(ravi, "Asha"))
        assertEquals(listOf("Asha"), repository.people.first().map { it.name })
    }

    @Test fun renamingACategoryOntoATakenNameIsRefused() = runBlocking {
        repository.addCategory("Coffee")
        repository.addCategory("Tea")
        val tea = repository.categories.first().single { it.name == "Tea" }

        assertFalse(repository.renameCategory(tea, "Coffee"))
        assertTrue(repository.categories.first().any { it.name == "Tea" })
    }
}
