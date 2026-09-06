package com.example.expensetracker

import java.util.Locale

data class ReceiptDraft(
    val amount: String = "",
    val merchant: String = "",
    val note: String = "",
    val category: String = "Other"
)

/**
 * Conservative parser for payment-receipt screenshots, tuned for Google Pay.
 * It only proposes values; the user always confirms them before anything is stored.
 *
 * Google Pay lays a receipt out as a handful of unlabelled lines -- the amount, the payee and
 * the message the payer typed all arrive as bare text -- so each field is found by structure
 * and position rather than by a keyword.
 */
object OcrReceiptParser {

    /** "1,250.50", "Rs. 400", "INR 90" with an explicit currency marker in front. */
    private val currencyAmount =
        Regex("(?:\u20B9|rs\\.?|inr)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE)

    /**
     * A line that is nothing but a number, allowing a single leading symbol for a currency glyph
     * ML Kit's Latin model failed to recognise. Exactly one symbol is what separates a headline
     * amount ("\u20B9450", "*450") from a masked account tail ("\u2022\u20225678").
     */
    private val standaloneAmount =
        Regex("^[^\\p{L}\\p{N}\\s]?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)$")

    private val merchantInline =
        Regex("^(?:paid to|payment to|money sent to|sent to|to)[:\\s]+(.+)$", RegexOption.IGNORE_CASE)
    private val merchantLabel =
        Regex("^(?:paid to|payment to|money sent to|sent to|to)$", RegexOption.IGNORE_CASE)
    private val noteLabel =
        Regex("^(?:note|message|remarks?|description|for)[:\\s]+(.+)$", RegexOption.IGNORE_CASE)

    private val vpa = Regex("^\\S+@\\S+$")
    private val maskedAccount = Regex("[\u2022*]{2,}|x{3,}\\s*\\d", RegexOption.IGNORE_CASE)
    private val dateLike = Regex(
        "\\d{1,2}\\s+[A-Za-z]{3,9}\\.?,?\\s+\\d{2,4}|\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}|\\d{1,2}[:.]\\d{2}\\s*(?:am|pm)",
        RegexOption.IGNORE_CASE
    )

    /**
     * Receipt chrome: never an amount, a payee, or the payer's own message.
     *
     * ML Kit returns text blocks in roughly, but not strictly, top-to-bottom order, so an action
     * button rendered at the bottom of the receipt can arrive ahead of the payer's note. Filtering
     * the buttons out by name is what keeps a short note like "me" from losing to "Pay again".
     * Button labels are matched as whole lines wherever possible, so a note that merely starts with
     * one of these words ("Share of the cab") still survives.
     */
    private val boilerplate = listOf(
        "^(?:completed|pending|failed|processing|cancell?ed|payment successful|successful|success)\\b",
        "\\bupi\\b",
        "\\btransaction id\\b",
        "\\bgoogle transaction\\b",
        "\\butr\\b",
        "\\bref(?:erence)?\\s*(?:no|id|number)\\b",
        "^(?:from|to|paid to|payment to|sent to|money sent to)\\b",
        "\\bbank\\b",
        "\\baccount\\b",
        "\\ba/c\\b",
        "\\bbalance\\b",
        // Google Pay action buttons.
        "^(?:pay|send|request|order)\\s+again\\b",
        "^(?:split expense|split bill|share receipt|view details|see details|show more|show less)$",
        "^(?:view|share|download|print)\\s+(?:receipt|details|invoice|statement)\\b",
        "^(?:contact|message|call)\\s+\\S+$",
        "^(?:get help|need help|help|report an issue|report a problem|something went wrong)\\b",
        "^(?:rate|review)\\s+(?:this|your)\\b",
        "^(?:done|close|ok|okay|cancel|back|retry|repeat)$",
        "^(?:transaction|payment)\\s+details$",
        "^(?:money (?:sent|received)|you (?:paid|sent|received))\\b",
        "^add (?:to contacts|a note|note)$",
        "^(?:new payment|scan any qr|self transfer|check balance)\\b"
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    private class Candidate(val index: Int, val value: String) {
        val numeric: Double = value.toDoubleOrNull() ?: 0.0
    }

    fun parse(text: String): ReceiptDraft {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        // Each field claims the line it came from so the next one cannot reuse it.
        val claimed = mutableSetOf<Int>()
        val amount = findAmount(lines, claimed)
        val merchant = findMerchant(lines, claimed)
        val note = findNote(lines, claimed)
        return ReceiptDraft(amount, merchant, note, categorize("$merchant $note".lowercase(Locale.ROOT)))
    }

    private fun findAmount(lines: List<String>, claimed: MutableSet<Int>): String {
        val tagged = mutableListOf<Candidate>()
        val bare = mutableListOf<Candidate>()
        lines.forEachIndexed { index, line ->
            val matches = currencyAmount.findAll(line).toList()
            if (matches.isNotEmpty()) {
                matches.forEach { tagged += Candidate(index, it.groupValues[1].replace(",", "")) }
                return@forEachIndexed
            }
            val raw = standaloneAmount.find(line)?.groupValues?.get(1) ?: return@forEachIndexed
            if (!looksLikeIdentifier(raw) && !followsIdentifierLabel(lines, index)) {
                bare += Candidate(index, raw.replace(",", ""))
            }
        }
        // A currency-tagged figure is trustworthy, and on an itemised bill the largest one is the
        // total. With no currency symbol anywhere the first bare number wins instead: that is the
        // headline amount Google Pay prints above everything else.
        val chosen = tagged.maxByOrNull { it.numeric } ?: bare.firstOrNull() ?: return ""
        claimed += chosen.index
        return chosen.value
    }

    /** UPI references and order numbers are long unbroken digit runs; prices carry a "," or ".". */
    private fun looksLikeIdentifier(raw: String): Boolean =
        !raw.contains(',') && !raw.contains('.') && raw.length >= 7

    private fun followsIdentifierLabel(lines: List<String>, index: Int): Boolean {
        val previous = lines.getOrNull(index - 1)?.lowercase(Locale.ROOT) ?: return false
        return listOf("transaction id", "reference", "ref no", "utr", "order", "account", "a/c")
            .any(previous::contains)
    }

    private fun findMerchant(lines: List<String>, claimed: MutableSet<Int>): String {
        lines.forEachIndexed { index, line ->
            if (index in claimed) return@forEachIndexed
            merchantInline.find(line)?.let { match ->
                val name = cleanName(match.groupValues[1])
                if (name.isNotEmpty()) {
                    claimed += index
                    return name
                }
            }
            if (merchantLabel.matches(line)) {
                // "Paid to" on a line of its own puts the payee on the next one.
                val next = lines.getOrNull(index + 1)?.let(::cleanName).orEmpty()
                if (next.isNotEmpty()) {
                    claimed += index
                    claimed += index + 1
                    return next
                }
            }
        }
        // Person-to-person receipts print the payee as a bare name under the amount, with no label.
        lines.forEachIndexed { index, line ->
            if (index in claimed || !isFreeText(line)) return@forEachIndexed
            val name = cleanName(line)
            if (name.isNotEmpty()) {
                claimed += index
                return name
            }
        }
        return ""
    }

    private fun findNote(lines: List<String>, claimed: MutableSet<Int>): String {
        lines.forEachIndexed { index, line ->
            noteLabel.find(line)?.let { match ->
                val note = match.groupValues[1].trim()
                if (note.isNotEmpty()) {
                    claimed += index
                    return note
                }
            }
        }
        // Google Pay renders the payer's message as a bare line, so take the first line that is
        // neither receipt chrome nor already claimed as the amount or the payee.
        lines.forEachIndexed { index, line ->
            if (index !in claimed && isFreeText(line)) {
                claimed += index
                return line
            }
        }
        return ""
    }

    private fun cleanName(raw: String): String {
        val name = raw.trim().trim('-', '\u2013', '\u2022', ':', ',', '.').trim()
        if (name.length !in 2..60) return ""
        if (name.count { it.isLetter() } < 2) return ""
        if (maskedAccount.containsMatchIn(name)) return ""
        if (boilerplate.any { it.containsMatchIn(name) }) return ""
        // "swiggy@ybl" is a UPI handle rather than a display name; the part before "@" is closer.
        return if (vpa.matches(name)) name.substringBefore('@') else name
    }

    private fun isFreeText(line: String): Boolean {
        if (line.length !in 2..80) return false
        if (line.count { it.isLetter() } < 2) return false
        if (vpa.matches(line) || maskedAccount.containsMatchIn(line)) return false
        if (dateLike.containsMatchIn(line)) return false
        return boilerplate.none { it.containsMatchIn(line) }
    }

    fun categorize(text: String): String = when {
        listOf(
            "zomato", "swiggy", "instamart", "zepto", "blinkit", "bigbasket", "dunzo", "restaurant",
            "cafe", "coffee", "chai", "bakery", "pizza", "burger", "dominos", "mcdonald", "starbucks",
            "food", "lunch", "dinner", "breakfast", "grocery", "kirana"
        ).any(text::contains) -> "Food"

        listOf(
            "uber", "ola", "rapido", "metro", "irctc", "railway", "indigo", "fuel", "petrol",
            "diesel", "bus", "cab", "taxi", "toll", "parking"
        ).any(text::contains) -> "Transport"

        listOf(
            "rent", "electricity", "water", "wifi", "broadband", "recharge", "airtel", "jio",
            "vodafone", "gas", "dth", "bill", "insurance", "emi"
        ).any(text::contains) -> "Bills"

        listOf(
            "amazon", "flipkart", "myntra", "ajio", "meesho", "nykaa", "mall", "shopping", "store"
        ).any(text::contains) -> "Shopping"

        listOf(
            "doctor", "pharmacy", "chemist", "medical", "medicine", "hospital", "clinic",
            "apollo", "diagnostic", "lab"
        ).any(text::contains) -> "Health"

        else -> "Other"
    }
}
