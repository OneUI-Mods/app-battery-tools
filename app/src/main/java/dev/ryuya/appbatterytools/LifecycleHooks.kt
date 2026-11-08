package dev.ryuya.appbatterytools

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedModule
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod

/** No work during normal transfers or discovery: cleanup follows original termination only. */
internal class LifecycleHooks(
    private val module: XposedModule,
    private val context: Context,
    private val report: (String, Throwable?, Int?) -> Unit,
    private val changed: () -> Unit,
) {
    private val hooks = mutableListOf<HookHandle>()

    private fun field(config: Bundle, key: String) =
        DexField(checkNotNull(config.getString(key))).getFieldInstance(context.classLoader).apply {
            isAccessible = true
        }

    private fun method(config: Bundle, key: String) =
        DexMethod(checkNotNull(config.getString(key)))
            .getMethodInstance(context.classLoader)
            .apply { isAccessible = true }

    private inline fun <T> safely(block: () -> T): T? =
        try {
            block()
        } catch (failure: Exception) {
            report("error", failure, null)
            null
        } catch (failure: LinkageError) {
            report("error", failure, null)
            null
        }

    fun install(config: Bundle) {
        try {
            when (context.packageName) {
                Targets.LANDROP -> landrop(config)
                Targets.CONTINUITY -> continuity(config)
                else -> error("Unknown lifecycle target")
            }
        } catch (failure: Throwable) {
            hooks.asReversed().forEach { runCatching { it.unhook() } }
            throw failure
        }
    }

    private fun landrop(config: Bundle) {
        val lock = field(config, "landrop_lock_field")
        val delegate = field(config, "landrop_delegate_field")
        val engine = field(config, "landrop_engine_field")
        val jni = field(config, "landrop_jni_field")
        val policy = method(config, "landrop_owner_policy")
        val attached = method(config, "landrop_attached")
        hooks +=
            module.hook(method(config, "landrop_destroy")).intercept { chain ->
                val instance = chain.thisObject
                if (!lock.declaringClass.isInstance(instance)) return@intercept chain.proceed()
                val state = safely {
                    val owned = policy.invoke(instance) as? Boolean
                    val resource = lock.get(instance) as? WifiManager.MulticastLock
                    if (owned != true || resource == null) return@safely null
                    val native = delegate.get(instance)?.let { engine.get(it) }?.let { jni.get(it) }
                    Triple(owned, resource, native)
                }
                val result =
                    chain.proceed() // No cleanup if native teardown throws or does not finish.
                if (state != null)
                    safely {
                        val (owned, resource, native) = state
                        val isAttached = native?.let { attached.invoke(it) as? Boolean }
                        if (
                            EngineShutdownPolicy.mayRelease(
                                owned,
                                true,
                                isAttached,
                                lock.get(instance) === resource,
                            )
                        ) {
                            if (resource.isHeld) {
                                resource.release()
                                changed()
                            }
                            if (lock.get(instance) === resource) lock.set(instance, null)
                        }
                    }
                result
            }
    }

    private fun continuity(config: Bundle) {
        val scanner = field(config, "continuity_scanner_field")
        val callbacks = field(config, "continuity_callbacks_field")
        val current = ThreadLocal<ScopedStopBatch<BluetoothLeScanner, ScanCallback>?>()
        hooks +=
            module
                .hook(
                    BluetoothLeScanner::class
                        .java
                        .getDeclaredMethod("stopScan", ScanCallback::class.java),
                )
                .intercept { chain ->
                    val frame = current.get() ?: return@intercept chain.proceed()
                    frame.attempt(
                        chain.thisObject as? BluetoothLeScanner,
                        chain.args[0] as? ScanCallback,
                    ) {
                        chain.proceed()
                    }
                }
        hooks +=
            module.hook(method(config, "continuity_stop")).intercept { chain ->
                val frame =
                    safely {
                        val owner = scanner.get(chain.thisObject) as? BluetoothLeScanner
                        val values = callbacks.get(chain.thisObject) as? Collection<*>
                        if (
                            owner == null ||
                                values == null ||
                                values.size > 256 ||
                                values.any { it !is ScanCallback }
                        )
                            null
                        else ScopedStopBatch(owner, values.filterIsInstance<ScanCallback>())
                    } ?: return@intercept chain.proceed()
                val previous = current.get()
                current.set(frame)
                try {
                    chain.proceed()
                } finally {
                    if (previous == null) current.remove() else current.set(previous)
                    if (frame.failures > 0) report("cleanup_partial", frame.firstFailure, null)
                    else if (frame.requested > 0) changed()
                }
            }
    }
}
