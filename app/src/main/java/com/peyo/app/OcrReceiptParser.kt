package com.peyo.app

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

    /** A line holding a number and nothing else: a headline amount, or an account tail. */
    private val bareAmount = Regex("^([0-9][0-9,]*(?:\\.[0-9]{1,2})?)$")

    /**
     * A number wearing a single leading character, which on a Google Pay receipt is the rupee
     * glyph ML Kit failed to read. The oversized headline defeats the Latin model in both
     * directions -- it comes back as a stray symbol ("*450") but just as often as a stray letter
     * ("z182", "R182").
     *
     * We explicitly exclude digits here to avoid consuming the first digit of a clean amount.
     */
    private val glyphedAmount = Regex("^([^0-9\\s])\\s?([0-9][0-9,]*(?:\\.[0-9]{1,2})?)$")

    /**
     * The amount restated in the confirmation sentence of the expanded details card,
     * "Payment of ₹182 completed". It is a second reading of the same figure, set small
     * enough that OCR tends to get it right on the receipts where the headline glyph defeats it.
     */
    private val paymentPhrase = Regex(
        "\\b(?:payment|transfer|sent)\\s+of\\s+[^0-9\\s]{0,3}([0-9][0-9,]*(?:\\.[0-9]{1,2})?)(?!\\d)",
        RegexOption.IGNORE_CASE
    )

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

    private data class Candidate(val value: String, val score: Int, val index: Int) {
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
        val candidates = mutableListOf<Candidate>()

        lines.forEachIndexed { index, line ->
            // 1. Amounts with explicit symbols: "₹182", "Rs. 450"
            currencyAmount.findAll(line).forEach { match ->
                val clean = match.groupValues[1].replace(",", "")
                if (!followsIdentifierLabel(lines, index, clean)) {
                    candidates += Candidate(clean, 100, index)
                }
            }

            // 2. Amounts in confirmation phrases: "Payment of ₹182 completed"
            paymentPhrase.findAll(line).forEach { match ->
                val clean = match.groupValues[1].replace(",", "")
                if (!followsIdentifierLabel(lines, index, clean)) {
                    candidates += Candidate(clean, 90, index)
                }
            }

            // 3. Glyphed or Bare numbers: "z182", "7182", "182", "7493"
            val glyphMatch = glyphedAmount.find(line)
            val bareMatch = bareAmount.find(line)

            val raw = glyphMatch?.groupValues?.get(2) ?: bareMatch?.groupValues?.get(1)
            if (raw != null && !followsIdentifierLabel(lines, index, raw) && !looksLikeIdentifier(raw)) {
                val clean = raw.replace(",", "")
                val isHeadline = index < 5
                if (glyphMatch != null) {
                    candidates += Candidate(clean, 80, index)
                } else {
                    // Bare number.
                    candidates += Candidate(clean, if (isHeadline) 60 else 50, index)
                    // If it starts with 7 or 2 and it's 4+ digits, it might be a mangled Rupee glyph.
                    if ((raw.startsWith('7') || raw.startsWith('2')) && clean.length >= 4) {
                        val stripped = clean.substring(1)
                        if (stripped.length >= 2) {
                            // High confidence if it's a '7' (closest to Rupee glyph) or if verified by phrase later.
                            val mangledScore = if (isHeadline && raw.startsWith('7')) 70 else 10
                            candidates += Candidate(stripped, mangledScore, index)
                        }
                    }
                }
            }
        }

        // 4. Boost candidates that appear multiple times or in high-confidence contexts.
        val highConfidence = candidates.filter { it.score >= 90 }.map { it.value }.toSet()
        val finalCandidates = candidates.map { c ->
            if (c.value in highConfidence) c.copy(score = c.score + 100) else c
        }

        // We want the highest score. For tied scores, the first occurrence (headline) wins.
        val chosen = finalCandidates
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.index })
            .let { list ->
                val maxScore = list.firstOrNull()?.score ?: return ""
                if (maxScore >= 100) {
                    // On an itemised bill the largest high-confidence amount is usually the total.
                    list.filter { it.score >= 100 }.maxByOrNull { it.numeric }
                } else {
                    list.firstOrNull()
                }
            } ?: return ""

        claimed += chosen.index
        return chosen.value
    }

    /** UPI references and order numbers are long unbroken digit runs; prices carry a "," or ".". */
    private fun looksLikeIdentifier(raw: String): Boolean =
        !raw.contains(',') && !raw.contains('.') && raw.length >= 7

    /**
     * Google Pay's expanded details card heads its funding-source row with the bank name and puts
     * the account tail bare on the line below -- "Central Bank of India" over "7493", with none of
     * the masking dots that would otherwise give it away. Digits under a bank are never a price.
     *
     * We check a few lines back because the bank name might be split or preceded by "From".
     */
    private fun followsIdentifierLabel(lines: List<String>, index: Int, rawDigits: String): Boolean {
        val isLikelyBankTail = rawDigits.length == 4 && rawDigits.all { it.isDigit() }

        for (offset in 1..3) {
            val prev = lines.getOrNull(index - offset)?.lowercase(Locale.ROOT) ?: break
            // Standalone labels for identifiers.
            if (listOf(
                    "transaction id", "reference", "ref no", "utr", "order",
                    "account", "a/c", "bank", "card", "wallet", "from", "to"
                ).any { prev == it || prev == "$it:" || prev.contains("$it ID", true) }) return true
            // Bank names or cards preceding a 4-digit account tail.
            if (isLikelyBankTail && (prev.contains("bank") || prev.contains("a/c") || prev.contains("card"))) return true
        }
        return false
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
