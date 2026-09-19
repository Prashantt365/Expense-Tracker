package com.example.expensetracker.ui

import com.example.expensetracker.AppCurrency
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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

/**
 * The same amount with the minor units dropped and thousands abbreviated.
 *
 * For the hero figures and the widget, where the pence are noise and the width is the constraint.
 * The full amount is always available a tap away, so rounding here costs nothing and is what keeps
 * a six-figure total from being ellipsised into uselessness on a 2x2 widget.
 */
fun moneyCompact(minorUnits: Long): String {
    val symbol = AppCurrency.currency.symbol
    val units = minorUnits / 100
    val sign = if (units < 0) "-" else ""
    val magnitude = kotlin.math.abs(units)
    val body = when {
        magnitude >= 10_000_000 -> trim(magnitude / 1_000_000.0) + "M"
        magnitude >= 100_000 -> trim(magnitude / 1_000.0) + "K"
        else -> NumberFormat.getIntegerInstance(Locale.getDefault()).format(magnitude)
    }
    return "$sign$symbol$body"
}

private fun trim(value: Double): String {
    val rounded = Math.round(value * 10) / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
}

fun shortDate(millis: Long): String =
    SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(millis))

fun monthLabel(millis: Long): String =
    SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(millis))

fun timeLabel(millis: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))

/**
 * The heading a day of transactions is filed under.
 *
 * "Today" and "Yesterday" are what somebody scanning a list for the coffee they just bought is
 * actually looking for; a date is only useful once the entry is old enough that the day of the
 * week has stopped meaning anything, so the year is dropped until it differs from this one.
 */
fun dayHeading(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when {
        date == today -> "Today"
        date == today.minusDays(1) -> "Yesterday"
        date.year == today.year -> SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date(millis))
        else -> shortDate(millis)
    }
}

/** The calendar day an instant falls on, which is what the transaction list groups by. */
fun dayKey(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()

/** Typed amount to stored hundredths, tolerating the extra precision OCR sometimes reports. */
fun amountToMinorUnits(text: String): Long? = runCatching {
    BigDecimal(text.trim().replace(",", ""))
        .movePointRight(2)
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact()
}.getOrNull()
