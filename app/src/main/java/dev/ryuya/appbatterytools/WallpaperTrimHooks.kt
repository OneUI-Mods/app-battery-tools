package dev.ryuya.appbatterytools

import android.content.ComponentCallbacks2
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import io.github.libxposed.api.XposedInterface.HookHandle

/**
 * Releases the live wallpaper process's GPU caches once its engine has stayed hidden. The wallpaper
 * keeps its decoded pictures and window buffers while another app covers it; the pictures upload
 * again on the next draw. One delayed callback per hidden period, no polling or service.
 */
@android.annotation.SuppressLint(
    "BlockedPrivateApi",
    "DiscouragedPrivateApi",
    "SoonBlockedPrivateApi",
)
internal class WallpaperTrimHooks(
    private val module: BatteryHookSession,
    private val report: (String, Throwable?, Int?) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val handles = mutableListOf<HookHandle>()
    private val hidden = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>())
    private var trimmed = 0

    private val trim = Runnable {
        if (hidden.isEmpty()) return@Runnable
        // Every engine of this process is hidden; the static call affects only this process.
        trimMemory.invoke(null, ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        if (++trimmed == 1) report("active", null, 1)
    }

    private val trimMemory =
        Class.forName("android.graphics.HardwareRenderer")
            .getDeclaredMethod("trimMemory", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
    private val engines = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>())

    fun install() {
        val change =
            WallpaperService.Engine::class
                .java
                .getDeclaredMethod(
                    "doVisibilityChanged",
                    Boolean::class.javaPrimitiveType,
                )
        handles +=
            module.hook(change).intercept { chain ->
                val result = chain.proceed()
                val engine = checkNotNull(chain.thisObject)
                val visible = chain.args[0] as Boolean
                main.post {
                    engines.add(engine)
                    if (visible) hidden.remove(engine) else hidden.add(engine)
                    main.removeCallbacks(trim)
                    if (hidden.size >= engines.size) main.postDelayed(trim, TRIM_DELAY_MS)
                }
                result
            }
        module.beforeRetirement { true }
        module.onRetire {
            main.removeCallbacks(trim)
            handles.forEach { runCatching { it.unhook() } }
        }
    }

    private companion object {
        const val TRIM_DELAY_MS = 4_000L
    }
}
