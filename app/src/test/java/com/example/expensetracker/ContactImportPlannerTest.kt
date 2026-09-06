package com.example.expensetracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactImportPlannerTest {

    @Test fun `a contact already in the people list is flagged`() {
        val plan = ContactImportPlanner.plan(listOf("Rahul Sharma"), listOf("rahul sharma"))
        assertEquals("rahul sharma", plan.single().existingPersonName)
        assertTrue(plan.single().isFlagged)
    }

    @Test fun `the same person twice in the phone book is flagged once`() {
        val plan = ContactImportPlanner.plan(listOf("Rahul Sharma", "Rahul S"), emptyList())
        val clean = plan.filterNot { it.isFlagged }
        assertEquals(1, clean.size)
        assertEquals(1, plan.count { it.duplicateOfEarlierContact })
    }

    @Test fun `unrelated contacts all come through unflagged`() {
        val plan = ContactImportPlanner.plan(
            listOf("Rahul Sharma", "Priya Menon", "Arjun Nair"),
            emptyList()
        )
        assertEquals(3, plan.size)
        assertTrue(plan.none { it.isFlagged })
        plan.forEach { assertNull(it.existingPersonName) }
    }

    @Test fun `nameless contacts are dropped entirely`() {
        val plan = ContactImportPlanner.plan(listOf("", "   ", "🎉", "Rahul"), emptyList())
        assertEquals(1, plan.size)
        assertEquals("Rahul", plan.single().name)
    }

    @Test fun `importable contacts are listed before flagged ones`() {
        val plan = ContactImportPlanner.plan(
            listOf("Rahul Sharma", "Zoya Khan", "Rahul Sharma"),
            listOf("Rahul Sharma")
        )
        assertFalse(plan.first().isFlagged)
        assertEquals("Zoya Khan", plan.first().name)
        assertTrue(plan.drop(1).all { it.isFlagged })
    }

    @Test fun `a flagged contact does not itself become a duplicate target`() {
        // Rahul is already a person, so both phone entries flag against the person list rather
        // than the second flagging against the first.
        val plan = ContactImportPlanner.plan(
            listOf("Rahul Sharma", "Rahul S"),
            listOf("Rahul Sharma")
        )
        assertTrue(plan.all { it.existingPersonName != null })
        assertTrue(plan.none { it.duplicateOfEarlierContact })
    }

    @Test fun `an empty phone book plans nothing`() {
        assertTrue(ContactImportPlanner.plan(emptyList(), listOf("Rahul")).isEmpty())
    }
}
