package dev.ryuya.appbatterytools

import android.content.pm.PackageItemInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Icons whose pixels are read from the package only while they are drawn. The host keeps one icon
 * per launchable app for its whole lifetime but draws few of them, so only recently drawn icons
 * stay in a bounded cache. Adaptive icons stay adaptive, with the same layers present, so the
 * host's monochrome and layer handling is unchanged.
 */
internal class LazyAppIcons(private val budgetBytes: Int) {
    private val lock = Any()
    private val loading = ThreadLocal<Boolean>()

    /** Layers of one icon: background, foreground, monochrome, or the whole non-adaptive icon. */
    private class Loaded(val layers: Array<Drawable?>, val bytes: Int)

    private val cache =
        object : LruCache<String, Loaded>(budgetBytes) {
            override fun sizeOf(key: String, value: Loaded) = value.bytes.coerceAtLeast(1)
        }

    val isLoading: Boolean
        get() = loading.get() == true

    private fun key(info: PackageItemInfo) = info.packageName + "/" + info.name

    private fun bytes(drawable: Drawable?): Int =
        (drawable as? BitmapDrawable)?.bitmap?.allocationByteCount
            ?: (drawable?.let {
                it.intrinsicWidth.coerceAtLeast(1) * it.intrinsicHeight.coerceAtLeast(1) * 4
            } ?: 0)

    private fun split(icon: Drawable): Array<Drawable?> =
        if (icon is AdaptiveIconDrawable)
            arrayOf(icon.background, icon.foreground, icon.monochrome, null)
        else arrayOf(null, null, null, icon)

    /** Drawn pixels only: bitmap layers keep [limit] pixels on their longest side. */
    private fun shrink(drawable: Drawable?, limit: Int): Drawable? {
        val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return drawable
        val side = max(bitmap.width, bitmap.height)
        if (side <= limit) return drawable
        val scale = limit.toFloat() / side
        return BitmapDrawable(
            null,
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                true,
            ),
        )
    }

    /** Lock held. Reads the icon again from its package. */
    private fun load(info: PackageItemInfo, manager: PackageManager): Loaded? {
        cache.get(key(info))?.let {
            return it
        }
        loading.set(true)
        val icon =
            try {
                info.loadIcon(manager)
            } finally {
                loading.remove()
            } ?: return null
        val layers =
            split(icon)
                .mapIndexed<Drawable?, Drawable?> { index, layer ->
                    shrink(layer, if (index == 3) ICON_SIDE else LAYER_SIDE)
                }
                .toTypedArray()
        val loaded = Loaded(layers, layers.sumOf { bytes(it) })
        cache.put(key(info), loaded)
        return loaded
    }

    /**
     * Replaces a freshly loaded icon; its pixels become garbage once the host drops the original.
     */
    fun wrap(info: PackageItemInfo, manager: PackageManager, original: Drawable): Drawable {
        val layers = split(original)
        fun lazy(index: Int) =
            layers[index]?.let {
                Layer(info, manager, index, it.intrinsicWidth, it.intrinsicHeight)
            }
        return if (original is AdaptiveIconDrawable) AdaptiveIconDrawable(lazy(0), lazy(1), lazy(2))
        else lazy(3) ?: original
    }

    private inner class Layer(
        private val info: PackageItemInfo,
        private val manager: PackageManager,
        private val index: Int,
        private val width: Int,
        private val height: Int,
    ) : Drawable() {
        private var alphaValue = 255
        private var filter: ColorFilter? = null
        private var tint: ColorStateList? = null
        private var blend: BlendMode? = null

        override fun draw(canvas: Canvas) {
            synchronized(lock) {
                val layer = load(info, manager)?.layers?.getOrNull(index) ?: return
                layer.bounds = bounds
                layer.alpha = alphaValue
                layer.colorFilter = filter
                layer.setTintList(tint)
                if (blend != null) layer.setTintBlendMode(blend)
                layer.state = state
                layer.draw(canvas)
            }
        }

        override fun getIntrinsicWidth() = width

        override fun getIntrinsicHeight() = height

        override fun setAlpha(alpha: Int) {
            alphaValue = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            filter = colorFilter
        }

        override fun setTintList(tint: ColorStateList?) {
            this.tint = tint
        }

        override fun setTintBlendMode(blendMode: BlendMode?) {
            blend = blendMode
        }

        override fun isStateful() = true

        override fun onStateChange(state: IntArray) = true

        @Deprecated("Deprecated in Drawable") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private companion object {
        const val ICON_SIDE = 144

        /**
         * Layers span 108 dp with the visible icon in the middle 72 dp; 216 px keeps 144 px
         * visible.
         */
        const val LAYER_SIDE = 216
    }
}
