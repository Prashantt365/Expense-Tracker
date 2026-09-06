package com.example.expensetracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NameMatcherTest {

    @Test fun `normalisation strips case accents punctuation and honorifics`() {
        assertEquals("rahul sharma", NameMatcher.normalize("  Mr. Rahul   Sharma "))
        assertEquals("rahul sharma", NameMatcher.normalize("RAHUL SHARMA"))
        assertEquals("jose alvarez", NameMatcher.normalize("José Álvarez"))
        assertEquals("rahul sharma", NameMatcher.normalize("Rahul Sharma 🎉"))
    }

    @Test fun `the same name saved twice is caught`() {
        assertTrue(NameMatcher.isLikelySame("Rahul Sharma", "rahul sharma"))
        assertTrue(NameMatcher.isLikelySame("Rahul Sharma", "Mr Rahul Sharma"))
    }

    @Test fun `a reversed name order is the same person`() {
        assertTrue(NameMatcher.isLikelySame("Rahul Sharma", "Sharma Rahul"))
    }

    @Test fun `an abbreviated surname is the same person`() {
        assertTrue(NameMatcher.isLikelySame("Rahul S", "Rahul Sharma"))
        assertTrue(NameMatcher.isLikelySame("Rahul Sharma", "Rahul S"))
    }

    @Test fun `a typo is still the same person`() {
        assertTrue(NameMatcher.isLikelySame("Rahul Sharma", "Rahul Sharmaa"))
        assertTrue(NameMatcher.isLikelySame("Priya Menon", "Priya Menoon"))
    }

    @Test fun `different people are not merged`() {
        assertFalse(NameMatcher.isLikelySame("Rahul Sharma", "Priya Menon"))
        assertFalse(NameMatcher.isLikelySame("Rahul Sharma", "Rohit Sharma"))
        // A shared surname alone must never be enough.
        assertFalse(NameMatcher.isLikelySame("Anil Kumar", "Sunil Kumar"))
    }

    @Test fun `a shared initial alone is not enough`() {
        // Both abbreviate to "R S", but the leading token has to match outright.
        assertFalse(NameMatcher.isLikelySame("Rohit Sharma", "Rahul Sharma"))
        assertFalse(NameMatcher.isLikelySame("S Rahul", "Sharma Priya"))
    }

    @Test fun `blank names never match`() {
        assertFalse(NameMatcher.isLikelySame("", "Rahul"))
        assertFalse(NameMatcher.isLikelySame("Rahul", ""))
        assertFalse(NameMatcher.isLikelySame("   ", "  "))
    }

    @Test fun `similarity is bounded and symmetric`() {
        assertEquals(1f, NameMatcher.similarity("rahul", "rahul"), 0.001f)
        assertEquals(0f, NameMatcher.similarity("rahul", ""), 0.001f)
        assertEquals(
            NameMatcher.similarity("rahul", "rahil"),
            NameMatcher.similarity("rahil", "rahul"),
            0.001f
        )
    }
}
