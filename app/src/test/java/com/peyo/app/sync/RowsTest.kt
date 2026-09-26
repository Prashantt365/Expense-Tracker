package com.peyo.app.sync

import com.peyo.app.data.Category
import com.peyo.app.data.Expense
import com.peyo.app.data.ExpenseSplit
import com.peyo.app.data.Person
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A mistyped column name comes back as a refusal anybody can read. A timestamp parsed in the wrong
 * zone comes back as nothing at all, and then resolves a conflict the wrong way months later, so
 * it is the timestamps that get the attention here.
 */
class RowsTest {

    // 2026-09-14T10:00:00.000Z
    private val instant = 1_789_380_000_000L

    @Test fun `a time survives the round trip`() {
        assertEquals(instant, Rows.fromIso(Rows.toIso(instant)))
        assertEquals("2026-09-14T10:00:00.000Z", Rows.toIso(instant))
    }

    @Test fun `every way postgres writes UTC reads as the same instant`() {
        listOf(
            "2026-09-14T10:00:00Z",
            "2026-09-14T10:00:00.000Z",
            "2026-09-14T10:00:00+00",
            "2026-09-14T10:00:00+00:00",
            "2026-09-14T10:00:00+0000",
            "2026-09-14 10:00:00+00"
        ).forEach { written ->
            assertEquals("could not read '$written'", instant, Rows.fromIso(written))
        }
    }

    @Test fun `fractional seconds are taken to milliseconds however many are sent`() {
        assertEquals(instant + 123, Rows.fromIso("2026-09-14T10:00:00.123456Z"))
        assertEquals(instant + 500, Rows.fromIso("2026-09-14T10:00:00.5Z"))
        assertEquals(instant + 120, Rows.fromIso("2026-09-14T10:00:00.12Z"))
        assertEquals(instant, Rows.fromIso("2026-09-14T10:00:00.000000Z"))
    }

    /**
     * Reading a zone that is not UTC as though it were would shift the time by hours, which is
     * exactly enough to make a conflict resolve backwards. Refusing is the safe answer.
     */
    @Test fun `a zone other than UTC is refused rather than guessed at`() {
        assertNull(Rows.normaliseTimestamp("2026-09-14T10:00:00+05:30"))
        assertNull(Rows.fromIso("2026-09-14T10:00:00+05:30"))
        assertNull(Rows.fromIso("2026-09-14T10:00:00-08:00"))
    }

    @Test fun `nothing and nonsense read as no time at all`() {
        assertNull(Rows.fromIso(null))
        assertNull(Rows.fromIso(""))
        assertNull(Rows.fromIso("   "))
        assertNull(Rows.fromIso("yesterday"))
        assertNull(Rows.fromIso("2026-09-14"))
    }

    @Test fun `an expense goes out under the column names the schema uses`() {
        val expense = Expense(
            id = 7,
            amountPaise = 18_200,
            category = "Food",
            note = "pizza",
            merchant = "Swiggy",
            paidAt = instant,
            remoteId = "e1",
            updatedAt = instant,
            deletedAt = null
        )
        val json = Rows.expenseJson(expense, userId = "u1", currency = "INR")
        assertEquals("e1", json.getString("id"))
        assertEquals("u1", json.getString("user_id"))
        assertEquals(18_200, json.getLong("amount_minor"))
        assertEquals("INR", json.getString("currency"))
        assertEquals("Swiggy", json.getString("merchant"))
        assertEquals("2026-09-14T10:00:00.000Z", json.getString("paid_at"))
        assertTrue(json.isNull("deleted_at"))
        // The local row number is meaningless on the server and must not be sent as the key.
        assertEquals("e1", json.getString("id"))
    }

    @Test fun `a deleted row carries its tombstone out`() {
        val person = Person(id = 3, name = "Rahul", remoteId = "p1", updatedAt = instant, deletedAt = instant)
        val json = Rows.personJson(person, "u1")
        assertEquals("2026-09-14T10:00:00.000Z", json.getString("deleted_at"))
    }

    @Test fun `an expense comes back with the local row it belongs to`() {
        val json = JSONObject(
            """
            {"id":"e1","user_id":"u1","amount_minor":18200,"currency":"INR","category":"Food",
             "note":"pizza","merchant":"Swiggy","paid_at":"2026-09-14T10:00:00+00:00",
             "updated_at":"2026-09-14T10:00:00+00:00","deleted_at":null}
            """.trimIndent()
        )
        val expense = Rows.expenseFrom(json, localId = 7, syncedAt = 99)!!
        assertEquals(7, expense.id)
        assertEquals("e1", expense.remoteId)
        assertEquals(18_200, expense.amountPaise)
        assertEquals("pizza", expense.note)
        assertEquals(instant, expense.paidAt)
        assertNull(expense.deletedAt)
        assertEquals(99L, expense.syncedAt)
        // Screenshots never leave the phone, so a pulled row cannot claim to have one.
        assertNull(expense.sourceUri)
    }

