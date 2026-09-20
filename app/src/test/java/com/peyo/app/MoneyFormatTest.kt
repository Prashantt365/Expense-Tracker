package com.peyo.app

import com.peyo.app.ui.money
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Amounts are stored as hundredths of a unit whatever the currency is, so formatting has to bridge
 * that fixed scale to however many decimal places the currency in use actually has. Symbols and
 * their placement come from the machine's own locale and are deliberately not asserted; the digits
 * are what this is about.
 */
class MoneyFormatTest {

    @After fun restore() { AppCurrency.code = AppCurrency.FALLBACK }

    private fun digits(text: String) = text.filter { it.isDigit() || it == '.' || it == ',' }

    @Test fun `two-decimal currencies keep both places`() {
        AppCurrency.code = "INR"
        assertEquals("182.00", digits(money(18_200)))
        assertEquals("1,250.50", digits(money(125_050)))
    }

    @Test fun `a currency with no minor unit is shown whole`() {
        // 18,200 hundredths is 182 yen, not 18,200 -- and never "182.00", which yen has no minor
        // unit for. Rounding to whole yen is the point, so a half unit has to go somewhere.
        AppCurrency.code = "JPY"
        assertEquals("182", digits(money(18_200)))
        assertEquals("183", digits(money(18_250)))
    }

    @Test fun `a three-decimal currency keeps the place the stored scale does not have`() {
        AppCurrency.code = "KWD"
        assertEquals("182.000", digits(money(18_200)))
    }

    @Test fun `an unknown code falls back rather than throwing`() {
        AppCurrency.code = "ZZZ"
        assertEquals(AppCurrency.FALLBACK, AppCurrency.currency.currencyCode)
        assertTrue(digits(money(18_200)).isNotEmpty())
    }

    @Test fun `the picker offers real currencies including the fallback`() {
        val all = AppCurrency.all()
        assertTrue("expected a substantial currency list, got ${all.size}", all.size > 100)
        assertTrue(all.any { it.first == AppCurrency.FALLBACK })
        assertTrue("codes should not double as names", all.none { it.first == it.second })
    }
}
