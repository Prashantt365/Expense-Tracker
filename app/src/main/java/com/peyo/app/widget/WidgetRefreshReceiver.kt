package com.peyo.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Redraws the widgets after the app itself has been replaced.
 *
 * Installing a new build throws away the RemoteViews the launcher had cached, so every placed
 * widget falls back to the placeholder layout it was declared with. It stays there until something
 * asks the provider for new content -- and with nothing scheduled sooner than the half-hourly
 * update, "something" was the user opening the app. Anybody who installed an update and looked at
 * their home screen first saw two blank tiles and no reason for them.
 *
 * MY_PACKAGE_REPLACED is delivered to the app that was replaced, which is exactly and only the
 * case this has to cover, so it needs no permission and wakes nothing that was not already
 * being restarted.
 */
class WidgetRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // updateAll suspends, and a broadcast receiver is dead the moment onReceive returns, so
        // the result has to be kept alive explicitly.
        val pending = goAsync()
        val application = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                PeyoWidgets.refresh(application)
            } finally {
                pending.finish()
            }
        }
    }
}
