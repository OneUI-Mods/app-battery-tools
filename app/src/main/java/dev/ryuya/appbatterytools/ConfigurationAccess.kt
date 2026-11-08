package dev.ryuya.appbatterytools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** An exact URI grant makes the module visible to the scoped app on Android 16/17. */
internal object ConfigurationAccess {
    const val REQUEST = "dev.ryuya.appbatterytools.REQUEST_CONFIGURATION_ACCESS"

    fun grant(context: Context) {
        for (target in Targets.all) try {
            context.packageManager.getApplicationInfo(target.packageName, 0)
            context.grantUriPermission(
                target.packageName,
                ModuleProvider.URI,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: PackageManager.NameNotFoundException) {
            /* Optional target absent. */
        }
    }
}
