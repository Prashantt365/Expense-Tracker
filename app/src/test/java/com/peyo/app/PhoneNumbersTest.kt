package com.peyo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Every way one number gets typed must come out as the same stored number, because that number is
 * the whole of how an expense finds the person it names.
 */
class PhoneNumbersTest {

    @Test fun `the same number typed every usual way is one number`() {
        listOf(
            "9876543210",
            "98765 43210",
            "98765-43210",
            "+91 98765 43210",
            "+919876543210",
            "+91-98765-43210",
            "(+91) 98765 43210",
            "09876543210",
            "0091 98765 43210",
            "91 98765 43210",
            "  9 8 7 6 5 4 3 2 1 0  "
        ).forEach { typed ->
            assertEquals("for '$typed'", "+919876543210", PhoneNumbers.normalize(typed))
        }
    }

    @Test fun `another country's code is kept as typed`() {
        assertEquals("+12025550143", PhoneNumbers.normalize("+1 202 555 0143"))
        assertEquals("+447946012345", PhoneNumbers.normalize("+44 7946 012345"))
        assertEquals("+9719876543210", PhoneNumbers.normalize("00971 98765 43210"))
    }

    @Test fun `anything that is not ten digits after the code is refused`() {
        listOf(
            "",
            "   ",
            "987654321",          // nine digits
            "98765432101",        // eleven, not a trunk zero
            "+91 98765 4321",     // nine after the code
            "+0 9876543210",      // a country code never starts with 0
            "+1234 9876543210",   // four-digit code
            "98765abc10",
            "call me",
            "+",
            "12 9876543210"       // two leading digits that are not +91
        ).forEach { typed ->
            assertNull("'$typed' should be refused", PhoneNumbers.normalize(typed))
        }
        assertNull(PhoneNumbers.normalize(null))
    }

    @Test fun `a stored number is shown back with spaces`() {
        assertEquals("+91 98765 43210", PhoneNumbers.display("+919876543210"))
        assertEquals("+1 20255 50143", PhoneNumbers.display("+12025550143"))
        assertEquals("", PhoneNumbers.display(null))
    }
}
