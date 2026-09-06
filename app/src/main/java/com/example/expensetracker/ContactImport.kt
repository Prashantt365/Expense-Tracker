package com.example.expensetracker

/** A contact offered for import, with whatever reason there is not to take it. */
data class ContactCandidate(
    val name: String,
    val existingPersonName: String? = null,
    val duplicateOfEarlierContact: Boolean = false
) {
    /** Anything flagged arrives unticked, so a duplicate takes a deliberate act to import. */
    val isFlagged: Boolean get() = existingPersonName != null || duplicateOfEarlierContact
}

/**
 * Works out which phone contacts are safe to add to the people list.
 *
 * A phone book routinely holds the same person more than once, and the people list may already
 * know some of them. Both kinds of collision are flagged rather than dropped: a false flag costs
 * one tick, whereas a missed duplicate corrupts every balance that person appears in.
 */
object ContactImportPlanner {

    fun plan(contactNames: List<String>, existingPeople: List<String>): List<ContactCandidate> {
        val accepted = mutableListOf<String>()
        return contactNames
            .map { it.trim() }
            .filter { NameMatcher.normalize(it).isNotEmpty() }
            .map { name ->
                val existing = existingPeople.firstOrNull { NameMatcher.isLikelySame(it, name) }
                val earlier = accepted.any { NameMatcher.isLikelySame(it, name) }
                if (existing == null && !earlier) accepted += name
                ContactCandidate(
                    name = name,
                    existingPersonName = existing,
                    duplicateOfEarlierContact = earlier
                )
            }
            .sortedWith(compareBy({ it.isFlagged }, { it.name.lowercase() }))
    }
}