    @Test fun `a row missing the fields it is identified by is refused`() {
        val noId = JSONObject("""{"amount_minor":1,"paid_at":"2026-09-14T10:00:00Z","updated_at":"2026-09-14T10:00:00Z"}""")
        assertNull(Rows.expenseFrom(noId, 1, 0))
        val noTime = JSONObject("""{"id":"e1","amount_minor":1,"updated_at":"2026-09-14T10:00:00Z"}""")
        assertNull(Rows.expenseFrom(noTime, 1, 0))
        val noName = JSONObject("""{"id":"p1","updated_at":"2026-09-14T10:00:00Z"}""")
        assertNull(Rows.personFrom(noName, 1, 0))
    }

    @Test fun `a null text column reads as empty rather than as the word null`() {
        val json = JSONObject(
            """
            {"id":"e1","amount_minor":1,"category":"Food","note":null,"merchant":null,
             "paid_at":"2026-09-14T10:00:00Z","updated_at":"2026-09-14T10:00:00Z"}
            """.trimIndent()
        )
        val expense = Rows.expenseFrom(json, 1, 0)!!
        assertEquals("", expense.note)
        assertEquals("", expense.merchant)
    }

    @Test fun `a split points at its expense and person by their remote ids`() {
        val split = ExpenseSplit(
            id = 5,
            expenseId = 7,
            personId = 3,
            amountPaise = 9_100,
            settledAt = null,
            remoteId = "s1",
            updatedAt = instant
        )
        val json = Rows.splitJson(split, "u1", expenseRemoteId = "e1", personRemoteId = "p1")
        assertEquals("e1", json.getString("expense_id"))
        assertEquals("p1", json.getString("person_id"))
        assertTrue(json.isNull("settled_at"))
    }

    /** A share of my own has no person, and null is the thing that says so. */
    @Test fun `my own share goes out with no person at all`() {
        val mine = ExpenseSplit(expenseId = 7, personId = null, amountPaise = 9_100, remoteId = "s2", updatedAt = instant)
        val json = Rows.splitJson(mine, "u1", "e1", null)
        assertTrue(json.isNull("person_id"))
    }

    /**
     * Editing an expense retires the shares it no longer has by tombstoning them. If the tombstone
     * did not make the round trip, another phone -- or a restore -- would keep the old shares
     * beside the new ones and count every balance twice.
     */
    @Test fun `a removed split carries its tombstone out and back`() {
        val retired = ExpenseSplit(
            id = 5, expenseId = 7, personId = 3, amountPaise = 9_100,
            remoteId = "s1", updatedAt = instant, deletedAt = instant + 1, syncedAt = null
        )
        val json = Rows.splitJson(retired, "u1", expenseRemoteId = "e1", personRemoteId = "p1")
        assertEquals("2026-09-14T10:00:00.001Z", json.getString("deleted_at"))

        val back = Rows.splitFrom(json, localId = 11, expenseId = 70, personId = 30, syncedAt = 99)!!
        assertEquals(instant + 1, back.deletedAt)
        assertEquals(70L, back.expenseId)
        assertEquals(30L, back.personId)
        assertEquals(99L, back.syncedAt)
    }

    @Test fun `a live split comes back live`() {
        val json = JSONObject(
            """
            {"id":"s1","expense_id":"e1","person_id":null,"amount_minor":100,"settled_at":null,
             "updated_at":"2026-09-14T10:00:00Z","deleted_at":null}
            """.trimIndent()
        )
        assertNull(Rows.splitFrom(json, 1, 7, null, 0)!!.deletedAt)
    }

    /**
     * What a restore meets for every row it re-reads: the same contents under the server's stamp.
     * Only the bookkeeping differs, and that must not make two versions look different.
     */
    @Test fun `the same contents under different stamps are the same`() {
        val held = Expense(
            id = 7, amountPaise = 18_200, category = "Food", note = "pizza", merchant = "Swiggy",
            paidAt = instant, sourceUri = "content://shot", remoteId = "e1",
            updatedAt = instant, syncedAt = null
        )
        val pulled = Rows.expenseFrom(Rows.expenseJson(held, "u1", "INR").put("updated_at", "2026-09-14T11:00:00Z"), 0, 99)!!
        assertTrue(sameContent(held, pulled))

        assertFalse(sameContent(held, pulled.copy(amountPaise = 18_300)))
        assertFalse(sameContent(held, pulled.copy(deletedAt = instant)))
    }

    @Test fun `a split differing only in its tombstone is not the same`() {
        val live = ExpenseSplit(expenseId = 7, personId = 3, amountPaise = 100, remoteId = "s1", updatedAt = instant)
        assertTrue(sameContent(live, live.copy(id = 9, updatedAt = instant + 5, syncedAt = 1)))
        assertFalse(sameContent(live, live.copy(deletedAt = instant)))
        assertFalse(sameContent(live, live.copy(personId = 4)))
    }

    @Test fun `a category round trips`() {
        val category = Category(id = 2, name = "Food", sortOrder = 3, remoteId = "c1", updatedAt = instant)
        val json = Rows.categoryJson(category, "u1")
        assertEquals("Food", json.getString("name"))
        assertEquals(3, json.getInt("sort_order"))
        val back = Rows.categoryFrom(json, localId = 2, syncedAt = 0)!!
        assertEquals("Food", back.name)
        assertEquals(3, back.sortOrder)
        assertEquals("c1", back.remoteId)
    }
}
