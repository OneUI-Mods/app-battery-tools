package dev.ryuya.appbatterytools

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayout.OnTabSelectedListener
import com.google.android.material.tabs.TabLayout.Tab
import java.util.ArrayList
import kotlin.math.max
import kotlin.math.min

/* Fixed navigation, with independent scrolling and saved positions for every page. */
class SettingsTabs
constructor(
    context: Context,
    root: LinearLayout,
    saved: Bundle?,
) {
    private val context: Context
    @kotlin.jvm.JvmField val tabs: TabLayout
    private val content: FrameLayout
    private val pages = ArrayList<NestedScrollView>()
    private val saved: Bundle?
    private val pendingScrolls = ArrayList<Int?>()

    init {
        this.context = context
        this.saved = saved
        tabs = TabLayout(context)
        tabs.setId(R.id.settings_tabs)
        tabs.setTabMode(TabLayout.MODE_AUTO)
        tabs.setTabIndicatorFullWidth(false)
        root.addView(tabs, android.widget.LinearLayout.LayoutParams(-1, -2))
        content = FrameLayout(context)
        root.addView(content, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        tabs.addOnTabSelectedListener(
            object : OnTabSelectedListener {
                override fun onTabSelected(tab: Tab) {
                    show(tab.getPosition())
                }

                override fun onTabUnselected(tab: Tab?) {}

                override fun onTabReselected(tab: Tab?) {}
            },
        )
    }

    fun page(title: Int): LinearLayout {
        val index = pages.size
        val scroll = NestedScrollView(context)
        scroll.setFillViewport(true)
        val body = LinearLayout(context)
        body.setOrientation(LinearLayout.VERTICAL)
        val padding = Math.round(20 * context.getResources().getDisplayMetrics().density)
        body.setPadding(padding, padding, padding, padding)
        scroll.addView(body)
        pages.add(scroll)
        val restoreY = if (saved == null) 0 else saved.getInt("page_scroll_" + index, 0)
        pendingScrolls.add(if (restoreY > 0) restoreY else null)
        content.addView(scroll, android.widget.FrameLayout.LayoutParams(-1, -1))
        scroll.setVisibility(if (index == 0) View.VISIBLE else View.GONE)
        tabs.addTab(tabs.newTab().setText(title))
        if (restoreY > 0) {
            scroll
                .getViewTreeObserver()
                .addOnGlobalLayoutListener(
                    object : OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            if (scroll.getVisibility() != View.VISIBLE || scroll.getHeight() == 0)
                                return
                            scroll.scrollTo(0, restoreY)
                            pendingScrolls.set(index, null)
                            scroll.getViewTreeObserver().removeOnGlobalLayoutListener(this)
                        }
                    },
                )
        }
        return body
    }

    fun restore() {
        val selected = if (saved == null) 0 else saved.getInt("selected_tab", 0)
        val tab = tabs.getTabAt(max(0, min(selected, pages.size - 1)))
        if (tab != null) tab.select()
    }

    fun save(out: Bundle) {
        out.putInt("selected_tab", max(0, tabs.getSelectedTabPosition()))
        for (i in pages.indices) out.putInt(
            "page_scroll_" + i,
            pendingScrolls[i] ?: pages[i].scrollY,
        )
    }

    private fun show(index: Int) {
        for (i in pages.indices) pages
            .get(i)
            .setVisibility(if (i == index) View.VISIBLE else View.GONE)
    }
}
