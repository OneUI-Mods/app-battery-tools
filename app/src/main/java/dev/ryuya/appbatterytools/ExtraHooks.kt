package dev.ryuya.appbatterytools

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod

internal class ExtraHooks(
    private val module: XposedModule,
    private val context: Context,
    private val report: (String, Throwable?, Int?) -> Unit,
) {
    private val installed = java.util.concurrent.CopyOnWriteArrayList<HookHandle>()
    private val actions = AtomicInteger()

    private fun method(config: Bundle, key: String): Method =
        DexMethod(checkNotNull(config.getString(key)))
            .getMethodInstance(context.classLoader)
            .apply { isAccessible = true }

    private fun changed() {
        // EQ can reuse a calculation every second. Per-hit reporting would keep the module awake.
        if (actions.incrementAndGet() == 1) report("active", null, 1)
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (failure: Exception) {
            report("error", failure, null)
        } catch (failure: LinkageError) {
            report("error", failure, null)
        }
    }

    fun install(config: Bundle) {
        val target = checkNotNull(Targets.find(context.packageName))
        if (target.options.none { config.getBoolean(it.key) }) {
            report("disabled", null, null)
            return
        }
        try {
            when (context.packageName) {
                Targets.WINDOWS -> {
                    if (config.getBoolean("windows_database")) windowsDatabase(config)
                    if (config.getBoolean("windows_connections")) windowsClients(config)
                }
                Targets.GOVEE,
                Targets.SESAME -> {
                    BleWifiHooks(module, context, report).install()
                    return
                }
                Targets.EQ -> equalizer(config)
                Targets.ECOVACS -> ecovacs(config)
                Targets.LANDROP,
                Targets.CONTINUITY ->
                    LifecycleHooks(module, context, report, ::changed).install(config)
                Targets.SEAD -> {
                    if (config.getString("contract") == "sead-private-worker-unavailable") {
                        report("unsupported", null, null)
                        return
                    }
                    LifecycleHooks(module, context, report, ::changed).install(config)
                }
                Targets.WEATHER,
                Targets.SMARTTHINGS,
                Targets.TAPO,
                Targets.SIMEJI -> CleanupHooks(module, context, report, ::changed).install(config)
            }
            report("active", null, null)
        } catch (failure: Throwable) {
            installed.asReversed().forEach { runCatching { it.unhook() } }
            throw failure
        }
    }

    private fun windowsDatabase(config: Bundle) {
        val factory = method(config, "database")
        val shared = BoundedMemo<Unit, Any?>(1)
        val close =
            factory.returnType.methods.single {
                it.name == "close" && it.parameterCount == 0 && it.returnType == Void.TYPE
            }
        installed +=
            module.hook(close).intercept { chain ->
                shared.invalidate(chain.thisObject)
                chain.proceed()
            }
        installed +=
            module.hook(factory).intercept { chain ->
                var created = false
                val result =
                    shared.getOrCompute(Unit) {
                        created = true
                        chain.proceed()
                    }
                if (result == null) shared.invalidate(null)
                if (!created) changed()
                result
            }
    }

    private fun windowsClients(config: Bundle) {
        val cleanup =
            WindowsClientCleanup(
                method(config, "opened_future"),
                method(config, "result_data"),
                method(config, "client_close"),
            )
        installed +=
            module.hook(method(config, "opened")).intercept { chain ->
                val result = chain.proceed()
                safely {
                    if (
                        cleanup.afterOpened(
                            checkNotNull(chain.thisObject),
                            checkNotNull(chain.args[0]),
                        )
                    )
                        changed()
                }
                result
            }
    }

    private fun equalizer(config: Bundle) {
        val identity = method(config, "eq_identity")
        val select = method(config, "eq_select")
        val hashes = BoundedMemo<Triple<String, String, String>, Any?>(32)
        val patterns = BoundedMemo<String, Any>(2)
        val selecting = ThreadLocal<Boolean>()
        installed +=
            module.hook(identity).intercept { chain ->
                if (chain.args.any { it !is String }) return@intercept chain.proceed()
                val key =
                    Triple(
                        chain.args[0] as String,
                        chain.args[1] as String,
                        chain.args[2] as String,
                    )
                var computed = false
                val result =
                    hashes.getOrCompute(key) {
                        computed = true
                        chain.proceed()
                    }
                if (!computed) changed()
                result
            }
        installed +=
            module.hook(select).intercept { chain ->
                val previous = selecting.get()
                selecting.set(true)
                try {
                    chain.proceed()
                } finally {
                    selecting.set(previous)
                }
            }
        installed +=
            module
                .hook(
                    java.util.regex.Pattern::class
                        .java
                        .getDeclaredMethod(
                            "compile",
                            String::class.java,
                        ),
                )
                .intercept { chain ->
                    if (selecting.get() != true || chain.args[0] != "[0-9a-f]{64}")
                        return@intercept chain.proceed()
                    patterns.getOrCompute("[0-9a-f]{64}") { checkNotNull(chain.proceed()) }
                }
    }

    private fun ecovacs(config: Bundle) {
        val loader = context.classLoader
        val field =
            DexField(checkNotNull(config.getString("matter_lock"))).getFieldInstance(loader).apply {
                isAccessible = true
            }
        val controller =
            DexField(checkNotNull(config.getString("matter_controller")))
                .getFieldInstance(loader)
                .apply { isAccessible = true }
        val lifecycle = MatterLifecycle()
        fun acquire() {
            val lock = field.get(null) as? WifiManager.MulticastLock ?: return
            if (!lock.isHeld) lock.acquire()
        }
        fun release() {
            val lock = field.get(null) as? WifiManager.MulticastLock ?: return
            if (controller.get(null) == null && lock.isHeld) {
                lock.release()
                changed()
            }
        }
        installed +=
            module.hook(method(config, "matter_initialize")).intercept { chain ->
                val result = chain.proceed()
                safely { synchronized(lifecycle) { acquire() } }
                result
            }
        installed +=
            module.hook(method(config, "matter_create")).intercept { chain ->
                safely {
                    synchronized(lifecycle) {
                        lifecycle.creating()
                        acquire()
                    }
                }
                try {
                    chain.proceed()
                } finally {
                    safely {
                        synchronized(lifecycle) {
                            val present = controller.get(null) != null
                            if (present) acquire()
                            if (lifecycle.created(present)) release()
                        }
                    }
                }
            }
        installed +=
            module.hook(method(config, "matter_destroy")).intercept { chain ->
                try {
                    chain.proceed()
                } finally {
                    safely {
                        synchronized(lifecycle) {
                            if (lifecycle.stopped(controller.get(null) != null)) release()
                        }
                    }
                }
            }
    }
}
