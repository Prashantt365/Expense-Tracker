package com.peyo.app

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

    /**
     * Rounding each share on its own can overshoot: 50% of 10.01 twice is 5.01 + 5.01. So the
     * group's combined share is rounded once, everyone gets their share rounded down, and the paise
     * that leaves over go one each to the shares that lost the most to rounding.
     */
    private fun percent(totalPaise: Long, typed: Map<Long, String>): SplitResult {
        val hundred = BigDecimal(100)
        val total = BigDecimal(totalPaise)
        var percentage = BigDecimal.ZERO
        val exact = mutableListOf<Pair<Long, BigDecimal>>()
        typed.forEach { (personId, text) ->
            if (text.isBlank()) return@forEach
            val pct = runCatching { BigDecimal(text.trim()) }.getOrNull()
                ?: return SplitResult.Invalid("Check the percentages you entered")
            if (pct.signum() < 0) return SplitResult.Invalid("A percentage cannot be negative")
            percentage = percentage.add(pct)
            if (percentage > hundred) return SplitResult.Invalid("Percentages add up to more than 100%")
            // Dividing by 100 always terminates, so this is the exact share in fractional paise.
            exact += personId to total.multiply(pct).divide(hundred)
        }
        val target = total.multiply(percentage).divide(hundred).setScale(0, RoundingMode.HALF_UP).toLong()
        val floors = exact.map { (_, share) -> share.setScale(0, RoundingMode.FLOOR).toLong() }
        val leftover = (target - floors.sum()).toInt()
        val roundedUp = exact.indices
            .sortedByDescending { exact[it].second.subtract(BigDecimal(floors[it])) }
            .take(leftover)
            .toSet()
        val shares = exact.mapIndexed { index, (personId, _) ->
            ComputedShare(personId, floors[index] + if (index in roundedUp) 1 else 0)
        }
        return finish(shares.toMutableList(), totalPaise, shares.sumOf { it.amountPaise })
    }

    private fun finish(shares: MutableList<ComputedShare>, totalPaise: Long, assigned: Long): SplitResult {
        val myShare = totalPaise - assigned
        if (myShare > 0) shares += ComputedShare(null, myShare)
        return SplitResult.Valid(shares, myShare)
    }

    /**
     * Rupee text to paise, tolerating the extra precision OCR sometimes reports.
     *
     * A comma is usually grouping ("1,000", "1,00,000"), but a German, French or Indonesian keyboard
     * types it as the decimal point, so a lone comma with one or two digits after it is read as one:
     * "12,50" is 12.50, not 1250. "1.234,50" is that same convention with dots doing the grouping.
     */
    fun parsePaise(amount: String): Long? = runCatching {
        val text = amount.trim()
        val plain = when {
            decimalComma.matches(text) -> text.replace(".", "").replace(',', '.')
            else -> text.replace(",", "")
        }
        BigDecimal(plain)
            .movePointRight(2)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    }.getOrNull()

    private val decimalComma = Regex("^[-+]?(?:\\d*|\\d{1,3}(?:\\.\\d{3})+),\\d{1,2}$")
}
