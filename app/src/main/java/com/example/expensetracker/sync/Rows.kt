package com.example.expensetracker.sync

import com.example.expensetracker.data.Category
import com.example.expensetracker.data.Expense
import com.example.expensetracker.data.ExpenseSplit
import com.example.expensetracker.data.Person
import com.example.expensetracker.data.Synced
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Turning rows into what Postgres holds and back.
 *
 * Kept free of Room and of the network so it can be tested directly, because this is where a sync
 * goes wrong quietly: a mistyped column name is a refusal you can read, but a timestamp parsed in
 * the wrong zone is a conflict that resolves the wrong way months later.
 */
object Rows {

    /**
     * Postgres hands back timestamptz as ISO 8601 in UTC, with anywhere from zero to six digits of
     * fractional seconds depending on what was stored, so the fraction is trimmed rather than
     * matched. Times are held locally as epoch millis, which carry no zone at all.
     */
    private fun isoFormat(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun toIso(millis: Long): String = isoFormat().format(Date(millis))

    fun fromIso(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val normalised = normaliseTimestamp(text) ?: return null
        return runCatching { isoFormat().parse(normalised)?.time }.getOrNull()
    }

    /**
     * Rewrites what Postgres sends into the one shape the parser accepts: exactly three fractional
     * digits and a trailing Z. "+00:00" and a bare space separator both turn up, and a fraction can
     * be absent or as long as six digits.
     */
    internal fun normaliseTimestamp(raw: String): String? {
        var text = raw.trim().replace(' ', 'T')
        // UTC arrives written several ways depending on the client and the column: "Z", "+00",
        // "+0000" and "+00:00" are all the same instant and all turn up.
        text = text.removeSuffix("Z").replace(Regex("[+-]00(:?00)?$"), "")
        // A zone other than UTC is not something this schema ever stores, and guessing at one
        // would be worse than refusing it.
        if (Regex("[+-]\\d{2}(:?\\d{2})?$").containsMatchIn(text)) return null
        val dot = text.indexOf('.')
        val base = if (dot >= 0) text.substring(0, dot) else text
        val fraction = if (dot >= 0) text.substring(dot + 1).filter(Char::isDigit) else ""
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}$").matches(base)) return null
        return base + "." + fraction.padEnd(3, '0').take(3) + "Z"
    }

    private fun JSONObject.putTime(key: String, millis: Long?) =
        if (millis == null) put(key, JSONObject.NULL) else put(key, toIso(millis))

    /** The columns every synced table shares. */
    private fun JSONObject.putCommon(row: Synced, userId: String) = apply {
        put("id", row.remoteId)
        put("user_id", userId)
        putTime("updated_at", row.updatedAt)
        putTime("deleted_at", row.deletedAt)
    }

    fun expenseJson(expense: Expense, userId: String, currency: String): JSONObject =
        JSONObject().putCommon(expense, userId).apply {
            put("amount_minor", expense.amountPaise)
            put("currency", currency)
            put("category", expense.category)
            put("note", expense.note)
            put("merchant", expense.merchant)
            putTime("paid_at", expense.paidAt)
        }

    fun personJson(person: Person, userId: String): JSONObject =
        JSONObject().putCommon(person, userId).apply {
            put("name", person.name)
            put("note", person.note)
        }

    fun categoryJson(category: Category, userId: String): JSONObject =
        JSONObject().putCommon(category, userId).apply {
            put("name", category.name)
            put("sort_order", category.sortOrder)
        }

    /**
     * A split points at its expense and its person by their remote ids, since the local numbering
     * means nothing on the server. A split whose expense has no remote id yet cannot be sent.
     */
    fun splitJson(
        split: ExpenseSplit,
        userId: String,
        expenseRemoteId: String,
        personRemoteId: String?
    ): JSONObject = JSONObject().putCommon(split, userId).apply {
        put("expense_id", expenseRemoteId)
        put("person_id", personRemoteId ?: JSONObject.NULL)
        put("amount_minor", split.amountPaise)
        putTime("settled_at", split.settledAt)
    }

