package dev.ryuya.appbatterytools

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedModule
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod

/** Event-bound cleanup in the host app. No additional periodic work. */
internal class CleanupHooks(
    private val module: XposedModule,
    private val context: Context,
    private val report: (String, Throwable?, Int?) -> Unit,
    private val changed: () -> Unit,
) {
    private val handles = mutableListOf<HookHandle>()

    private fun method(config: Bundle, key: String) =
        DexMethod(checkNotNull(config.getString(key)))
            .getMethodInstance(context.classLoader)
            .apply { isAccessible = true }

    private fun field(config: Bundle, key: String) =
        DexField(checkNotNull(config.getString(key))).getFieldInstance(context.classLoader).apply {
            isAccessible = true
        }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (error: Exception) {
            report("error", error, null)
        } catch (error: LinkageError) {
            report("error", error, null)
        }
    }

    fun install(config: Bundle) {
        try {
            when (context.packageName) {
                Targets.WEATHER -> weather(config)
                Targets.SMARTTHINGS -> smartThings(config)
                Targets.TAPO -> tapo(config)
                Targets.SIMEJI -> {
                    handles +=
                        module.hook(method(config, "simeji_schedule")).intercept {
                            changed()
                            null // Only the reviewed SDK ANR-polling registration is suppressed.
                        }
                }
                else -> error("Unknown cleanup target")
            }
        } catch (failure: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() } }
            throw failure
        }
    }

    private fun weather(config: Bundle) {
        val timer = OwnedTimerCleanup(field(config, "weather_timer"))
        handles +=
            module.hook(method(config, "weather_stop")).intercept { chain ->
                safely {
                    if (timer.beforeStop(chain.thisObject)) changed()
                }
                chain.proceed()
            }
    }

    private class SmartFrame(
        val owner: Any,
        val emitter: Any,
        val ticket: ResourceHandoffs.Ticket<WifiManager.MulticastLock>,
    )

    private fun smartThings(config: Bundle) {
        val lock = field(config, "smart_lock")
        val captures =
            checkNotNull(config.getString("smart_owner_fields")).lines().map {
                DexField(it).getFieldInstance(context.classLoader).apply { isAccessible = true }
            }
        val handoffs = ResourceHandoffs<WifiManager.MulticastLock>()
        val current = ThreadLocal<SmartFrame?>()
        handles +=
            module
                .hook(
                    WifiManager::class
                        .java
                        .getDeclaredMethod("createMulticastLock", String::class.java),
                )
                .intercept { chain ->
                    val result = chain.proceed()
                    val frame = current.get()
                    if (
                        frame != null &&
                            chain.args[0] == lock.declaringClass.name &&
                            result is WifiManager.MulticastLock
                    ) {
                        handoffs.created(frame.ticket, result)
                    }
                    result
                }
        handles +=
            module.hook(method(config, "smart_cancellable")).intercept { chain ->
                val frame = current.get()?.takeIf { it.emitter === chain.thisObject }
                // Capture before setCancellable: an already cancelled emitter may synchronously
                // dispose the resource and clear its field during the original call.
                val resource = frame?.let { lock.get(it.owner) as? WifiManager.MulticastLock }
                val result = chain.proceed()
                if (frame != null && chain.args[0] != null)
                    handoffs.accepted(frame.ticket, resource)
                result
            }
        handles +=
            module.hook(method(config, "smart_subscribe")).intercept { chain ->
                val instance = chain.thisObject ?: return@intercept chain.proceed()
                val owners =
                    captures
                        .mapNotNull { it.get(instance) }
                        .filter { lock.declaringClass.isInstance(it) }
                val owner =
                    owners.firstOrNull()?.takeIf { candidate -> owners.all { it === candidate } }
                        ?: return@intercept chain.proceed()
                val emitter = chain.args[0] ?: return@intercept chain.proceed()
                val previous = current.get()
                val frame = SmartFrame(owner, emitter, handoffs.begin(owner))
                current.set(frame)
                try {
                    chain.proceed()
                } finally {
                    current.set(previous)
                    safely {
                        handoffs
                            .finish(frame.ticket) { resource ->
                                if (resource.isHeld) {
                                    resource.release()
                                    changed()
                                }
                                if (lock.get(owner) === resource) lock.set(owner, null)
                            }
                            .forEach { report("error", it, null) }
                    }
                }
            }
    }

    private fun tapo(config: Bundle) {
        val lock = field(config, "tapo_lock")
        val callbacks = field(config, "tapo_callbacks")
        val current = ThreadLocal<AcquiredReferences<WifiManager.MulticastLock>?>()
        handles +=
            module
                .hook(WifiManager.MulticastLock::class.java.getDeclaredMethod("acquire"))
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? WifiManager.MulticastLock)?.let {
                        current.get()?.acquired(it)
                    }
                    result
                }
        handles +=
            module.hook(method(config, "tapo_start")).intercept { chain ->
                val instance = chain.thisObject ?: return@intercept chain.proceed()
                val resource =
                    lock.get(instance) as? WifiManager.MulticastLock
                        ?: return@intercept chain.proceed()
                val handle = chain.args[1] as? Long ?: return@intercept chain.proceed()
                val previous = current.get()
                val acquisitions = AcquiredReferences(resource)
                current.set(acquisitions)
                var completed = false
                try {
                    val result = chain.proceed()
                    completed = true
                    result
                } finally {
                    current.set(previous)
                    safely {
                        val registered =
                            (callbacks.get(instance) as? Map<*, *>)?.containsKey(handle) ?: true
                        acquisitions.rollback(completed, registered) {
                            // Release only this call's acquisitions, retaining other active
                            // browsers.
                            if (it.isHeld) {
                                it.release()
                                changed()
                            }
                        }
                    }
                }
            }
    }
}
