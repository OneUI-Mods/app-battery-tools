package dev.ryuya.appbatterytools

/** Descriptor strings must never overwrite Boolean settings or status fields in a Bundle. */
internal object DiscoveryBindings {
    private val reserved =
        setOf("ready", "discovery", "searches", "hostState", "error", "released", "updated")

    fun checked(bindings: Map<String, String?>, optionKeys: Set<String>): Map<String, String?> {
        require(bindings.keys.none { it in optionKeys || it in reserved }) {
            "Discovery binding collides with configuration"
        }
        return bindings
    }
}
