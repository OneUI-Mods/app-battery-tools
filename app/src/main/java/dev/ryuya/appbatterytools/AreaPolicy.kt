package dev.ryuya.appbatterytools

/** Missing or unreviewed settings are never permission to disable a feature. */
internal object AreaPolicy {
    fun shouldSuppress(reviewed: Boolean, stored: Boolean?): Boolean = reviewed && stored == false
}
