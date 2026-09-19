package com.example.expensetracker.sync

import android.content.Context

/**
 * Whether the app backs itself up without being asked.
 *
 * On by default, which is the point. A backup nobody remembers to take is not a backup, and the
 * cost of getting it wrong is the entire reason the account exists -- so the default has to be the
 * safe one and the switch has to be there for anyone who wants the old behaviour back.
 *
 * It stays a preference rather than becoming unconditional because syncing is the one thing this
 * app does that sends anything anywhere, and taking that decision away from the user would be a
 * worse trade than a checkbox.
 */
class BackupSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("peyo.settings", Context.MODE_PRIVATE)

    var autoBackup: Boolean
        get() = prefs.getBoolean(KEY_AUTO_BACKUP, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_BACKUP, value).apply()

    /**
     * Whether the user has already answered the account question by declining it.
     *
     * Without this the welcome screen was shown on every cold start to anybody using the app
     * offline, which is the majority case the app is built for: they said no on Monday and were
     * asked again on Tuesday, and again from every launcher shortcut and every home screen
     * widget, each of which landed on a sales pitch instead of the thing they tapped. Signing in
     * is still one tap away in Settings, so nothing is lost by taking the answer at its word.
     */
    var accountDeclined: Boolean
        get() = prefs.getBoolean(KEY_ACCOUNT_DECLINED, false)
        set(value) = prefs.edit().putBoolean(KEY_ACCOUNT_DECLINED, value).apply()

    private companion object {
        const val KEY_AUTO_BACKUP = "autoBackup"
        const val KEY_ACCOUNT_DECLINED = "accountDeclined"
    }
}
