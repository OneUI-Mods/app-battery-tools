package dev.ryuya.appbatterytools

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.util.concurrent.locks.ReentrantReadWriteLock

/** Keep a platform callback on its original generation, including chained hooks. */
internal class BatteryHookSession(private val framework: XposedInterface) {
    private val active = ReentrantReadWriteLock()
    private val readiness = ArrayList<() -> Boolean>()
    private val cleanup = ArrayList<() -> Unit>()
    @Volatile private var retired = false

    fun hook(target: Executable) = Target(target)

    inner class Target(private val method: Executable) {
        fun intercept(callback: XposedInterface.Hooker): XposedInterface.HookHandle =
            framework.hook(method).intercept { chain ->
                if (retired) chain.proceed()
                else {
                    val lock = active.readLock()
                    lock.lock()
                    try {
                        if (retired) chain.proceed() else callback.intercept(chain)
                    } finally {
                        lock.unlock()
                    }
                }
            }
    }

    fun beforeRetirement(test: () -> Boolean) {
        readiness.add(test)
    }

    fun onRetire(action: () -> Unit) {
        cleanup.add(action)
    }

    fun tryRetire(save: () -> Unit): Boolean {
        val lock = active.writeLock()
        if (!lock.tryLock()) return false
        try {
            if (!readiness.all { it() }) return false
            save()
            retired = true
        } finally {
            lock.unlock()
        }
        for (action in cleanup.asReversed()) {
            try {
                action()
            } catch (error: Exception) {
                framework.log(
                    Log.WARN,
                    "AppBatteryTools",
                    "Could not retire a host listener",
                    error,
                )
            }
        }
        cleanup.clear()
        readiness.clear()
        return true
    }
}
