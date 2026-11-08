package dev.ryuya.appbatterytools

/** A small LRU for reviewed pure computations. Failures are not cached. */
internal class BoundedMemo<K, V>(private val capacity: Int = 32) {
    init {
        require(capacity > 0)
    }

    private val values = LinkedHashMap<K, V>(capacity, .75f, true)

    @Synchronized
    fun getOrCompute(key: K, compute: () -> V): V {
        if (values.containsKey(key)) return values.getValue(key)
        val value = compute()
        values[key] = value
        if (values.size > capacity) values.remove(values.keys.first())
        return value
    }

    @Synchronized
    fun invalidate(value: V) {
        val iterator = values.entries.iterator()
        while (iterator.hasNext()) if (iterator.next().value === value) iterator.remove()
    }

    val size
        get() = synchronized(this) { values.size }
}
