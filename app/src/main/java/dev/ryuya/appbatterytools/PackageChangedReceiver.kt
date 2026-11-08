package dev.ryuya.appbatterytools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Target updates invalidate the generation without installing a polling service. */
class PackageChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action !in
                setOf(
                    Intent.ACTION_PACKAGE_ADDED,
                    Intent.ACTION_PACKAGE_REPLACED,
                    Intent.ACTION_PACKAGE_REMOVED,
                )
        )
            return
        val name = intent.data?.schemeSpecificPart ?: return
        if (Targets.find(name) == null) return
        ConfigurationAccess.grant(context)
        // Provider initialization enqueues discovery and immediately returns.
        context.contentResolver.call(ModuleProvider.URI, "status", name, null)
    }
}
