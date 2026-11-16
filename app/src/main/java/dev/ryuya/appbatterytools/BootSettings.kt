package dev.ryuya.appbatterytools

import android.content.Context

/** Boot preparation is optional; explicit configuration requests remain available. */
internal object BootSettings {
    private const val PREFERENCES = "startup"
    private const val ENABLED = "start_on_boot"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ENABLED, enabled)
            .apply()
    }
}
