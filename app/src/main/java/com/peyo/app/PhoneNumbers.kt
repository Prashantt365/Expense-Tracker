package com.peyo.app

/**
 * Mobile numbers, in the one form they are stored and matched in.
 *
 * A number is what links an expense on one person's phone to the account of the person it names,
 * so the same number has to come out the same however it was typed: "+91 98765 43210",
 * "098765-43210", "9876543210" and "0091 9876543210" are all one person. Everything is reduced to
 * E.164 -- a plus, the country code, then the number -- and only that form is ever stored or
 * compared, on this phone and on the server alike.
 *
 * The number itself must be exactly ten digits once the country code, a leading trunk zero and any
 * spaces or punctuation are taken off. A number typed with no country code is taken to be Indian.
 */
object PhoneNumbers {

    const val DEFAULT_COUNTRY_CODE = "91"
    private const val NATIONAL_DIGITS = 10

    /** Spacing and punctuation people put in numbers, none of which changes the number. */
    private val separators = Regex("[\\s\\-().\\u00A0\\u2011\\u2012\\u2013]")

    /**
     * [raw] as E.164 ("+919876543210"), or null when it is not a ten-digit mobile number with an
     * optional country code.
     */
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var text = raw.trim().replace(separators, "")
        // The international call prefix is another way of writing the plus.
        if (text.startsWith("00")) text = "+" + text.removePrefix("00")

        if (text.startsWith("+")) {
            val digits = text.substring(1)
            if (digits.isEmpty() || !digits.all(Char::isDigit) || digits.startsWith("0")) return null
            // The code is read off the front by the shape of the numbering plan, not taken as
            // whatever is left over: "+91 98765 4321" is an Indian number a digit short, not a
            // ten-digit number under a country code "9" that does not exist.
            val codeLength = countryCodeLength(digits)
            val code = digits.take(codeLength)
            val national = digits.drop(codeLength)
            if (national.length != NATIONAL_DIGITS) return null
            return "+$code$national"
        }

        if (!text.all(Char::isDigit)) return null
        val national = when {
            text.length == NATIONAL_DIGITS -> text
            // A leading 0 is the trunk prefix dialled inside the country: 098765 43210.
            text.length == NATIONAL_DIGITS + 1 && text.startsWith("0") -> text.drop(1)
            // The country code typed without its plus: 91 98765 43210.
            text.length == NATIONAL_DIGITS + DEFAULT_COUNTRY_CODE.length &&
                text.startsWith(DEFAULT_COUNTRY_CODE) -> text.drop(DEFAULT_COUNTRY_CODE.length)
            else -> return null
        }
        return "+$DEFAULT_COUNTRY_CODE$national"
    }

    fun isValid(raw: String?): Boolean = normalize(raw) != null

    /**
     * Country codes are prefix-free (ITU-T E.164), so how long one is can be told from its first
     * digits alone: 1 and 7 stand on their own, the codes below are two digits, and every other
     * code is three.
     */
    private val twoDigitCodes = setOf(
        "20", "27", "30", "31", "32", "33", "34", "36", "39", "40", "41", "43", "44", "45", "46",
        "47", "48", "49", "51", "52", "53", "54", "55", "56", "57", "58", "60", "61", "62", "63",
        "64", "65", "66", "81", "82", "84", "86", "90", "91", "92", "93", "94", "95", "98"
    )

    private fun countryCodeLength(digits: String): Int = when {
        digits.first() == '1' || digits.first() == '7' -> 1
        digits.take(2) in twoDigitCodes -> 2
        else -> 3
    }

    /** "+91 98765 43210", for showing a stored number back to somebody. */
    fun display(e164: String?): String {
        if (e164.isNullOrBlank()) return ""
        val digits = e164.removePrefix("+")
        if (digits.length <= NATIONAL_DIGITS) return e164
        val code = digits.dropLast(NATIONAL_DIGITS)
        val national = digits.takeLast(NATIONAL_DIGITS)
        return "+$code ${national.take(5)} ${national.drop(5)}"
    }

    /** What to tell somebody whose number was refused. */
    const val INVALID_MESSAGE =
        "Enter a 10-digit mobile number. A country code such as +91 is optional."
}
