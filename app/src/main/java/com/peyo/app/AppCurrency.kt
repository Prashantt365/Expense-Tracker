package com.peyo.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Currency
import java.util.Locale

/**
 * The single currency every amount in the app is shown in.
 *
 * Amounts are stored as hundredths of a unit whatever that currency is, so changing it relabels
 * the existing history rather than converting it. That is the honest trade of a single-currency
 * app: only the user knows what a past amount was really denominated in, and inventing a rate to
 * convert by would corrupt figures that are currently correct.
 */
object AppCurrency {

    /** Used when the phone reports a region with no currency of its own, which a tablet can. */
    const val FALLBACK = "INR"

    private const val PREFS = "peyo.settings"
    private const val KEY = "currencyCode"

    /**
     * Compose state rather than a plain field: money is formatted in two dozen places, none of
     * which should have to observe a preference to stay current when this changes.
     */
    var code by mutableStateOf(FALLBACK)
        // Written through set() in the app; left open to the module so formatting can be tested
        // without an Android Context, which load() and set() both need.
        internal set

    val currency: Currency get() = of(code) ?: Currency.getInstance(FALLBACK)

    /** The stored choice if there is one, otherwise whatever the phone's region implies. */
    fun load(context: Context) {
        code = prefs(context).getString(KEY, null) ?: deviceDefault(context)
    }

    /**
     * False while the app is only guessing from the phone's region.
     *
     * The guess is right often enough to open the app with, and wrong often enough -- a phone
     * bought abroad, a dual-SIM traveller, a tablet with no region at all -- that it is worth
     * asking once. This is what tells the first run whether it still has to.
     */
    fun hasChosen(context: Context): Boolean = prefs(context).contains(KEY)

    fun set(context: Context, newCode: String) {
        if (of(newCode) == null) return
        code = newCode
        prefs(context).edit().putString(KEY, newCode).apply()
    }

    /**
     * The country the phone was set up in, read from its configured locales.
     *
     * The locale list is walked in the user's own order of preference, because only some of those
     * locales carry a region -- a phone set to plain "en" followed by "en-GB" has to reach the
     * second entry before GBP can be worked out. Currency.getInstance throws rather than returning
     * null on a region it cannot resolve, hence the runCatching on each.
     */
    private fun deviceDefault(context: Context): String {
        val locales = context.resources.configuration.locales
        for (index in 0 until locales.size()) {
            of(locales[index])?.let { return it.currencyCode }
        }
        return of(Locale.getDefault())?.currencyCode ?: FALLBACK
    }

    private fun of(locale: Locale): Currency? = runCatching { Currency.getInstance(locale) }.getOrNull()
    private fun of(code: String): Currency? = runCatching { Currency.getInstance(code) }.getOrNull()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Every currency the platform knows, as code-and-name pairs for the picker. Currencies with no
     * display name of their own are dropped: they are historical codes the user cannot want.
     */
    fun all(): List<Pair<String, String>> = Currency.getAvailableCurrencies()
        .map { it.currencyCode to it.getDisplayName(Locale.getDefault()) }
        .filter { (code, name) -> name != code }
        .sortedBy { it.second }
}
