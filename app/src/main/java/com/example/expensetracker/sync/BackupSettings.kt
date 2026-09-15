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
        .getSharedPreferences("spendwise.settings", Context.MODE_PRIVATE)

    var autoBackup: Boolean
        get() = prefs.getBoolean(KEY_AUTO_BACKUP, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_BACKUP, value).apply()

    private companion object {
        const val KEY_AUTO_BACKUP = "autoBackup"
    }
}
