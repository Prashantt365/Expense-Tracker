package com.peyo.app.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A row of shared_with_me() as PostgREST returns it. */
class SharedLedgerParsingTest {

    private fun row(iOwe: Boolean = true, settled: String? = null) = JSONObject(
        """
        {
          "split_id": "2f5d1c1a-1111-4a4a-9b9b-000000000001",
          "expense_id": "2f5d1c1a-2222-4a4a-9b9b-000000000002",
          "owner_id": "8f14e45f-ceea-467a-9f0b-4c2d6a1f9b77",
          "owner_name": "Prashant",
          "owner_phone": "+919876543210",
          "merchant": "Dinner",
          "category": "Food",
          "note": "",
          "paid_at": "2026-10-01T19:30:00+00:00",
          "amount_minor": 30000,
          "currency": "INR",
          "i_owe": $iOwe,
          "settled_at": ${if (settled == null) "null" else "\"$settled\""},
          "removed": false,
          "updated_at": "2026-10-01T19:31:00.123456+00:00"
        }
        """.trimIndent()
    )

    @Test fun `a share somebody put me on reads as what I owe them`() {
        val share = SharedLedger.shareFrom(row())!!
        assertEquals("Prashant", share.ownerName)
        assertEquals(30000, share.amountPaise)
        assertTrue(share.iOwe)
        assertTrue(share.open)
        assertNull(share.settledAt)
    }

    @Test fun `a bill recorded as paid by me reads as owed to me`() {
        assertFalse(SharedLedger.shareFrom(row(iOwe = false))!!.iOwe)
    }

    @Test fun `a settled share is no longer open`() {
        val share = SharedLedger.shareFrom(row(settled = "2026-10-02T08:00:00Z"))!!
        assertFalse(share.open)
    }

    @Test fun `a row without its ids is skipped rather than guessed at`() {
        val broken = row().apply { remove("split_id") }
        assertNull(SharedLedger.shareFrom(broken))
    }
}
