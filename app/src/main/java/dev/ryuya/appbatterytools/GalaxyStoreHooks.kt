package dev.ryuya.appbatterytools

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.util.Log
import io.github.libxposed.api.XposedInterface.HookHandle
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod

/** All player access runs on the store's existing main thread. */
internal class GalaxyStoreHooks(
    private val module: BatteryHookSession,
    private val context: Context,
    private val enabled: Boolean,
    previous: Array<*>?,
    private val report: (String, Throwable?, Int?) -> Unit,
) {
    private val stopped = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    private var visible = previous?.getOrNull(0) as? Boolean
    private var failed = false
    private var reportedCleanup = false

    init {
        (previous?.getOrNull(1) as? Array<*>)?.forEach { reference ->
            (reference as? WeakReference<*>)?.get()?.let(stopped::add)
        }
    }

    // The new classloader receives only platform objects and weak references to host players.
    fun handoff(): Array<Any?> = arrayOf(visible, stopped.map { WeakReference(it) }.toTypedArray())

    fun install(config: Bundle) {
        check(Application.getProcessName() == Targets.GALAXY_STORE)
        check(config.getString("contract") == "galaxy-store-slot-playback")
        val application = context.applicationContext as Application
        val loader = context.classLoader
        val owner = Class.forName(GalaxyStoreDiscovery.MAIN, false, loader)
        check(Activity::class.java.isAssignableFrom(owner))
        fun method(key: String) =
            DexMethod(checkNotNull(config.getString(key))).getMethodInstance(loader).apply {
                isAccessible = true
            }

        val field =
            DexField(checkNotNull(config.getString("store_registry")))
                .getFieldInstance(loader)
                .apply { isAccessible = true }
        val player = method("store_player")
        val play = method("store_play")
        val state = method("store_state")
        val stop = method("store_stop")
        val prepare = method("store_prepare")

        fun previews() = (field.get(null) as Map<*, *>).values
        fun isPreview(entry: Any?) = player.declaringClass.isInstance(entry)

        val lifecycle =
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityPreStarted(activity: Activity) {
                    if (owner.isInstance(activity)) visible = true
                }

                override fun onActivityPreResumed(activity: Activity) {
                    if (owner.isInstance(activity)) visible = true
                }

                override fun onActivityPostStopped(activity: Activity) {
                    if (!owner.isInstance(activity)) return
                    visible = false
                    if (!enabled || failed) return
                    safely {
                        var count = 0
                        for (entry in previews().toList()) {
                            if (!isPreview(entry)) continue
                            val instance = player.invoke(entry) ?: continue
                            if (state.invoke(instance) == STATE_IDLE) continue
                            play.invoke(instance, false)
                            if (stop.parameterCount == 0) stop.invoke(instance)
                            else
                                stop.invoke(
                                    instance,
                                    false,
                                ) // Retain the playlist on older ExoPlayer builds.
                            stopped.add(instance)
                            count++
                        }
                        if (count > 0) {
                            Log.i(TAG, "Galaxy Store banner decoders stopped count=$count")
                            if (!reportedCleanup) {
                                reportedCleanup = true
                                report("active", null, count)
                            }
                        }
                    }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) =
                    Unit

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) =
                    Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            }
        var handle: HookHandle? = null
        try {
            handle =
                module.hook(play).intercept { chain ->
                    val instance = chain.thisObject
                    if (
                        chain.args[0] != true ||
                            instance == null ||
                            Looper.myLooper() != Looper.getMainLooper()
                    )
                        return@intercept chain.proceed()
                    var block = false
                    safely {
                        // Until the first lifecycle event, let stock decide whether to play.
                        if (enabled && !failed && visible == false) {
                            block =
                                previews().any { isPreview(it) && player.invoke(it) === instance }
                        }
                        if (!block && instance in stopped) {
                            if (state.invoke(instance) == STATE_IDLE) prepare.invoke(instance)
                            stopped.remove(instance)
                        }
                    }
                    if (block) null else chain.proceed()
                }
            application.registerActivityLifecycleCallbacks(lifecycle)
            module.onRetire {
                application.unregisterActivityLifecycleCallbacks(lifecycle)
                handle.unhook()
                stopped.clear()
            }
            report(if (enabled) "active" else "disabled", null, null)
        } catch (failure: Throwable) {
            application.unregisterActivityLifecycleCallbacks(lifecycle)
            handle?.unhook()
            throw failure
        }
    }

    private inline fun safely(action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            if (!failed) report("error", error, null)
            failed = true
        } catch (error: LinkageError) {
            if (!failed) report("error", error, null)
            failed = true
        }
    }

    private companion object {
        const val STATE_IDLE = 1
        const val TAG = "AppBatteryTools"
    }
}
