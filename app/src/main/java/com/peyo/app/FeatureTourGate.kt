package com.peyo.app

import android.content.Context
import android.content.pm.PackageManager

/**
 * Whether this phone still has the feature tour to see.
 *
 * Meant for new installs only. Somebody updating from a version without the tour already knows the
 * app, and opening it on six cards about features they use every day would be in their way. An
 * update shows up as the package's last update time running ahead of its install time; a phone
 * that has never been updated has the two equal.
 *
 * The flag is kept out of Android's backup (see backup_rules.xml), so a reinstall counts as new and
 * sees the tour again rather than inheriting "seen" from the phone it was restored from.
 */
object FeatureTourGate {

    private const val PREFS = "peyo.onboarding"
    private const val KEY_SEEN = "tourSeen"

    /**
     * [knownUser] is the fallback for the rare install updated before it was ever opened: anybody
     * already signed in, or who has already turned an account down, has been through the app.
     */
    fun shouldShow(context: Context, knownUser: Boolean): Boolean {
        if (prefs(context).getBoolean(KEY_SEEN, false)) return false
        return neverUpdated(context) || !knownUser
    }

    fun markSeen(context: Context) {
        prefs(context).edit().putBoolean(KEY_SEEN, true).apply()
    }

    private fun neverUpdated(context: Context): Boolean = try {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.firstInstallTime == info.lastUpdateTime
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
