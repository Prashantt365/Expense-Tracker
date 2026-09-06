package com.example.expensetracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitCalculatorTest {

    @Test fun `my share is the remainder once everyone else is entered`() {
        val result = SplitCalculator.compute(50000, SplitMode.CUSTOM, mapOf(1L to "150", 2L to "125.50"))
        val valid = result as SplitResult.Valid
        assertEquals(22450, valid.myShare)
        assertEquals(15000, valid.shares.first { it.personId == 1L }.amountPaise)
        assertEquals(12550, valid.shares.first { it.personId == 2L }.amountPaise)
    }

    @Test fun `the parts always add back up to the total`() {
        val total = 99999L
        val valid = SplitCalculator.compute(total, SplitMode.CUSTOM, mapOf(1L to "333.33", 2L to "1.01")) as SplitResult.Valid
        assertEquals(total, valid.shares.sumOf { it.amountPaise })
    }

    @Test fun `an equal split covers everyone tagged plus me`() {
        val valid = SplitCalculator.compute(30000, SplitMode.EQUAL, mapOf(1L to "", 2L to "")) as SplitResult.Valid
        // Three ways: two people and me.
        assertEquals(10000, valid.shares.first { it.personId == 1L }.amountPaise)
        assertEquals(10000, valid.shares.first { it.personId == 2L }.amountPaise)
        assertEquals(10000, valid.myShare)
    }

    @Test fun `an equal split gives the indivisible paise to me`() {
        val valid = SplitCalculator.compute(10000, SplitMode.EQUAL, mapOf(1L to "", 2L to "")) as SplitResult.Valid
        // 100.00 three ways is 33.33 each, and the stray paise stays with me.
        assertEquals(3333, valid.shares.first { it.personId == 1L }.amountPaise)
        assertEquals(3334, valid.myShare)
        assertEquals(10000, valid.shares.sumOf { it.amountPaise })
    }

    @Test fun `an equal split with nobody tagged is entirely mine`() {
        val valid = SplitCalculator.compute(10000, SplitMode.EQUAL, emptyMap()) as SplitResult.Valid
        assertEquals(10000, valid.myShare)
    }

    @Test fun `percentages become rupee shares with the rest left to me`() {
        val valid = SplitCalculator.compute(20000, SplitMode.PERCENT, mapOf(1L to "25", 2L to "10")) as SplitResult.Valid
        assertEquals(5000, valid.shares.first { it.personId == 1L }.amountPaise)
        assertEquals(2000, valid.shares.first { it.personId == 2L }.amountPaise)
        assertEquals(13000, valid.myShare)
        assertEquals(20000, valid.shares.sumOf { it.amountPaise })
    }

    @Test fun `a blank percentage leaves that person out`() {
        val valid = SplitCalculator.compute(20000, SplitMode.PERCENT, mapOf(1L to "50", 2L to "")) as SplitResult.Valid
        assertEquals(1, valid.shares.count { it.personId != null })
        assertEquals(10000, valid.myShare)
    }

    @Test fun `percentages over a hundred are rejected`() {
        val result = SplitCalculator.compute(20000, SplitMode.PERCENT, mapOf(1L to "60", 2L to "50"))
        assertTrue(result is SplitResult.Invalid)
    }

    @Test fun `a full hundred percent leaves me nothing`() {
        val valid = SplitCalculator.compute(20000, SplitMode.PERCENT, mapOf(1L to "40", 2L to "60")) as SplitResult.Valid
        assertEquals(0, valid.myShare)
        assertTrue(valid.shares.none { it.personId == null })
        assertEquals(20000, valid.shares.sumOf { it.amountPaise })
    }

    @Test fun `fractional percentages still reconcile with the total`() {
        val valid = SplitCalculator.compute(10000, SplitMode.PERCENT, mapOf(1L to "33.33", 2L to "33.33")) as SplitResult.Valid
        assertEquals(10000, valid.shares.sumOf { it.amountPaise })
    }

    @Test fun `a fully covered bill leaves me no share at all`() {
        val valid = SplitCalculator.compute(20000, SplitMode.CUSTOM, mapOf(1L to "100", 2L to "100")) as SplitResult.Valid
        assertEquals(0, valid.myShare)
        assertTrue("my share should not be stored when it is zero", valid.shares.none { it.personId == null })
    }

    @Test fun `blank shares are ignored rather than treated as zero`() {
        val valid = SplitCalculator.compute(10000, SplitMode.CUSTOM, mapOf(1L to "", 2L to "40")) as SplitResult.Valid
        assertEquals(1, valid.shares.count { it.personId != null })
        assertEquals(6000, valid.myShare)
    }

    @Test fun `shares beyond the total are rejected`() {
        val result = SplitCalculator.compute(10000, SplitMode.CUSTOM, mapOf(1L to "60", 2L to "60"))
        assertTrue(result is SplitResult.Invalid)
    }

    @Test fun `an unreadable share is rejected rather than silently dropped`() {
        val result = SplitCalculator.compute(10000, SplitMode.CUSTOM, mapOf(1L to "abc"))
        assertTrue(result is SplitResult.Invalid)
    }

    @Test fun `extra precision rounds instead of throwing`() {
        // The old save path threw here, swallowed it, and lost the expense without a word.
        // parsePaise returns Long?, so these must be Long literals or the boxed compare fails.
        assertEquals(1056L, SplitCalculator.parsePaise("10.555"))
        assertEquals(25000L, SplitCalculator.parsePaise("250"))
        assertEquals(125050L, SplitCalculator.parsePaise("1,250.50"))
        assertEquals(null, SplitCalculator.parsePaise("not a number"))
    }
}
