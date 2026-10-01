package com.peyo.app

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

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
     * A name normalised and split once, ready to be compared against many others.
     *
     * Importing contacts sets every name against every one already accepted, so normalising
     * inside each comparison ran the regexes and the Unicode decomposition hundreds of thousands
     * of times for an ordinary phone book -- seconds on a phone, enough to freeze the import.
     */
    class Prepared internal constructor(internal val tokens: List<String>) {
        internal val sorted: List<String> = tokens.sorted()
        internal val joined: String = tokens.joinToString(" ")
    }

    fun prepare(name: String): Prepared = Prepared(tokens(name))

    /**
     * True when the two names are close enough to be worth flagging as the same person.
     *
     * Deliberately generous: a false flag costs one tick in the import list, whereas a missed
     * duplicate quietly corrupts every balance that person appears in.
     */
    fun isLikelySame(a: String, b: String, threshold: Float = 0.85f): Boolean =
        isLikelySame(prepare(a), prepare(b), threshold)

    fun isLikelySame(a: Prepared, b: Prepared, threshold: Float = 0.85f): Boolean {
        val left = a.tokens
        val right = b.tokens
        if (left.isEmpty() || right.isEmpty()) return false
        if (left == right) return true

        // "Sharma Rahul" is the same person as "Rahul Sharma".
        if (a.sorted == b.sorted) return true

        // "Rahul S" against "Rahul Sharma": every token of the shorter name either matches or is
        // an initial of its counterpart.
        if (initialsMatch(left, right) || initialsMatch(right, left)) return true

        // The edit distance is at least the difference in length, so a pair too far apart in
        // length can never reach the threshold. Ruling that out first skips the quadratic
        // comparison for most pairs without changing a single answer.
        val longest = maxOf(a.joined.length, b.joined.length)
        // The most edits that still pass the threshold, worked out with the same float arithmetic
        // as similarity() so a name exactly on the boundary is judged the same way it always was.
        var allowed = ((1f - threshold) * longest).toInt() + 1
        while (allowed > 0 && 1f - allowed.toFloat() / longest < threshold) allowed--
        if (abs(a.joined.length - b.joined.length) > allowed) return false

        // Same test as similarity() >= threshold, but the distance stops being worked out as soon
        // as it is known to be over the limit, which for two unrelated names is within a few rows.
        return boundedLevenshtein(a.joined, b.joined, allowed) <= allowed
    }

    /**
     * The edit distance between [a] and [b] when it is at most [limit], and something larger than
     * [limit] otherwise. Every cell in a row is at least that row's minimum, and later rows never
     * fall below it, so once a whole row is over the limit the answer cannot come back under it.
     */
    private fun boundedLevenshtein(a: String, b: String, limit: Int): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            var rowMin = current[0]
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
                if (current[j] < rowMin) rowMin = current[j]
            }
            if (rowMin > limit) return limit + 1
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
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
