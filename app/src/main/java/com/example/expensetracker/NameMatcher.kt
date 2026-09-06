package com.example.expensetracker

import java.text.Normalizer
import java.util.Locale

/**
 * Decides whether two people's names refer to the same person.
 *
 * A phone book is full of near-duplicates: the same person saved twice, once with a surname and
 * once without, once with an honorific, once with a stray emoji. Importing contacts blindly would
 * fill the people list with those, so every candidate is compared against what is already stored
 * and against the rest of the import.
 */
object NameMatcher {

    private val honorifics = setOf("mr", "mrs", "ms", "miss", "dr", "prof", "shri", "smt", "sri")
    private val noise = Regex("[^\\p{L}\\p{N} ]")
    private val spaces = Regex("\\s+")

    /** Case, accents, punctuation, honorifics and stray spacing all removed. */
    fun normalize(name: String): String {
        val stripped = Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace(noise, " ")
            .replace(spaces, " ")
            .trim()
        return stripped.split(' ')
            .filter { it.isNotBlank() && it !in honorifics }
            .joinToString(" ")
    }

    private fun tokens(name: String): List<String> =
        normalize(name).split(' ').filter { it.isNotBlank() }

    /**
     * True when the two names are close enough to be worth flagging as the same person.
     *
     * Deliberately generous: a false flag costs one tick in the import list, whereas a missed
     * duplicate quietly corrupts every balance that person appears in.
     */
    fun isLikelySame(a: String, b: String, threshold: Float = 0.85f): Boolean {
        val left = tokens(a)
        val right = tokens(b)
        if (left.isEmpty() || right.isEmpty()) return false
        if (left == right) return true

        // "Sharma Rahul" is the same person as "Rahul Sharma".
        if (left.sorted() == right.sorted()) return true

        // "Rahul S" against "Rahul Sharma": every token of the shorter name either matches or is
        // an initial of its counterpart.
        if (initialsMatch(left, right) || initialsMatch(right, left)) return true

        return similarity(left.joinToString(" "), right.joinToString(" ")) >= threshold
    }

    private fun initialsMatch(short: List<String>, long: List<String>): Boolean {
        if (short.size > long.size || short.isEmpty()) return false
        // The leading token must be a real match; initials alone are far too weak on their own.
        if (short.first() != long.first()) return false
        return short.zip(long).all { (s, l) -> s == l || (s.length == 1 && l.startsWith(s)) }
    }

    /** Levenshtein distance expressed as a 0..1 similarity, for typos and misspellings. */
    fun similarity(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        val distance = levenshtein(a, b)
        val longest = maxOf(a.length, b.length)
        return 1f - distance.toFloat() / longest
    }

    private fun levenshtein(a: String, b: String): Int {
        // Only the previous row is needed, so the whole matrix never has to be held.
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
