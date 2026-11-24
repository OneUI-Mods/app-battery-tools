package dev.ryuya.appbatterytools

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.pm.PackageItemInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import io.github.libxposed.api.XposedInterface.HookHandle
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The host app reads every installed widget's preview image when it starts and keeps them all. Each
 * preview is replaced with a copy whose longest side is at most [MAX_SIDE] pixels. The copy reports
 * the original intrinsic size, so the host lays out its widget list exactly as before.
 */
internal class WidgetPreviewHooks(
    private val module: BatteryHookSession,
    private val report: (String, Throwable?, Int?) -> Unit,
) {
    private val handles = mutableListOf<HookHandle>()
    private var reduced = 0
    @Volatile private var wrapped = 0
    private val icons = LazyAppIcons(ICON_CACHE_BYTES)

    private class ReducedPreview(
        private val bitmap: Bitmap,
        private val width: Int,
        private val height: Int,
    ) : Drawable() {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val source = Rect(0, 0, bitmap.width, bitmap.height)

        override fun draw(canvas: Canvas) = canvas.drawBitmap(bitmap, source, bounds, paint)

        override fun getIntrinsicWidth() = width

        override fun getIntrinsicHeight() = height

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Drawable") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private fun reduce(original: Drawable): Drawable {
        val width = original.intrinsicWidth
        val height = original.intrinsicHeight
        val side = max(width, height)
        if (width <= 0 || height <= 0 || side <= MAX_SIDE) return original
        val scale = MAX_SIDE.toFloat() / side
        val bitmap =
            Bitmap.createBitmap(
                (width * scale).roundToInt().coerceAtLeast(1),
                (height * scale).roundToInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
        val previous = Rect(original.bounds)
        original.setBounds(0, 0, bitmap.width, bitmap.height)
        original.draw(Canvas(bitmap))
        original.bounds = previous
        if (++reduced == 1) report("active", null, 1)
        return ReducedPreview(bitmap, width, height)
    }

    fun install() {
        val load =
            AppWidgetProviderInfo::class
                .java
                .getDeclaredMethod(
                    "loadPreviewImage",
                    Context::class.java,
                    Int::class.javaPrimitiveType,
                )
        handles +=
            module.hook(load).intercept { chain ->
                val result = chain.proceed() as? Drawable ?: return@intercept null
                try {
                    reduce(result)
                } catch (failure: RuntimeException) {
                    report("error", failure, null)
                    result
                } catch (failure: OutOfMemoryError) {
                    result
                }
            }
        // The app also keeps the icon of every launchable app but draws only a few of them.
        val icon =
            PackageItemInfo::class.java.getDeclaredMethod("loadIcon", PackageManager::class.java)
        handles +=
            module.hook(icon).intercept { chain ->
                val result = chain.proceed()
                if (icons.isLoading) return@intercept result
                val drawable = result as? Drawable ?: return@intercept result
                try {
                    val lazy =
                        icons.wrap(
                            chain.thisObject as PackageItemInfo,
                            chain.args[0] as PackageManager,
                            drawable,
                        )
                    if (lazy !== drawable) {
                        wrapped++
                        if (++reduced == 1) report("active", null, 1)
                    }
                    lazy
                } catch (failure: RuntimeException) {
                    result
                }
            }
        // Lazy icons stay in the host model; the next start of the app picks up a new build.
        module.beforeRetirement { wrapped == 0 }
        module.onRetire { handles.forEach { runCatching { it.unhook() } } }
    }

    private companion object {
        const val MAX_SIDE = 256
        const val ICON_CACHE_BYTES = 16 * 1024 * 1024
    }
}
