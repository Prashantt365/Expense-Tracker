package com.peyo.app

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
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
 * Statements are read as records rather than as lines. A bank statement puts a whole transaction
 * on one line, but a Google Pay statement spreads one across three -- date above time, payee above
 * the reference, amount off in its own column -- so a line-at-a-time reading finds nothing. A line
 * beginning with a date opens a record, everything after it joins that record, and the fields are
 * pulled from the record as a whole.
 */
object StatementParser {

    private const val MONTHS =
        "jan|feb|mar|apr|may|jun|jul|aug|sept?|oct|nov|dec|" +
            "january|february|march|april|june|july|august|september|october|november|december"

    /** "05/09/2026", "05-Sep-2026", "5 Sep 2026", "03 Aug, 2026". */
    private val leadingDate = Regex(
        "^\\s*(\\d{1,2}[-/.\\s]+(?:\\d{1,2}|[A-Za-z]{3,9})\\.?,?[-/.\\s]+\\d{2,4})",
        RegexOption.IGNORE_CASE
    )

    /**
     * A currency marker makes even a bare integer safe to read as money: "₹80", "Rs. 1,250.50".
     *
     * "Rs" and "INR" must start a word, or the tail of "Cars24" would read as ₹24. The Cr/Dr
     * suffix must end one, or the "CR" of a payee such as "CROMA" would turn a purchase into a credit.
     */
    private val taggedAmount = Regex(
        "(?:₹|\\b(?:rs\\.?|inr))\\s*(\\d[\\d,]*(?:\\.\\d{1,2})?)(\\s*(?:cr|dr)\\b)?",
        RegexOption.IGNORE_CASE
    )

    /**
     * Without a currency marker, money has to be told apart from reference numbers, so a grouping
     * comma or two decimal places is required. That keeps "UPI Transaction ID: 127297424577" and
     * an account tail such as "6254" out of the amount column.
     *
     * A figure touching another dot-separated number is part of something else: "05.09" is the
     * front of the date "05.09.2026", not ₹5.09.
     */
    private val plainAmount = Regex(
        "(?<![\\d.])(\\d{1,3}(?:,\\d{2,3})+(?:\\.\\d{1,2})?|\\d+\\.\\d{2})(?!\\.?\\d)(\\s*(?:cr|dr)\\b)?",
        RegexOption.IGNORE_CASE
    )

    private val timeOfDay = Regex("\\b(\\d{1,2}):(\\d{2})(?::\\d{2})?\\s*(am|pm)?\\b", RegexOption.IGNORE_CASE)

    /** "Paid to Rapido", "Received from Sonu gupta". */
    private val payee = Regex(
        "^(?:paid to|payment to|money sent to|sent to|received from|paid|to)\\b[:\\s]+(.+)$",
        RegexOption.IGNORE_CASE
    )

    private val creditWords = listOf(
        "received from", "credit", "refund", "cashback", "reversal", "salary", "interest", "deposit"
    )

    /** A statement period such as "01 August 2026 - 31 August 2026" is not a transaction date. */
    private val dateRange = Regex(
        "\\d{1,2}\\s+(?:$MONTHS)\\.?,?\\s+\\d{4}\\s*[-\u2013\u2014]\\s*\\d{1,2}\\s+(?:$MONTHS)",
        RegexOption.IGNORE_CASE
    )

    /** Page furniture, column headings and summary tiles: never transactions. */
    private val skipLines = listOf(
        "\\btransaction statement\\b",
        "\\bstatement period\\b",
        "\\bgoogle pay\\b",
        // The "Sent ₹46,821.76" summary tile, as against a "Sent to <name>" transaction.
        "^\\s*(?:sent|received)\\s*(?:₹|rs\\.?|inr)",
        "^\\s*date\\s*&\\s*time\\b",
        "^\\s*transaction details\\s*$",
        "^\\s*amount\\s*$",
        "^\\s*(?:date|txn date|value date|particulars|description|narration|remarks)\\b",
        "\\b(?:opening|closing)\\s+balance\\b",
        "^\\s*(?:total|sub\\s*total|grand total|balance b/f|balance c/f)\\b",
        "^\\s*page\\s+\\d+",
        "\\b(?:account|a/c)\\s+(?:number|no|statement)\\b",
        "^\\s*(?:ifsc|micr|branch|customer id|address)\\b",
        // The header line carrying the account holder's phone and email.
        "^[\\d\\s,+()-]*\\S+@\\S+\\.\\S+\\s*$"
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    /** Lines inside a record that describe it but are not its payee. */
    private val supporting = listOf(
        "\\bupi transaction id\\b",
        "\\btransaction id\\b",
        "\\bgoogle transaction\\b",
        "\\butr\\b",
        "\\bref(?:erence)?\\s*(?:no|id|number)\\b",
        "^\\s*paid by\\b",
        "\\bbank\\b"
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    private val dateFormats = listOf(
        "d/M/uuuu", "d-M-uuuu", "d.M.uuuu", "d/M/uu", "d-M-uu",
        "d MMM uuuu", "d-MMM-uuuu", "d MMM uu", "d-MMM-uu", "d MMMM uuuu"
    ).map { pattern ->
        DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern(pattern)
            // Two-digit years in a statement are this century, not the 1920s.
            .parseDefaulting(ChronoField.ERA, 1)
            .toFormatter(Locale.ENGLISH)
    }

    /** Beyond this a record has clearly run past its transaction, so it is closed off. */
    private const val MAX_RECORD_LINES = 8

    private class Record(val dateText: String?) {
        val lines = mutableListOf<String>()
    }

    fun parse(
        text: String,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone)
    ): List<StatementRow> {
        val rows = mutableListOf<StatementRow>()
        var open: Record? = null

        fun flush() {
            open?.let { record -> finish(record, zone, today)?.let(rows::add) }
            open = null
        }

        text.lines().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
            if (skipLines.any { it.containsMatchIn(line) } || dateRange.containsMatchIn(line)) {
                flush()
                return@forEach
            }
            val match = leadingDate.find(line)
            when {
                match != null -> {
                    flush()
                    open = Record(match.groupValues[1]).apply { lines += line }
                }
                open != null -> {
                    val record = open!!
                    record.lines += line
                    if (record.lines.size >= MAX_RECORD_LINES) flush()
                }
                // A statement without dates still yields rows if a line carries both a figure and
                // something to recognise it by; the user can supply the date in review.
                else -> Record(null).apply { lines += line }.let { orphan ->
                    finish(orphan, zone, today)?.let(rows::add)
                }
            }
        }
        flush()
        return rows
    }

