package dev.ryuya.appbatterytools

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/** Idempotence without retaining closed native wrappers or running a cleanup thread. */
internal class WeakIdentitySet {
    private class Key(value: Any, queue: ReferenceQueue<Any>? = null) :
        WeakReference<Any>(value, queue) {
        private val hash = System.identityHashCode(value)

        override fun hashCode() = hash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            val referent = get() ?: return false
            return other is Key && referent === other.get()
        }
    }

    private val queue = ReferenceQueue<Any>()
    private val keys = HashSet<Key>()

    @Synchronized
    fun add(value: Any): Boolean {
        while (true) {
            val dead = queue.poll() ?: break
            keys.remove(dead)
        }
        return keys.add(Key(value, queue))
    }
}
