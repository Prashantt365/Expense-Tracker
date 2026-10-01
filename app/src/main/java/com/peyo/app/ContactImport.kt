package com.peyo.app

import com.peyo.app.data.PhoneContact

/** A contact offered for import, with whatever reason there is not to take it. */
data class ContactCandidate(
    val name: String,
    val existingPersonName: String? = null,
    val duplicateOfEarlierContact: Boolean = false,
    /** Their mobile number in E.164, when the phone book had a usable one. */
    val phone: String? = null
) {
    /** Anything flagged arrives unticked, so a duplicate takes a deliberate act to import. */
    val isFlagged: Boolean get() = existingPersonName != null || duplicateOfEarlierContact
}

/** Somebody already in the people list, as the planner needs them. */
data class KnownPerson(val name: String, val phone: String = "")

/**
 * Works out which phone contacts are safe to add to the people list.
 *
 * A phone book routinely holds the same person more than once, and the people list may already
 * know some of them. Both kinds of collision are flagged rather than dropped: a false flag costs
 * one tick, whereas a missed duplicate corrupts every balance that person appears in.
 *
 * A shared number is the surest sign of all: two entries with one mobile number are one person
 * however differently they are named, so the number is checked before the name.
 */
object ContactImportPlanner {

    fun plan(contactNames: List<String>, existingPeople: List<String>): List<ContactCandidate> =
        planContacts(contactNames.map { PhoneContact(it) }, existingPeople.map { KnownPerson(it) })

    fun planContacts(contacts: List<PhoneContact>, existingPeople: List<KnownPerson>): List<ContactCandidate> {
        // Each name is prepared once; the comparisons below then only compare.
        val existing = existingPeople.map { it to NameMatcher.prepare(it.name) }
        val existingByPhone = existingPeople.filter { it.phone.isNotBlank() }.associateBy { it.phone }
        val accepted = mutableListOf<NameMatcher.Prepared>()
        val acceptedPhones = HashSet<String>()
        return contacts
            .map { it.copy(name = it.name.trim()) }
            .filter { NameMatcher.normalize(it.name).isNotEmpty() }
            // The same name spelled identically twice -- a Google and a SIM copy of one contact --
            // is one choice, not two. The picker keys and ticks rows by name, so two identical
            // rows crashed it outright, and ticking one would have ticked both anyway. The copy
            // with a number is the one kept.
            .groupBy { it.name }
            .map { (_, copies) -> copies.firstOrNull { it.phone != null } ?: copies.first() }
            .map { contact ->
                val prepared = NameMatcher.prepare(contact.name)
                val match = contact.phone?.let { existingByPhone[it]?.name }
                    ?: existing.firstOrNull { NameMatcher.isLikelySame(it.second, prepared) }?.first?.name
                val earlier = (contact.phone != null && contact.phone in acceptedPhones) ||
                    accepted.any { NameMatcher.isLikelySame(it, prepared) }
                if (match == null && !earlier) {
                    accepted += prepared
                    contact.phone?.let(acceptedPhones::add)
                }
                ContactCandidate(
                    name = contact.name,
                    existingPersonName = match,
                    duplicateOfEarlierContact = earlier,
                    phone = contact.phone
                )
            }
            .sortedWith(compareBy({ it.isFlagged }, { it.name.lowercase() }))
    }
}
