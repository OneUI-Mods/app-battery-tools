package dev.ryuya.appbatterytools

/** Desired scans survive a Wi-Fi pause, but explicit stop/failure removes the desire. */
internal class ScanRegistry<K, V>(private val capacity: Int = 32) {
    data class Entry<V>(val request: V, var active: Boolean)

    private val entries = linkedMapOf<K, Entry<V>>()

    fun contains(key: K) = entries.containsKey(key)

    fun isCurrent(key: K, request: V, active: Boolean): Boolean {
        val entry = entries[key] ?: return false
        return entry.request === request && entry.active == active
    }

    fun canAdd() = entries.size < capacity

    fun add(key: K, value: V, active: Boolean) {
        check(!entries.containsKey(key) && canAdd())
        entries[key] = Entry(value, active)
    }

    fun remove(key: K) = entries.remove(key)

    fun transition(connected: Boolean): List<Pair<K, V>> = entries.mapNotNull { (key, entry) ->
        if (entry.active == connected) null
        else {
            entry.active = connected
            key to entry.request
        }
    }

    fun clear() = entries.clear()

    val size
        get() = entries.size
}
