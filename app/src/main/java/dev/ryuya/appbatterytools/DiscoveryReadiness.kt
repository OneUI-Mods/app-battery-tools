package dev.ryuya.appbatterytools

/** Only a pending exploration needs an event retry; cached misses must not wait forever. */
internal object DiscoveryReadiness {
    fun pending(state: String?): Boolean =
        state == null || state == "waiting" || state == "preparing"
}
