package dev.ryuya.appbatterytools

import android.app.Instrumentation
import android.os.Bundle
import dalvik.system.DexClassLoader
import java.io.File
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod

/** Parses private, supplied APK fixtures; never starts the third-party applications. */
class DiscoveryInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = JSONObject()
        var successful = true
        for (name in
            listOf(
                Targets.WINDOWS,
                Targets.EQ,
                Targets.ECOVACS,
                Targets.WEATHER,
                Targets.SMARTTHINGS,
                Targets.TAPO,
                Targets.SIMEJI,
                Targets.LANDROP,
                Targets.CONTINUITY,
            )) {
            try {
                val file = File(targetContext.filesDir, "fixtures/$name.apk")
                check(file.isFile)
                val bindings = ExtraDiscovery.explore(name, file.path)
                DiscoveryBindings.checked(bindings, Targets.optionKeys)
                result.put(name, JSONObject(bindings))
                if (name == Targets.WINDOWS) verifyWindowsHandoff(file, bindings)
                if (name == Targets.EQ) verifyRouteIdentity(file, bindings)
                if (name == Targets.WEATHER) verifyWeatherStop(file, bindings)
                if (
                    name in
                        listOf(
                            Targets.SMARTTHINGS,
                            Targets.TAPO,
                            Targets.SIMEJI,
                            Targets.LANDROP,
                            Targets.CONTINUITY,
                        )
                )
                    verifyCleanupBindings(file, bindings)
                if (name == Targets.LANDROP) verifyFlutterNativeState(file, bindings)
            } catch (error: Throwable) {
                successful = false
                result.put(name, error.stackTraceToString().take(4000))
            }
        }
        val output = Bundle().apply { putString("result", result.toString()) }
        finish(if (successful) -1 else 0, output)
    }

    private fun verifyFlutterNativeState(file: File, bindings: Map<String, String?>) {
        val loader = loader(file)
        val attached =
            DexMethod(checkNotNull(bindings["landrop_attached"])).getMethodInstance(loader)
        val unsafeType = Class.forName("sun.misc.Unsafe")
        val unsafe =
            unsafeType.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        // An isolated empty Java wrapper, never attached to a native Flutter engine.
        val jni =
            unsafeType
                .getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, attached.declaringClass)
        val handle =
            attached.declaringClass.declaredFields
                .single { it.type == java.lang.Long::class.java }
                .apply { isAccessible = true }
        check(attached.invoke(jni) == false)
        check(EngineShutdownPolicy.mayRelease(true, true, attached.invoke(jni) as Boolean, true))
        try {
            handle.set(jni, 1L)
            check(attached.invoke(jni) == true)
            check(
                !EngineShutdownPolicy.mayRelease(true, true, attached.invoke(jni) as Boolean, true),
            )
        } finally {
            handle.set(jni, null)
        }
    }

    private fun verifyCleanupBindings(file: File, bindings: Map<String, String?>) {
        val loader = loader(file)
        for ((key, value) in bindings) {
            if (key == "contract" || value == null) continue
            for (descriptor in value.lines()) {
                if (descriptor.contains("(")) DexMethod(descriptor).getMethodInstance(loader)
                else DexField(descriptor).getFieldInstance(loader)
            }
        }
    }

    /** Invoke the real APK stop method on an unattached, empty Application. Never run onCreate. */
    private fun verifyWeatherStop(file: File, bindings: Map<String, String?>) {
        val loader = loader(file)
        val stop =
            DexMethod(checkNotNull(bindings["weather_stop"])).getMethodInstance(loader).apply {
                isAccessible = true
            }
        val field =
            DexField(checkNotNull(bindings["weather_timer_field"])).getFieldInstance(loader).apply {
                isAccessible = true
            }
        val unsafeType = Class.forName("sun.misc.Unsafe")
        val unsafe =
            unsafeType.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val app =
            unsafeType
                .getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, stop.declaringClass)
        fun task() =
            object : TimerTask() {
                override fun run() = Unit
            }
        val original = Timer("OriginalWeatherStopProbe", true)
        try {
            original.scheduleAtFixedRate(task(), 3_600_000, 3_600_000)
            field.set(app, original)
            stop.invoke(app)
            check(field.get(app) == null)
            original.schedule(task(), 3_600_000) // Reproduce: original stop did not cancel.
        } finally {
            original.cancel()
        }
        val fixed = Timer("FixedWeatherStopProbe", true)
        try {
            fixed.scheduleAtFixedRate(task(), 3_600_000, 3_600_000)
            field.set(app, fixed)
            val cleanup = OwnedTimerCleanup(field)
            check(cleanup.beforeStop(app))
            stop.invoke(app)
            check(field.get(app) == null)
            check(!cleanup.beforeStop(app))
            var cancelled = false
            try {
                fixed.schedule(task(), 3_600_000)
            } catch (_: IllegalStateException) {
                cancelled = true
            }
            check(cancelled)
        } finally {
            fixed.cancel()
        }
    }

    private fun loader(file: File): DexClassLoader {
        check(file.setReadOnly())
        return DexClassLoader(
            file.path,
            targetContext.codeCacheDir.path,
            null,
            targetContext.classLoader.parent,
        )
    }

    private fun verifyWindowsHandoff(file: File, bindings: Map<String, String?>) {
        val loader = loader(file)
        fun method(key: String) =
            DexMethod(checkNotNull(bindings[key])).getMethodInstance(loader).apply {
                isAccessible = true
            }
        check(
            method("database").returnType.methods.count {
                it.name == "close" && it.parameterCount == 0 && it.returnType == Void.TYPE
            } == 1,
        )
        val opened = method("opened")
        val getter = method("opened_future")
        val data = method("result_data")
        val clientClose = method("client_close")
        val constructor =
            opened.declaringClass.declaredConstructors.single { it.parameterCount == 4 }
        fun proxy(type: Class<*>, onCall: (String) -> Unit): Any =
            Proxy.newProxyInstance(loader, arrayOf(type)) { instance, call, arguments ->
                onCall(call.name)
                when (call.name) {
                    "equals" -> instance === arguments?.get(0)
                    "hashCode" -> System.identityHashCode(instance)
                    "toString" -> "mock"
                    else ->
                        when (call.returnType) {
                            Boolean::class.javaPrimitiveType -> false
                            Int::class.javaPrimitiveType -> 0
                            else -> null
                        }
                }
            }
        fun adapter(): Any =
            constructor.newInstance(
                proxy(constructor.parameterTypes[0]) {},
                proxy(constructor.parameterTypes[1]) {},
                Duration.ofSeconds(1),
                Duration.ofSeconds(15),
            )
        val stopped = adapter()
        val closeCount = AtomicInteger()
        val incoming =
            proxy(opened.parameterTypes.single()) {
                if (it == "Close") closeCount.incrementAndGet()
            }
        stopped.javaClass.getDeclaredMethod("close").invoke(stopped)
        opened.invoke(stopped, incoming)
        check(closeCount.get() == 0) // Reproduce the upstream abandoned-client handoff.
        val cleanup = WindowsClientCleanup(getter, data, clientClose)
        check(cleanup.afterOpened(stopped, incoming))
        check(closeCount.get() == 1)
        check(!cleanup.afterOpened(stopped, incoming))
        check(closeCount.get() == 1)
        val successful = adapter()
        val active =
            proxy(opened.parameterTypes.single()) {
                if (it == "Close") error("Active client closed")
            }
        opened.invoke(successful, active)
        check(!cleanup.afterOpened(successful, active))
    }

    private fun verifyRouteIdentity(file: File, bindings: Map<String, String?>) {
        val loader = loader(file)
        val method = DexMethod(checkNotNull(bindings["eq_identity"])).getMethodInstance(loader)
        val instance = method.declaringClass.getDeclaredField("INSTANCE").get(null)
        val memo = BoundedMemo<Triple<String, String, String>, Any?>(32)
        for (address in listOf("one", "two", "one")) {
            val args = Triple("usb", address, "test device")
            val original = method.invoke(instance, args.first, args.second, args.third)
            val optimized =
                memo.getOrCompute(args) {
                    method.invoke(instance, args.first, args.second, args.third)
                }
            check(original == optimized)
        }
    }
}
