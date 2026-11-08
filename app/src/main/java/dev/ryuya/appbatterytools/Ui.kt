package dev.ryuya.appbatterytools

import android.content.Context
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textview.MaterialTextView

internal object Ui {
    fun Context.dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    fun root(activity: AppCompatActivity, title: String): LinearLayout {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        activity.setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                )
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        root.addView(MaterialToolbar(activity).apply { this.title = title })
        return root
    }

    fun text(context: Context, value: CharSequence, title: Boolean = false) =
        MaterialTextView(context).apply {
            text = value
            setTextAppearance(
                if (title) com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
                else com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
            )
            setPadding(0, context.dp(6), 0, context.dp(6))
        }

    fun card(parent: LinearLayout): LinearLayout {
        val c = parent.context
        val card =
            MaterialCardView(c).apply {
                radius = c.dp(24).toFloat()
                cardElevation = 0f
            }
        val box =
            LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(c.dp(20), c.dp(14), c.dp(20), c.dp(16))
            }
        card.addView(box)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = c.dp(16) })
        return box
    }
}
