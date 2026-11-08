package dev.ryuya.appbatterytools

import android.app.Instrumentation
import android.os.Bundle
import dalvik.system.DexClassLoader
import java.io.File
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
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
        for (name in listOf(Targets.WINDOWS, Targets.EQ, Targets.ECOVACS)) {
            try {
                val file = File(targetContext.filesDir, "fixtures/$name.apk")
                check(file.isFile)
                val bindings = ExtraDiscovery.explore(name, file.path)
                result.put(name, JSONObject(bindings))
                if (name == Targets.WINDOWS) verifyWindowsHandoff(file, bindings)
                if (name == Targets.EQ) verifyRouteIdentity(file, bindings)
            } catch (error: Throwable) {
                successful = false
                result.put(name, error.stackTraceToString().take(4000))
            }
        }
        val output = Bundle().apply { putString("result", result.toString()) }
        finish(if (successful) -1 else 0, output)
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
