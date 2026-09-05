package com.example.expensetracker

import java.util.Locale

data class ReceiptDraft(val amount: String = "", val merchant: String = "", val note: String = "", val category: String = "Other")

/** Conservative parser: it proposes values only; the user always confirms before storage. */
object OcrReceiptParser {
    private val amountPattern = Regex("(?:₹|rs\\.?|inr)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE)

    fun parse(text: String): ReceiptDraft {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val amount = amountPattern.findAll(text).map { it.groupValues[1].replace(",", "") }
            .maxByOrNull { it.toDoubleOrNull() ?: 0.0 }.orEmpty()
        val noteLine = lines.firstOrNull { it.contains("note", true) || it.contains("remark", true) }
            ?.substringAfter(":", "").orEmpty()
        val merchant = lines.firstOrNull {
            it.contains("paid to", true) || it.contains("payment to", true) || it.contains("to ", true)
        }?.replace(Regex("(?i)paid to|payment to|^to\\s*"), "")?.trim().orEmpty()
        val searchText = "$merchant $noteLine".lowercase(Locale.ROOT)
        return ReceiptDraft(amount, merchant, noteLine, categorize(searchText))
    }

    fun categorize(text: String): String = when {
        listOf("zomato", "swiggy", "restaurant", "cafe", "food", "lunch", "dinner", "grocery").any(text::contains) -> "Food"
        listOf("uber", "ola", "metro", "fuel", "petrol", "diesel", "bus").any(text::contains) -> "Transport"
        listOf("rent", "electricity", "water", "wifi", "recharge").any(text::contains) -> "Bills"
        listOf("amazon", "flipkart", "mall", "shopping").any(text::contains) -> "Shopping"
        listOf("doctor", "pharmacy", "medical", "hospital").any(text::contains) -> "Health"
        else -> "Other"
    }
}
