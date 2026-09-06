package com.example.expensetracker

import java.math.BigDecimal
import java.math.RoundingMode

data class ComputedShare(
    /** null is my own share of the bill, which is never owed to anybody. */
    val personId: Long?,
    val amountPaise: Long
)

enum class SplitMode(val label: String) {
    /** Type each person's rupee share. Leaving one blank simply excludes them. */
    CUSTOM("Custom"),

    /** Divide evenly across everyone tagged plus me. */
    EQUAL("Equal"),

    /** Type each person's percentage of the bill; mine is the remaining percentage. */
    PERCENT("Percent")
}

sealed interface SplitResult {
    data class Valid(val shares: List<ComputedShare>, val myShare: Long) : SplitResult
    data class Invalid(val message: String) : SplitResult
}

/**
 * Turns the shares set up in the editor into rows to store.
 *
 * Whichever mode is used, my share is whatever is left over, so the parts always reconcile with
 * the total and no rounding remainder can go missing.
 */
object SplitCalculator {

    fun compute(totalPaise: Long, mode: SplitMode, typed: Map<Long, String>): SplitResult {
        if (totalPaise <= 0) return SplitResult.Invalid("Amount must be more than zero")
        return when (mode) {
            SplitMode.CUSTOM -> custom(totalPaise, typed)
            SplitMode.EQUAL -> equal(totalPaise, typed.keys)
            SplitMode.PERCENT -> percent(totalPaise, typed)
        }
    }

    private fun custom(totalPaise: Long, typed: Map<Long, String>): SplitResult {
        val shares = mutableListOf<ComputedShare>()
        var assigned = 0L
        typed.forEach { (personId, text) ->
            // A share is optional: an untouched field just leaves that person out.
            if (text.isBlank()) return@forEach
            val paise = parsePaise(text) ?: return SplitResult.Invalid("Check the share you entered for each person")
            if (paise < 0) return SplitResult.Invalid("A share cannot be negative")
            assigned += paise
            shares += ComputedShare(personId, paise)
        }
        if (assigned > totalPaise) return SplitResult.Invalid("Shares add up to more than the total")
        return finish(shares, totalPaise, assigned)
    }

    private fun equal(totalPaise: Long, people: Set<Long>): SplitResult {
        if (people.isEmpty()) return SplitResult.Valid(listOf(ComputedShare(null, totalPaise)), totalPaise)
        // Everyone tagged, plus me.
        val ways = people.size + 1
        val each = totalPaise / ways
        val shares = people.map { ComputedShare(it, each) }
        // The indivisible paise land on me rather than being dropped.
        return finish(shares.toMutableList(), totalPaise, each * people.size)
    }

    private fun percent(totalPaise: Long, typed: Map<Long, String>): SplitResult {
        val shares = mutableListOf<ComputedShare>()
        val hundred = BigDecimal(100)
        var percentage = BigDecimal.ZERO
        var assigned = 0L
        typed.forEach { (personId, text) ->
            if (text.isBlank()) return@forEach
            val pct = runCatching { BigDecimal(text.trim()) }.getOrNull()
                ?: return SplitResult.Invalid("Check the percentages you entered")
            if (pct.signum() < 0) return SplitResult.Invalid("A percentage cannot be negative")
            percentage = percentage.add(pct)
            if (percentage > hundred) return SplitResult.Invalid("Percentages add up to more than 100%")
            val paise = BigDecimal(totalPaise).multiply(pct)
                .divide(hundred, 0, RoundingMode.HALF_UP)
                .toLong()
            assigned += paise
            shares += ComputedShare(personId, paise)
        }
        if (assigned > totalPaise) return SplitResult.Invalid("Percentages add up to more than the total")
        return finish(shares, totalPaise, assigned)
    }

    private fun finish(shares: MutableList<ComputedShare>, totalPaise: Long, assigned: Long): SplitResult {
        val myShare = totalPaise - assigned
        if (myShare > 0) shares += ComputedShare(null, myShare)
        return SplitResult.Valid(shares, myShare)
    }

    /** Rupee text to paise, tolerating the extra precision OCR sometimes reports. */
    fun parsePaise(amount: String): Long? = runCatching {
        BigDecimal(amount.trim().replace(",", ""))
            .movePointRight(2)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    }.getOrNull()
}
