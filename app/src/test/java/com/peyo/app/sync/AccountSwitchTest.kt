package com.peyo.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Moving a phone's rows from one account to another. Reusing an id the old account holds is a
 * refusal that stops every sync; minting new ones on every switch copies the whole history into an
 * account the phone merely returned to. Both are checked here.
 */
class AccountSwitchTest {

    private fun minter(): () -> String {
        var next = 0
        return { "new-" + next++ }
    }

    @Test fun `every row gets an id the old account does not hold`() {
        val plan = reassignIds(listOf("a1", "a2"), emptyMap(), from = "A", to = "B", mint = minter())
        assertEquals(mapOf("a1" to "new-0", "a2" to "new-1"), plan.ids)
        // A is left remembering what each row is called there.
        assertEquals(mapOf("new-0" to "a1", "new-1" to "a2"), plan.memory["A"])
    }

    /** Signing into the wrong account and straight back must not duplicate anything. */
    @Test fun `going back hands the old ids back`() {
        val toB = reassignIds(listOf("a1", "a2"), emptyMap(), from = "A", to = "B", mint = minter())
        // Signed in to B, a row of B's own arrives.
        val onB = toB.ids.values + "b1"
        val backToA = reassignIds(onB, toB.memory, from = "B", to = "A", mint = minter())

        assertEquals("a1", backToA.ids.getValue(toB.ids.getValue("a1")))
        assertEquals("a2", backToA.ids.getValue(toB.ids.getValue("a2")))
        // B's row is new to A, and B remembers every row including it.
        assertTrue(backToA.ids.getValue("b1").startsWith("new-"))
        assertEquals("b1", backToA.memory.getValue("B")[backToA.ids.getValue("b1")])
        // A's memory has been used up: the rows carry A's ids again.
        assertTrue("A" !in backToA.memory)
    }

    @Test fun `a third account's memory follows the rows to their new names`() {
        val toB = reassignIds(listOf("a1"), emptyMap(), from = "A", to = "B", mint = minter())
        val toC = reassignIds(toB.ids.values, toB.memory, from = "B", to = "C", mint = { "c-row" })
        val backToA = reassignIds(listOf("c-row"), toC.memory, from = "C", to = "A", mint = { "x" })
        assertEquals(mapOf("c-row" to "a1"), backToA.ids)
    }

    @Test fun `a remembered id is never handed to two rows`() {
        val memory = mapOf("B" to mapOf("r1" to "b1", "r2" to "b1"))
        val plan = reassignIds(listOf("r1", "r2"), memory, from = "A", to = "B", mint = minter())
        assertEquals(2, plan.ids.values.toSet().size)
    }
}
