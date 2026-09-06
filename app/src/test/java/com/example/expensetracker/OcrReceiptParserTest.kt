package com.example.expensetracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * The sample texts below mirror what ML Kit returns for real Google Pay receipt screenshots,
 * including the ways its Latin model mangles the rupee glyph.
 */
class OcrReceiptParserTest {

    @Test fun `reads amount and payee from a merchant receipt`() {
        val draft = OcrReceiptParser.parse(
            """
            ₹450
            To Swiggy
            Completed
            6 Sep 2026, 10:32 am
            UPI transaction ID
            432109876543
            From
            HDFC Bank ••5678
            """.trimIndent()
        )
        assertEquals("450", draft.amount)
        assertEquals("Swiggy", draft.merchant)
        assertEquals("", draft.note)
        assertEquals("Food", draft.category)
    }

    @Test fun `reads the note a payer typed on a person-to-person payment`() {
        val draft = OcrReceiptParser.parse(
            """
            ₹1,200
            Rahul Sharma
            Dinner split
            Completed • 5 Sep 2026
            UPI transaction ID 987654321012
            From HDFC Bank XXXX5678
            """.trimIndent()
        )
        assertEquals("1200", draft.amount)
        assertEquals("Rahul Sharma", draft.merchant)
        assertEquals("Dinner split", draft.note)
        assertEquals("Food", draft.category)
    }

    @Test fun `reads a payee carried on the line after a Paid to label`() {
        val draft = OcrReceiptParser.parse(
            """
            Paid to
            BLUE TOKAI COFFEE
            ₹385.50
            Coffee with team
            Completed
            5 September 2026, 4:12 pm
            UPI transaction ID 456789012345
            Google transaction ID CICAgIDF6oXVFQ
            """.trimIndent()
        )
        assertEquals("385.50", draft.amount)
        assertEquals("BLUE TOKAI COFFEE", draft.merchant)
        assertEquals("Coffee with team", draft.note)
        assertEquals("Food", draft.category)
    }

    @Test fun `reads the amount when OCR drops the rupee glyph entirely`() {
        val draft = OcrReceiptParser.parse(
            """
            2,499
            Paid to Amazon Pay
            Order payment
            Completed
            """.trimIndent()
        )
        assertEquals("2499", draft.amount)
        assertEquals("Amazon Pay", draft.merchant)
        assertEquals("Order payment", draft.note)
        assertEquals("Shopping", draft.category)
    }

    @Test fun `reads the amount when OCR misreads the rupee glyph as a symbol`() {
        val draft = OcrReceiptParser.parse(
            """
            *450
            Paid to Zomato
            Completed
            """.trimIndent()
        )
        assertEquals("450", draft.amount)
        assertEquals("Zomato", draft.merchant)
    }

    @Test fun `does not mistake a transaction reference for the amount`() {
        val draft = OcrReceiptParser.parse(
            """
            Paid to Ola Cabs
            UPI transaction ID
            432109876543
            ₹238
            """.trimIndent()
        )
        assertEquals("238", draft.amount)
        assertEquals("Transport", draft.category)
    }

    @Test fun `does not mistake a masked account tail for the amount`() {
        val draft = OcrReceiptParser.parse(
            """
            Paid to Kirana Store
            From HDFC Bank
            ••5678
            """.trimIndent()
        )
        assertEquals("", draft.amount)
        assertEquals("Kirana Store", draft.merchant)
    }

    @Test fun `prefers an explicitly labelled note`() {
        val draft = OcrReceiptParser.parse(
            """
            ₹900
            Paid to Apollo Pharmacy
            Note: monthly medicines
            Completed
            """.trimIndent()
        )
        assertEquals("monthly medicines", draft.note)
        assertEquals("Health", draft.category)
    }

    @Test fun `takes the total rather than a line item on an itemised bill`() {
        val draft = OcrReceiptParser.parse(
            """
            Subtotal Rs. 400
            GST Rs. 50
            Total Rs. 450
            """.trimIndent()
        )
        assertEquals("450", draft.amount)
    }

    @Test fun `returns an empty draft for unreadable text`() {
        val draft = OcrReceiptParser.parse("")
        assertEquals("", draft.amount)
        assertEquals("", draft.merchant)
        assertEquals("", draft.note)
        assertEquals("Other", draft.category)
    }

    @Test fun `every parsed amount survives the BigDecimal parse the editor performs`() {
        val samples = listOf("₹450", "Rs. 1,250.50", "INR 90", "2,499", "*450")
        samples.forEach { line ->
            val amount = OcrReceiptParser.parse(line).amount
            assertTrue("no amount found in '$line'", amount.isNotEmpty())
            assertTrue("'$amount' is not positive", BigDecimal(amount).signum() == 1)
        }
    }

    @Test fun `ignores action buttons and keeps a short note`() {
        val draft = OcrReceiptParser.parse(
            """
            ₹500
            Paid to Rahul Sharma
            me
            Completed
            6 Sep 2026, 2:15 pm
            UPI transaction ID 123456789012
            From HDFC Bank ••1234
            Pay again
            Split expense
            """.trimIndent()
        )
        assertEquals("500", draft.amount)
        assertEquals("Rahul Sharma", draft.merchant)
        assertEquals("me", draft.note)
    }

    @Test fun `ignores an action button that OCR returns before the note`() {
        // ML Kit orders blocks spatially, so a button at the foot of the receipt can arrive first.
        val draft = OcrReceiptParser.parse(
            """
            Pay again
            ₹500
            Paid to Rahul Sharma
            me
            Completed
            """.trimIndent()
        )
        assertEquals("me", draft.note)
        assertEquals("Rahul Sharma", draft.merchant)
    }

    @Test fun `keeps a note that merely starts with a button word`() {
        val draft = OcrReceiptParser.parse(
            """
            ₹800
            Paid to Rahul Sharma
            Share of the cab
            Completed
            """.trimIndent()
        )
        assertEquals("Share of the cab", draft.note)
    }

    @Test fun `categorises from the merchant and the note together`() {
        assertEquals("Transport", OcrReceiptParser.categorize("uber trip"))
        assertEquals("Bills", OcrReceiptParser.categorize("airtel recharge"))
        assertEquals("Other", OcrReceiptParser.categorize("gift for a friend"))
    }
}
