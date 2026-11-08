package dev.ryuya.appbatterytools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Target updates invalidate the generation without installing a polling service. */
class PackageChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.data?.schemeSpecificPart != Targets.DIGIPOLICE) return
        // Provider initialization enqueues discovery and immediately returns.
        context.contentResolver.call(ModuleProvider.URI, "status", null, null)
    }
}
