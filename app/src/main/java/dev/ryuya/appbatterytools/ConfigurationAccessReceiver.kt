package dev.ryuya.appbatterytools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** One-shot boot/update recovery; callers cannot select a recipient or an authority. */
class ConfigurationAccessReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action == Intent.ACTION_BOOT_COMPLETED ||
                intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            ConfigurationAccess.grant(context)
        } else if (intent.action == ConfigurationAccess.REQUEST) {
            val targetUid =
                try {
                    context.packageManager.getApplicationInfo(Targets.DIGIPOLICE, 0).uid
                } catch (_: PackageManager.NameNotFoundException) {
                    return
                }
            if (sentFromUid == targetUid) ConfigurationAccess.grant(context)
        }
    }
}
