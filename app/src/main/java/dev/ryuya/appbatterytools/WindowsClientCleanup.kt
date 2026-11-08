package dev.ryuya.appbatterytools

import java.lang.reflect.Method
import java.util.concurrent.CompletableFuture

/** Close only an incoming client for which the completed handoff explicitly has no consumer. */
internal class WindowsClientCleanup(
    private val futureGetter: Method,
    private val dataGetter: Method,
    private val close: Method,
) {
    private val closed = WeakIdentitySet()

    fun afterOpened(adapter: Any, client: Any): Boolean {
        val future = futureGetter.invoke(adapter) as CompletableFuture<*>
        if (!future.isDone || future.isCompletedExceptionally || future.isCancelled) return false
        val result = future.getNow(null) ?: return false
        if (!LateClientPolicy.shouldClose(true, false, dataGetter.invoke(result), client))
            return false
        if (!closed.add(client)) return false
        close.invoke(client)
        return true
    }
}
