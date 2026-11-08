package dev.ryuya.appbatterytools

import android.app.Application
import android.app.Instrumentation
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/** Only the area service owns cleanup. Original request parameters and results pass through. */
class HookEntry : XposedModule() {
    private var areaSettingsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var lifecyclePrepared = false

    override fun onPackageReady(param: PackageReadyParam) {
        if (
            lifecyclePrepared ||
                param.packageName != Targets.DIGIPOLICE ||
                Application.getProcessName() != Targets.DIGIPOLICE
        )
            return
        lifecyclePrepared = true
        val application =
            try {
                Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentApplication")
                    .invoke(null) as? Application
            } catch (_: ReflectiveOperationException) {
                null
            }
        if (application?.baseContext != null && application.packageName == Targets.DIGIPOLICE) {
            initialize(application)
            return
        }
        // API 102 PackageReady can arrive after Application.attach. Instrumentation still owns
        // the subsequent onCreate call, including for applications overriding onCreate.
        hook(
                Instrumentation::class
                    .java
                    .getDeclaredMethod("callApplicationOnCreate", Application::class.java),
            )
            .intercept { chain ->
                val context = chain.args[0] as Application
                initialize(context)
                chain.proceed()
            }
    }

    private fun initialize(context: Context) {
        try {
            install(context)
        } catch (error: Exception) {
            report(context, "error", error)
        } catch (error: LinkageError) {
            report(context, "error", error)
        }
    }

    private fun install(context: Context) {
        val config = context.contentResolver.call(ModuleProvider.URI, "settings", null, null)
        if (config == null || !config.getBoolean("ready")) {
            report(context, "not_ready")
            return
        }
        if (!config.getBoolean("enabled") || !config.getBoolean("digipolice")) {
            report(context, "disabled")
            return
        }
        val loader = context.classLoader
        fun type(key: String) = Class.forName(checkNotNull(config.getString(key)), false, loader)
        val service = type("service")
        check(Service::class.java.isAssignableFrom(service))
        // The proof must belong to this exact installed APK, never a plugin with a similar name.
        check(config.getString("apk") == context.applicationInfo.sourceDir)
        val client = type("client")
        val callback = type("callback")
        val request =
            Class.forName("com.google.android.gms.location.LocationRequest", false, loader)
        val register =
            client.getDeclaredMethod(
                config.getString("register"),
                request,
                callback,
                Looper::class.java,
            )
        val remove = client.getDeclaredMethod(config.getString("remove"), callback)
        check(
            register.returnType == remove.returnType &&
                register.returnType.name == config.getString("task"),
        )
        val start =
            service.getDeclaredMethod(
                "onStartCommand",
                Intent::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
        val destroy =
            try {
                service.getDeclaredMethod("onDestroy")
            } catch (_: NoSuchMethodException) {
                Service::class.java.getDeclaredMethod("onDestroy")
            }
        remove.isAccessible = true
        val owner = ThreadLocal<Any?>()
        var healthy = true
        val preferences =
            context.getSharedPreferences(Targets.AREA_PREFERENCES, Context.MODE_PRIVATE)
        val settingsReviewed = config.getString("area_guard") == "reviewed"
        fun suppressAreaService(): Boolean {
            val stored =
                try {
                    if (preferences.contains(Targets.AREA_KEY))
                        preferences.getBoolean(Targets.AREA_KEY, true)
                    else null
                } catch (_: ClassCastException) {
                    null
                }
            return AreaPolicy.shouldSuppress(settingsReviewed, stored)
        }
        val main = Handler(Looper.getMainLooper())
        val stopDisabledService = Runnable {
            if (suppressAreaService()) {
                context.stopService(Intent(context, service))
                report(context, "suppressed")
            }
        }
        val leases =
            LocationLeases<Any, Any, Any>(
                { c, listener ->
                    remove.invoke(c, listener)
                    Unit
                },
                { error -> report(context, "error", error) },
            )
        val installed = mutableListOf<HookHandle>()
        try {
            installed +=
                hook(register).intercept { chain ->
                    val current = owner.get()
                    val result = chain.proceed()
                    if (current != null && healthy) {
                        try {
                            leases.registered(
                                current,
                                checkNotNull(chain.thisObject),
                                checkNotNull(chain.args[1]),
                            )
                        } catch (error: Exception) {
                            healthy = false
                            report(context, "error", error)
                        }
                    }
                    result
                }
            installed +=
                hook(start).intercept { chain ->
                    val instance = checkNotNull(chain.thisObject)
                    if (suppressAreaService()) {
                        (instance as Service).stopSelf(chain.args[2] as Int)
                        report(context, "suppressed")
                        return@intercept Service.START_NOT_STICKY
                    }
                    val previous = owner.get()
                    if (healthy) {
                        leases.start(instance)
                        owner.set(instance)
                    }
                    try {
                        chain.proceed()
                    } finally {
                        owner.set(previous)
                    }
                }
            installed +=
                hook(destroy).intercept { chain ->
                    try {
                        chain.proceed()
                    } finally {
                        if (service.isInstance(chain.thisObject)) {
                            val result = leases.stop(checkNotNull(chain.thisObject))
                            if (result.requested > 0 && result.failed == 0)
                                report(context, "removal_requested", released = result.requested)
                        }
                    }
                }
            if (settingsReviewed) {
                areaSettingsListener =
                    SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                        if (key == Targets.AREA_KEY && suppressAreaService()) {
                            main.removeCallbacks(stopDisabledService)
                            main.post(stopDisabledService)
                        }
                    }
                preferences.registerOnSharedPreferenceChangeListener(areaSettingsListener)
            }
            report(context, "active")
        } catch (error: Throwable) {
            // No partial interception survives an installation failure.
            for (handle in installed.asReversed()) runCatching { handle.unhook() }
            areaSettingsListener?.let(preferences::unregisterOnSharedPreferenceChangeListener)
            areaSettingsListener = null
            main.removeCallbacks(stopDisabledService)
            throw error
        }
    }

    private fun report(
        context: Context,
        state: String,
        error: Throwable? = null,
        released: Int? = null,
    ) {
        try {
            context.contentResolver.call(
                ModuleProvider.URI,
                "report",
                null,
                Bundle().apply {
                    putString("state", state)
                    if (error != null) putString("error", error.javaClass.simpleName)
                    released?.let { putInt("released", it) }
                },
            )
        } catch (_: Exception) {
            /* Diagnostics never interrupt a host callback. */
        }
    }
}