    /**
     * [localId] and the resolved foreign keys come from the caller, which is the only part that
     * needs the database. syncedAt is stamped because a row just read from the server is by
     * definition in step with it.
     */
    fun expenseFrom(json: JSONObject, localId: Long, syncedAt: Long): Expense? {
        val remoteId = json.stringOrNull("id") ?: return null
        return Expense(
            id = localId,
            amountPaise = json.optLong("amount_minor"),
            category = json.stringOrNull("category").orEmpty(),
            note = json.optString("note").takeIf { !json.isNull("note") }.orEmpty(),
            merchant = json.optString("merchant").takeIf { !json.isNull("merchant") }.orEmpty(),
            paidAt = fromIso(json.stringOrNull("paid_at")) ?: return null,
            sourceUri = null,
            remoteId = remoteId,
            updatedAt = fromIso(json.stringOrNull("updated_at")) ?: return null,
            deletedAt = fromIso(json.stringOrNull("deleted_at")),
            syncedAt = syncedAt
        )
    }

    fun personFrom(json: JSONObject, localId: Long, syncedAt: Long): Person? {
        val remoteId = json.stringOrNull("id") ?: return null
        val name = json.stringOrNull("name") ?: return null
        return Person(
            id = localId,
            name = name,
            note = json.optString("note").takeIf { !json.isNull("note") }.orEmpty(),
            remoteId = remoteId,
            updatedAt = fromIso(json.stringOrNull("updated_at")) ?: return null,
            deletedAt = fromIso(json.stringOrNull("deleted_at")),
            syncedAt = syncedAt
        )
    }

    fun categoryFrom(json: JSONObject, localId: Long, syncedAt: Long): Category? {
        val remoteId = json.stringOrNull("id") ?: return null
        val name = json.stringOrNull("name") ?: return null
        return Category(
            id = localId,
            name = name,
            sortOrder = json.optInt("sort_order"),
            remoteId = remoteId,
            updatedAt = fromIso(json.stringOrNull("updated_at")) ?: return null,
            deletedAt = fromIso(json.stringOrNull("deleted_at")),
            syncedAt = syncedAt
        )
    }

    fun splitFrom(
        json: JSONObject,
        localId: Long,
        expenseId: Long,
        personId: Long?,
        syncedAt: Long
    ): ExpenseSplit? {
        val remoteId = json.stringOrNull("id") ?: return null
        return ExpenseSplit(
            id = localId,
            expenseId = expenseId,
            personId = personId,
            amountPaise = json.optLong("amount_minor"),
            settledAt = fromIso(json.stringOrNull("settled_at")),
            remoteId = remoteId,
            updatedAt = fromIso(json.stringOrNull("updated_at")) ?: return null,
            deletedAt = fromIso(json.stringOrNull("deleted_at")),
            syncedAt = syncedAt
        )
    }
}

/** What a pulled row should cause, once it is known what is held locally. */
enum class Merge {
    /** Nothing local has this identity. */
    INSERT,

    /** Local is in step with the server and the server has moved on. */
    UPDATE,

    /** Both sides changed since they last agreed. Only the user can say which is right. */
    CONFLICT,

    /** Local is unchanged and no newer, or local holds edits the push will carry. */
    SKIP,

    /**
     * The row could not be written yet -- a split whose expense has not arrived, or a row the
     * local schema refused. Never returned by [mergeDecision]: it is what the engine reports when
     * the write itself does not go through, and it is what holds the watermark back so the row is
     * offered again next time rather than stepped over.
     */
    BLOCKED
}

/**
 * Decides what a pulled row means, given whatever is held locally.
 *
 * The whole of the conflict rule is here, in one pure function, because it is the part that can
 * silently lose somebody's work. A row is only a conflict when both sides moved: local edits that
 * have not reached the server (syncedAt null) together with a server copy newer than the local
 * one. A local edit that is itself newer is not a conflict -- the push will carry it -- and a
 * local row already in step with the server simply takes the update.
 */
fun mergeDecision(local: Synced?, remoteUpdatedAt: Long): Merge = when {
    local == null -> Merge.INSERT
    local.syncedAt != null -> if (remoteUpdatedAt > local.updatedAt) Merge.UPDATE else Merge.SKIP
    remoteUpdatedAt > local.updatedAt -> Merge.CONFLICT
    else -> Merge.SKIP
}
