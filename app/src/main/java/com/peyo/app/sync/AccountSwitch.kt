package com.peyo.app.sync

import org.json.JSONObject
import java.io.File

/**
 * What every local row is called after the phone moves from one account to another, and what each
 * account is left remembering about it.
 *
 * A row's remote id is the primary key of a table every account shares, so the ids this phone
 * pushed under the previous account are already taken on the server. Pushing them again under the
 * new account is an update of somebody else's row, which row level security refuses -- and people
 * go first, so that one refusal used to stop every table syncing for good. Rows carried into a new
 * account therefore get ids of their own there.
 *
 * Minting fresh ones every time would turn switching back into copying: signing into the wrong
 * account by mistake and straight back out would push every row to the right one a second time,
 * beside the originals it still holds. So the id each row had in the account being left is
 * remembered, and handed back if the phone ever returns to it.
 */
internal data class Reassignment(
    /** Each row's current remote id, mapped to the one it has from now on. */
    val ids: Map<String, String>,
    /** Per account: a row's id from now on, mapped to the id that row has in that account. */
    val memory: Map<String, Map<String, String>>
)

internal fun reassignIds(
    current: Collection<String>,
    memory: Map<String, Map<String, String>>,
    from: String,
    to: String,
    mint: () -> String
): Reassignment {
    val returning = memory[to].orEmpty()
    val given = HashSet<String>()
    val ids = LinkedHashMap<String, String>()
    for (id in current) {
        // A remembered id is only handed out once, since two rows cannot share one.
        val remembered = returning[id]?.takeIf { given.add(it) }
        ids[id] = remembered ?: mint().also { given.add(it) }
    }

    val next = HashMap<String, Map<String, String>>()
    for ((owner, known) in memory) {
        // The account arrived at has just had its ids handed back, and the one being left is
        // written afresh below; every other account's memory follows the rows to their new names.
        if (owner == to || owner == from) continue
        val rekeyed = known.entries.mapNotNull { (old, there) -> ids[old]?.let { it to there } }.toMap()
        if (rekeyed.isNotEmpty()) next[owner] = rekeyed
    }
    // Every row was pushed to the account being left under the id it carried until now.
    next[from] = ids.entries.associate { (old, new) -> new to old }
    return Reassignment(ids, next)
}

/** [Reassignment.memory], kept in a private file so it outlives the process. */
internal class IdMemory(private val file: File) {

    fun load(): Map<String, Map<String, String>> {
        val json = runCatching { file.readText().asJsonObject() }.getOrNull() ?: return emptyMap()
        return json.keys().asSequence().associateWith { owner ->
            val known = json.optJSONObject(owner) ?: JSONObject()
            known.keys().asSequence().associateWith { known.optString(it) }
        }
    }

    fun save(memory: Map<String, Map<String, String>>) {
        val json = JSONObject()
        memory.forEach { (owner, known) -> json.put(owner, JSONObject(known)) }
        // Written beside and then moved over, so a process killed mid-write leaves the old memory
        // rather than half of a new one.
        val staging = File(file.parentFile, file.name + ".tmp")
        staging.writeText(json.toString())
        if (!staging.renameTo(file)) {
            file.delete()
            staging.renameTo(file)
        }
    }
}