    private fun finish(record: Record, zone: ZoneId, today: LocalDate): StatementRow? {
        val joined = record.lines.joinToString(" ")
        // The date that opened the record is never money, whatever shape its digits take.
        val money = record.dateText?.let { joined.replaceFirst(it, " ") } ?: joined

        val tagged = taggedAmount.findAll(money).toList()
        val figures = tagged.ifEmpty { plainAmount.findAll(money).toList() }
        if (figures.isEmpty()) return null

        // A bank row prints the running balance last, so with more than one figure the trailing
        // one is dropped. A Google Pay record only ever carries the single amount.
        val candidates = if (figures.size > 1) figures.dropLast(1) else figures
        val chosen = candidates.firstOrNull { paise(it.groupValues[1]).let { p -> p != null && p > 0 } }
            ?: return null
        val amountPaise = paise(chosen.groupValues[1])?.takeIf { it > 0 } ?: return null

        val description = describe(record, figures) ?: return null

        return StatementRow(
            date = record.dateText?.let { toEpochMillis(it, joined, zone, today) },
            description = description,
            amountPaise = amountPaise,
            isCredit = isCredit(joined, chosen),
            rawLine = joined
        )
    }

    /**
     * The payee, taken from whichever line of the record names it. Reference numbers, the funding
     * bank and the time of day describe the transaction but do not identify it.
     */
    private fun describe(record: Record, figures: List<MatchResult>): String? {
        val cleaned = record.lines.map { line ->
            var stripped = line
            figures.forEach { stripped = stripped.replace(it.value, " ") }
            record.dateText?.let { stripped = stripped.replace(it, " ") }
            stripped.replace(timeOfDay, " ")
                .replace(Regex("[|\\t]+"), " ")
                .replace(Regex("\\s{2,}"), " ")
                .trim()
                .trim('-', '.', ',', ':')
                .trim()
        }

        cleaned.firstNotNullOfOrNull { line -> payee.find(line)?.groupValues?.get(1)?.trim() }
            ?.takeIf { it.count(Char::isLetter) >= 2 }
            ?.let { return it }

        return cleaned.firstOrNull { line ->
            line.count(Char::isLetter) >= 3 && supporting.none { it.containsMatchIn(line) }
        }
    }

    private fun isCredit(joined: String, chosen: MatchResult): Boolean {
        chosen.groupValues.getOrNull(2)?.trim()?.lowercase(Locale.ROOT)?.let { suffix ->
            if (suffix == "cr") return true
            if (suffix == "dr") return false
        }
        val lower = joined.lowercase(Locale.ROOT)
        // "Paid to" wins outright: a payment to someone called "Credit Union" is still a payment.
        if (Regex("\\bpaid to\\b|\\bsent to\\b").containsMatchIn(lower)) return false
        return creditWords.any { lower.contains(it) }
    }

    private fun paise(raw: String): Long? = runCatching {
        BigDecimal(raw.replace(",", ""))
            .movePointRight(2)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    }.getOrNull()

    private fun toEpochMillis(raw: String, joined: String, zone: ZoneId, today: LocalDate): Long? {
        // "Sept" is a common abbreviation that MMM does not accept, and a month may carry a
        // full stop ("5 Sep. 2026") that no pattern expects.
        val cleaned = raw.trim().replace(",", " ")
            .replace(Regex("\\bsept\\b", RegexOption.IGNORE_CASE), "Sep")
            .replace(Regex("(?<=[A-Za-z])\\."), "")
            .replace(Regex("\\s{2,}"), " ")
        val date = dateFormats.firstNotNullOfOrNull { formatter ->
            runCatching { LocalDate.parse(cleaned, formatter) }.getOrNull()
                ?: runCatching { LocalDate.parse(cleaned.replace('.', '/'), formatter) }.getOrNull()
                ?: runCatching { LocalDate.parse(cleaned.replace('/', '-'), formatter) }.getOrNull()
                ?: runCatching { LocalDate.parse(cleaned.replace('/', ' '), formatter) }.getOrNull()
        } ?: return null

        // A statement cannot describe the future; a misread year is the likelier reading.
        if (date.isAfter(today)) return null

        val time = timeOfDay.find(joined)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@let null
            val minute = match.groupValues[2].toIntOrNull() ?: return@let null
            val meridiem = match.groupValues[3].lowercase(Locale.ROOT)
            val adjusted = when {
                meridiem == "pm" && hour < 12 -> hour + 12
                meridiem == "am" && hour == 12 -> 0
                else -> hour
            }
            runCatching { LocalTime.of(adjusted, minute) }.getOrNull()
        } ?: LocalTime.MIDNIGHT

        return date.atTime(time).atZone(zone).toInstant().toEpochMilli()
    }
}
