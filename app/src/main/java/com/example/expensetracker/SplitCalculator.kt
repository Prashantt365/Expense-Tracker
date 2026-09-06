package com.example.expensetracker

import java.math.BigDecimal
import java.math.RoundingMode

data class ComputedShare(
    /** null is my own share of the bill, which is never owed to anybody. */
    val personId: Long?,
    val amountPaise: Long
)

sealed interface SplitResult {
    data class Valid(val shares: List<ComputedShare>, val myShare: Long) : SplitResult
    data class Invalid(val message: String) : SplitResult
}

/**
 * Turns the shares typed into the editor into rows to store.
 *
 * Every person's share is entered by hand; mine is whatever is left over, so the parts always
 * reconcile with the total no matter what was typed.
 */
object SplitCalculator {

    fun compute(totalPaise: Long, typed: Map<Long, String>): SplitResult {
        if (totalPaise <= 0) return SplitResult.Invalid("Amount must be more than zero")

        val shares = mutableListOf<ComputedShare>()
        var assigned = 0L
        typed.forEach { (personId, text) ->
            if (text.isBlank()) return@forEach
            val paise = parsePaise(text) ?: return SplitResult.Invalid("Check the share you entered for each person")
            if (paise < 0) return SplitResult.Invalid("A share cannot be negative")
            assigned += paise
            shares += ComputedShare(personId, paise)
        }

        if (assigned > totalPaise) return SplitResult.Invalid("Shares add up to more than the total")

        val myShare = totalPaise - assigned
        if (myShare > 0) shares += ComputedShare(null, myShare)
        return SplitResult.Valid(shares, myShare)
    }

    /** OCR can hand back more precision than paise can hold, so round rather than reject. */
    fun parsePaise(amount: String): Long? = runCatching {
        BigDecimal(amount.trim().replace(",", ""))
            .movePointRight(2)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    }.getOrNull()
}
