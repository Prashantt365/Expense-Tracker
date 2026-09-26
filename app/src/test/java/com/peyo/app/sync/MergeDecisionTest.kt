package com.peyo.app.sync

import com.peyo.app.data.Expense
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

    @Test fun `an untouched local row ignores the version it already holds`() {
        assertEquals(Merge.SKIP, mergeDecision(local(later, syncedAt = later), later))
    }

    /**
     * An untouched row holds nothing the server lacks, so the server's copy wins whatever the
     * stamps say. A row last stamped by a phone whose clock runs fast used to look newer than a
     * real change made elsewhere, and that change was silently ignored.
     */
    @Test fun `an untouched local row takes the server copy even when its own stamp looks newer`() {
        assertEquals(Merge.UPDATE, mergeDecision(local(later, syncedAt = later), earlier))
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

    /**
     * A restore, or a push whose answer was lost, leaves a row unsynced with the very contents the
     * server holds, under a later server stamp. That is not a disagreement, and it is taken so the
     * row is marked in step rather than pushed again or put in front of the user.
     */
    @Test fun `identical contents are never a conflict`() {
        assertEquals(Merge.UPDATE, mergeDecision(local(earlier, syncedAt = null), later, sameContent = true))
        assertEquals(Merge.UPDATE, mergeDecision(local(later, syncedAt = null), earlier, sameContent = true))
    }

    /**
     * The local edit is stamped by this phone and the server copy by the server. With the phone
     * a minute behind, an edit made after the server's last change still carries an earlier
     * stamp, and only shifting it onto the server's clock stops that reading as a conflict.
     */
    @Test fun `a local edit is compared on the server's clock`() {
        val minute = 60_000L
        val editedAt = 10 * minute
        val serverChangedAt = editedAt + 30_000 // on the server's clock, before the edit
        val edit = local(editedAt, syncedAt = null)

        assertEquals(Merge.CONFLICT, mergeDecision(edit, serverChangedAt, clockOffset = 0))
        assertEquals(Merge.SKIP, mergeDecision(edit, serverChangedAt, clockOffset = minute))
        // And a server change genuinely after the edit is still a conflict once shifted.
        assertEquals(Merge.CONFLICT, mergeDecision(edit, editedAt + minute + 1, clockOffset = minute))
    }

    /** The offset is only for edits in flight; a row in step takes the server copy regardless. */
    @Test fun `the clock offset does not stop an untouched row updating`() {
        assertEquals(Merge.UPDATE, mergeDecision(local(later, syncedAt = later), earlier, clockOffset = -5_000))
    }

    @Test fun `a deletion is decided the same way as any other change`() {
        val deletedLocally = local(earlier, syncedAt = null).copy(deletedAt = earlier)
        assertEquals(Merge.CONFLICT, mergeDecision(deletedLocally, later))

        val deletedAndPushed = local(earlier, syncedAt = earlier).copy(deletedAt = earlier)
        assertEquals(Merge.UPDATE, mergeDecision(deletedAndPushed, later))
    }
}
