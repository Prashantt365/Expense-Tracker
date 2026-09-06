package com.example.expensetracker.ui

import java.math.BigDecimal
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun money(paise: Long): String =
    NumberFormat.getCurrencyInstance(Locale("en", "IN")).format(paise / 100.0)

fun shortDate(millis: Long): String =
    SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(millis))

fun monthLabel(millis: Long): String =
    SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(millis))

/** Rupee text to paise, tolerating the extra precision OCR sometimes reports. */
fun rupeesToPaise(text: String): Long? = runCatching {
    BigDecimal(text.trim().replace(",", ""))
        .movePointRight(2)
        .setScale(0, java.math.RoundingMode.HALF_UP)
        .longValueExact()
}.getOrNull()
