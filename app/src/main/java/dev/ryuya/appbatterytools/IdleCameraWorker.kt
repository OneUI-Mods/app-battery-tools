package dev.ryuya.appbatterytools

import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Called after the host's onClosed. Queued work and future submissions remain valid. */
internal object IdleCameraWorker {
    fun retire(executor: Any?, expectedFactory: ThreadFactory?): Boolean {
        if (expectedFactory == null) return false
        val pool = unwrap(executor) ?: return false
        if (
            pool.threadFactory !== expectedFactory ||
                pool.isShutdown ||
                pool.corePoolSize != 1 ||
                pool.maximumPoolSize != 1 ||
                pool.allowsCoreThreadTimeOut() ||
                pool.getKeepAliveTime(TimeUnit.NANOSECONDS) != 0L
        )
            return false
        // No shutdown, cancellation, interruption, queue draining or parallelism change.
        pool.setKeepAliveTime(1, TimeUnit.SECONDS)
        pool.allowCoreThreadTimeOut(true)
        return true
    }

    internal fun unwrap(executor: Any?): ThreadPoolExecutor? {
        var current = executor
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        repeat(4) {
            val value = current ?: return null
            if (!seen.add(value)) return null
            if (value is ThreadPoolExecutor) return value
            if (value !is Executor) return null
            val fields =
                generateSequence(value.javaClass as Class<*>?) { it.superclass }
                    .flatMap { it.declaredFields.asSequence() }
                    .filter {
                        !Modifier.isStatic(it.modifiers) &&
                            Executor::class.java.isAssignableFrom(it.type)
                    }
                    .toList()
            val delegates = Collections.newSetFromMap(IdentityHashMap<Any?, Boolean>())
            for (field in fields) {
                field.isAccessible = true
                delegates.add(field.get(value))
            }
            current = delegates.singleOrNull() ?: return null
        }
        return null
    }
}
