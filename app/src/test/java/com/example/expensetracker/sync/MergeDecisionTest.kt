package com.example.expensetracker.sync

import com.example.expensetracker.data.Expense
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule that decides whether a pulled row is taken, ignored, or put in front of the user.
 *
 * This is the part of syncing that can silently destroy work, so each case is spelled out rather
 * than left to be inferred from the implementation.
 */
class MergeDecisionTest {

    private val earlier = 1_000L
    private val later = 2_000L

    private fun local(updatedAt: Long, syncedAt: Long?) = Expense(
        amountPaise = 1,
        category = "Food",
        note = "",
        merchant = "",
        paidAt = earlier,
        updatedAt = updatedAt,
        syncedAt = syncedAt
    )

    @Test fun `a row that is not held locally is inserted`() {
        assertEquals(Merge.INSERT, mergeDecision(null, later))
    }

    @Test fun `an untouched local row takes a newer server copy`() {
        assertEquals(Merge.UPDATE, mergeDecision(local(earlier, syncedAt = earlier), later))
    }

    @Test fun `an untouched local row ignores a server copy no newer than it`() {
        assertEquals(Merge.SKIP, mergeDecision(local(later, syncedAt = later), later))
        assertEquals(Merge.SKIP, mergeDecision(local(later, syncedAt = later), earlier))
    }

    /**
     * Both sides moved since they last agreed. Overwriting either one loses an edit somebody
     * actually made, which is the whole reason this is put to the user.
     */
    @Test fun `an edited local row against a newer server copy is a conflict`() {
        assertEquals(Merge.CONFLICT, mergeDecision(local(earlier, syncedAt = null), later))
    }

    /**
     * A local edit newer than the server is not a conflict: the server has not moved since this
     * device last agreed with it, and the push is about to carry the edit up.
     */
    @Test fun `a local edit newer than the server is left for the push`() {
        assertEquals(Merge.SKIP, mergeDecision(local(later, syncedAt = null), earlier))
    }

    /**
     * Equal stamps are the same write coming back, most often this device's own push being read
     * again. Calling that a conflict would put a meaningless choice in front of the user on every
     * sync.
     */
    @Test fun `an equal stamp is not a conflict`() {
        assertEquals(Merge.SKIP, mergeDecision(local(later, syncedAt = null), later))
        assertEquals(Merge.SKIP, mergeDecision(local(later, syncedAt = later), later))
    }

    @Test fun `a deletion is decided the same way as any other change`() {
        val deletedLocally = local(earlier, syncedAt = null).copy(deletedAt = earlier)
        assertEquals(Merge.CONFLICT, mergeDecision(deletedLocally, later))

        val deletedAndPushed = local(earlier, syncedAt = earlier).copy(deletedAt = earlier)
        assertEquals(Merge.UPDATE, mergeDecision(deletedAndPushed, later))
    }
}
