package dev.ryuya.appbatterytools

import android.app.Application
import android.app.BroadcastOptions
import android.app.Instrumentation
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import dev.ryuya.appbatterytools.startup.FirstUnlockGate
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.util.IdentityHashMap

/** Only the area service owns cleanup. Original request parameters and results pass through. */
class HookEntry : XposedModule() {
    private var areaSettingsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var lifecyclePrepared = false
    private var initialized = false
    private val firstUnlock = FirstUnlockGate()
    private val session = BatteryHookSession(this)
    private val main = Handler(Looper.getMainLooper())
    private var readyHost: Context? = null
    private var onInstalled: (() -> Unit)? = null
    @Volatile private var reconnectHost: Context? = null
    @Volatile private var retired = false
    private var accessRequested = false
    private var discoveryObserver: ContentObserver? = null

    override fun onPackageReady(param: PackageReadyParam) {
        Log.i(
            "AppBatteryTools",
            "PackageReady ${param.packageName}; build=${BuildConfig.VERSION_CODE}",
        )
        if (param.isFirstPackage && Targets.find(param.packageName) == null) {
            // Scope is chosen in the framework. Processes without a supported app keep no module
            // code.
            detach()
            return
        }
        if (lifecyclePrepared || Targets.find(param.packageName) == null) return
        lifecyclePrepared = true
        if (param.packageName == Targets.GOVEE) {
            // The protected bootstrap calls Application.attach reflectively for its real app.
            // Do not intercept that bootstrap or Instrumentation. One queued callback runs
            // after the synchronous application creation; it is not a polling loop.
            Handler(Looper.getMainLooper()).post {
                val ready = currentApplication()
                if (ready?.baseContext != null && ready.packageName == Targets.GOVEE) {
                    initialize(ready)
                } else Log.w("AppBatteryTools", "Protected application was not ready")
            }
            return
        }
        val application = currentApplication()
        if (application?.baseContext != null && Targets.find(application.packageName) != null) {
            initialize(application)
            return
        }
        session
            .hook(Application::class.java.getDeclaredMethod("attach", Context::class.java))
            .intercept { chain ->
                val result = chain.proceed()
                initialize(chain.args[0] as Context)
                result
            }
        // API 102 PackageReady can arrive after Application.attach. Instrumentation still owns
        // the subsequent onCreate call, including for applications overriding onCreate.
        session
            .hook(
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

    override fun onHotReloading(param: HotReloadingParam): Boolean =
        MainThreadReload.run(main) handOff@{
            val context = readyHost ?: reconnectHost ?: return@handOff false
            if (!session.tryRetire { param.setSavedInstanceState(context) }) return@handOff false
            retired = true
            discoveryObserver?.let {
                runCatching { context.contentResolver.unregisterContentObserver(it) }
                discoveryObserver = null
            }
            main.removeCallbacksAndMessages(null)
            onInstalled = null
            true
        }

    override fun onHotReloaded(param: HotReloadedParam) {
        val context = requireNotNull(param.savedInstanceState as? Context)
        check(Targets.find(context.packageName) != null)
        val oldHooks = param.oldHookHandles
        reconnectHost = context
        check(
            MainThreadReload.run(main) {
                lifecyclePrepared = true
                onInstalled = {
                    reconnectHost = null
                    oldHooks.forEach { it.unhook() }
                    Log.i(
                        "AppBatteryTools",
                        "ONEUI_MODS_HOT_RELOAD package=${BuildConfig.APPLICATION_ID} " +
                            "version=${BuildConfig.VERSION_CODE} pid=${Process.myPid()}",
                    )
                }
                initialize(context)
                true
            },
        ) {
            "Battery host main thread was unavailable"
        }
    }

    private fun currentApplication(): Application? =
        try {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication")
                .invoke(null) as? Application
        } catch (_: ReflectiveOperationException) {
            null
        }

    private fun initialize(context: Context) {
        if (retired || initialized || Targets.find(context.packageName) == null) return
        firstUnlock.run(context) { initializeUnlocked(context) }
    }

    private fun initializeUnlocked(context: Context) {
        if (retired || initialized) return
        try {
            tryInstall(context)
        } catch (error: Exception) {
            if (!accessRequested) {
                accessRequested = true
                val request =
                    Intent(ConfigurationAccess.REQUEST)
                        .setComponent(
                            ComponentName(
                                BuildConfig.APPLICATION_ID,
                                ConfigurationAccessReceiver::class.java.name,
                            ),
                        )
                context.sendOrderedBroadcast(
                    request,
                    null,
                    BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle(),
                    object : BroadcastReceiver() {
                        override fun onReceive(ignored: Context?, intent: Intent?) {
                            try {
                                tryInstall(context)
                            } catch (failure: Exception) {
                                report(context, "error", failure)
                            }
                        }
                    },
                    Handler(Looper.getMainLooper()),
                    0,
                    null,
                    null,
                )
            } else report(context, "error", error)
        } catch (error: LinkageError) {
            report(context, "error", error)
        }
    }

    private fun tryInstall(context: Context) {
        // Permission results can arrive after another lifecycle event completed setup.
        if (retired || initialized) return
        if (install(context)) {
            discoveryObserver?.let(context.contentResolver::unregisterContentObserver)
            discoveryObserver = null
            finishInstallation(context)
            return
        }
        if (discoveryObserver != null) return
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    initializeUnlocked(context)
                }
            }
        context.contentResolver.registerContentObserver(ModuleProvider.URI, false, observer)
        discoveryObserver = observer
        // Covers discovery finishing between the first read and observer registration.
        if (install(context)) {
            context.contentResolver.unregisterContentObserver(observer)
            discoveryObserver = null
            finishInstallation(context)
        }
    }

