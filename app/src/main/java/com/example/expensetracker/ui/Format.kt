package com.example.expensetracker.ui

import com.example.expensetracker.AppCurrency
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Amounts are held as hundredths of a unit regardless of the currency on show, so the stored value
 * becomes a decimal before formatting rather than being divided into a Double.
 *
 * The decimal count then has to be set from the currency by hand. Handing NumberFormat a currency
 * swaps the symbol but leaves the formatting locale's own count in place, so on an en-IN phone yen
 * would print as "182.00", which yen has no minor unit for, and a dinar would silently lose its
 * third place. defaultFractionDigits reports -1 for the metal and test codes, hence the floor.
 */
fun money(minorUnits: Long): String {
    val chosen = AppCurrency.currency
    val digits = chosen.defaultFractionDigits.coerceAtLeast(0)
    return NumberFormat.getCurrencyInstance(Locale.getDefault())
        .apply {
            currency = chosen
            minimumFractionDigits = digits
            maximumFractionDigits = digits
            // NumberFormat rounds half to even by default, which would show a stored 182.50 as
            // 182 yen while every amount entering the app is rounded half up. Display follows
            // storage rather than the other way round.
            roundingMode = RoundingMode.HALF_UP
        }
        .format(BigDecimal.valueOf(minorUnits, 2))
}

fun shortDate(millis: Long): String =
    SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(millis))

fun monthLabel(millis: Long): String =
    SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(millis))

/** Typed amount to stored hundredths, tolerating the extra precision OCR sometimes reports. */
fun amountToMinorUnits(text: String): Long? = runCatching {
    BigDecimal(text.trim().replace(",", ""))
        .movePointRight(2)
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact()
}.getOrNull()
