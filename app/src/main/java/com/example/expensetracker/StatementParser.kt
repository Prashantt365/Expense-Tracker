package com.example.expensetracker

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale

/** One candidate transaction lifted out of a statement, before the user confirms it. */
data class StatementRow(
    val date: Long?,
    val description: String,
    val amountPaise: Long,
    /** Money coming in. Never an expense, so these arrive unticked. */
    val isCredit: Boolean,
    val rawLine: String
)

/**
 * Pulls transactions out of the text of a bank or UPI statement.
 *
 * Statement layouts vary by issuer, so this reads by shape rather than by column: a date near the
 * start, one or more money figures, and whatever text lies between them. Nothing is ever imported
 * on the strength of this alone -- every row goes to the user for review first.
 */
object StatementParser {

    private val dateAtStart = Regex(
        "^\\s*(\\d{1,2}[-/. ](?:\\d{1,2}|[A-Za-z]{3,9})[-/. ]\\d{2,4})",
        RegexOption.IGNORE_CASE
    )

    /** Money: an optional currency marker, thousands separators, and usually two decimals. */
    private val amount = Regex(
        "(?:₹|rs\\.?|inr)?\\s*(\\d{1,3}(?:,\\d{2,3})+(?:\\.\\d{1,2})?|\\d+\\.\\d{2})(\\s*(?:cr|dr))?",
        RegexOption.IGNORE_CASE
    )

    private val creditWords = listOf(
        "credit", "received", "refund", "cashback", "reversal", "salary", "interest", "deposit"
    )

    /** Column headings, page furniture and totals: never transactions. */
    private val skipLines = listOf(
        "^\\s*(date|txn date|value date|particulars|description|narration|remarks)\\b",
        "\\b(opening|closing)\\s+balance\\b",
        "^\\s*(total|sub\\s*total|grand total|balance b/f|balance c/f)\\b",
        "^\\s*page\\s+\\d+",
        "\\bstatement\\s+(of|period|for)\\b",
        "\\b(account|a/c)\\s+(number|no|statement)\\b",
        "^\\s*(ifsc|micr|branch|customer id|address)\\b"
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    private val dateFormats = listOf(
        "d/M/uuuu", "d-M-uuuu", "d.M.uuuu", "d/M/uu", "d-M-uu",
        "d MMM uuuu", "d-MMM-uuuu", "d MMM uu", "d-MMM-uu",
        "d MMMM uuuu"
    ).map { pattern ->
        DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern(pattern)
            // Two-digit years in a statement are this century, not 1920s.
            .parseDefaulting(ChronoField.ERA, 1)
            .toFormatter(Locale.ENGLISH)
    }

    fun parse(
        text: String,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone)
    ): List<StatementRow> = text.lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .mapNotNull { line -> parseLine(line, zone, today) }

    private fun parseLine(line: String, zone: ZoneId, today: LocalDate): StatementRow? {
        if (skipLines.any { it.containsMatchIn(line) }) return null

        val dateMatch = dateAtStart.find(line)
        val figures = amount.findAll(line).toList()
        if (figures.isEmpty()) return null

        // Statements print the running balance last on the row. With more than one figure the
        // trailing one is the balance, so it is dropped rather than imported as a transaction.
        val candidates = if (figures.size > 1) figures.dropLast(1) else figures
        val chosen = candidates.firstOrNull { paise(it.groupValues[1]) ?: 0L > 0L } ?: return null
        val amountPaise = paise(chosen.groupValues[1])?.takeIf { it > 0 } ?: return null

        val description = describe(line, dateMatch, figures)
        // Without a description there is nothing to recognise the row by; it is almost certainly
        // page furniture that slipped past the skip list.
        if (description.count { it.isLetter() } < 3) return null

        return StatementRow(
            date = dateMatch?.groupValues?.get(1)?.let { toEpochMillis(it, zone, today) },
            description = description,
            amountPaise = amountPaise,
            isCredit = isCredit(line, chosen),
            rawLine = line
        )
    }

    private fun describe(line: String, dateMatch: MatchResult?, figures: List<MatchResult>): String {
        val builder = StringBuilder(line)
        // Blank out from the right so the earlier ranges stay valid.
        figures.sortedByDescending { it.range.first }.forEach { match ->
            builder.replace(match.range.first, match.range.last + 1, " ")
        }
        dateMatch?.let { builder.replace(it.range.first, it.range.last + 1, " ") }
        return builder.toString()
            .replace(Regex("[|\\t]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .trim('-', '.', ',', ':')
            .trim()
    }

    private fun isCredit(line: String, chosen: MatchResult): Boolean {
        chosen.groupValues[2].trim().lowercase(Locale.ROOT).let { suffix ->
            if (suffix == "cr") return true
            if (suffix == "dr") return false
        }
        val lower = line.lowercase(Locale.ROOT)
        return creditWords.any { lower.contains(it) }
    }

    private fun paise(raw: String): Long? = runCatching {
        java.math.BigDecimal(raw.replace(",", ""))
            .movePointRight(2)
            .setScale(0, java.math.RoundingMode.HALF_UP)
            .longValueExact()
    }.getOrNull()

    private fun toEpochMillis(raw: String, zone: ZoneId, today: LocalDate): Long? {
        val cleaned = raw.trim().replace(Regex("[.]"), "/")
        for (formatter in dateFormats) {
            val parsed = runCatching { LocalDate.parse(cleaned, formatter) }.getOrNull()
                ?: runCatching { LocalDate.parse(cleaned.replace('/', '-'), formatter) }.getOrNull()
                ?: runCatching { LocalDate.parse(cleaned.replace('/', ' '), formatter) }.getOrNull()
            if (parsed != null) {
                // A statement cannot describe the future; a misread year is the likelier reading.
                if (parsed.isAfter(today)) return null
                return parsed.atStartOfDay(zone).toInstant().toEpochMilli()
            }
        }
        return null
    }
}