    private fun finishInstallation(context: Context) {
        initialized = true
        readyHost = context
        onInstalled?.also { onInstalled = null }?.invoke()
    }

    private fun install(context: Context): Boolean {
        val config =
            context.contentResolver.call(ModuleProvider.URI, "settings", context.packageName, null)
        if (config == null || !config.getBoolean("ready")) {
            val pending =
                config == null || DiscoveryReadiness.pending(config.getString("discovery"))
            report(context, if (pending) "not_ready" else "unsupported")
            return !pending
        }
        if (!config.getBoolean("enabled")) {
            report(context, "disabled")
            return true
        }
        if (context.packageName != Targets.DIGIPOLICE) {
            check(config.getString("apk") == context.applicationInfo.sourceDir)
            ExtraHooks(session, context) { state, error, count ->
                    report(context, state, error, count)
                }
                .install(config)
            return true
        }
        if (!config.getBoolean("digipolice")) {
            report(context, "disabled")
            return true
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
                checkNotNull(config.getString("register")),
                request,
                callback,
                Looper::class.java,
            )
        val remove = client.getDeclaredMethod(checkNotNull(config.getString("remove")), callback)
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
        // Only entries whose original onStartCommand has completed can be stopped. An FGS
        // start request must first satisfy the platform's startForeground deadline.
        val promoted = IdentityHashMap<Service, Int>()
        fun stopPromotedService(instance: Service, startId: Int) {
            try {
                instance.stopForeground(Service.STOP_FOREGROUND_REMOVE)
                instance.stopSelf(startId)
                report(context, "suppressed")
            } catch (error: Exception) {
                report(context, "error", error)
            }
        }
        val stopDisabledService = Runnable {
            if (suppressAreaService()) {
                for ((instance, startId) in promoted.entries.toList()) stopPromotedService(
                    instance,
                    startId,
                )
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
            for (method in listOf("startForegroundService", "startService")) {
                installed +=
                    session
                        .hook(
                            ContextWrapper::class
                                .java
                                .getDeclaredMethod(method, Intent::class.java),
                        )
                        .intercept { chain ->
                            val component = (chain.args[0] as? Intent)?.component
                            if (
                                component?.packageName == Targets.DIGIPOLICE &&
                                    component.className == service.name &&
                                    suppressAreaService()
                            ) {
                                // No ServiceRecord is created, so no FGS deadline is armed.
                                report(context, "suppressed")
                                component
                            } else chain.proceed()
                        }
            }
            installed +=
                session.hook(register).intercept { chain ->
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
                session.hook(start).intercept { chain ->
                    val instance = chain.thisObject as Service
                    val previous = owner.get()
                    if (healthy) {
                        leases.start(instance)
                        owner.set(instance)
                    }
                    val result =
                        try {
                            chain.proceed()
                        } finally {
                            owner.set(previous)
                        }
                    val startId = chain.args[2] as Int
                    promoted[instance] = startId
                    if (suppressAreaService()) {
                        // Sticky/system/external starts bypass ContextWrapper interception.
                        // Preserve native foreground setup before removing this service.
                        stopPromotedService(instance, startId)
                        Service.START_NOT_STICKY
                    } else result
                }
            installed +=
                session.hook(destroy).intercept { chain ->
                    try {
                        chain.proceed()
                    } finally {
                        if (service.isInstance(chain.thisObject)) {
                            promoted.remove(chain.thisObject)
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
            session.beforeRetirement { promoted.isEmpty() && leases.isIdle() }
            session.onRetire {
                main.removeCallbacks(stopDisabledService)
                areaSettingsListener?.let(preferences::unregisterOnSharedPreferenceChangeListener)
                areaSettingsListener = null
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
        return true
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
                context.packageName,
                Bundle().apply {
                    putString("state", state)
                    if (error != null) putString("error", error.javaClass.simpleName)
                    released?.let { putInt("released", it) }
                },
            )
        } catch (unavailable: Exception) {
            Log.w(
                "AppBatteryTools",
                "Diagnostic provider unavailable: ${unavailable.javaClass.simpleName}",
            )
        }
    }
}
