package dev.ryuya.appbatterytools

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONArray
import org.json.JSONObject

/** Searchable library cards open a separate license page instead of one giant text dump. */
class LicensesActivity : AppCompatActivity() {
    private val libraries by lazy {
        val list =
            assets.open("legal/libraries.json").bufferedReader().use { JSONArray(it.readText()) }
        (0 until list.length()).map(list::getJSONObject)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        catalog()
    }

    private fun page(title: String): LinearLayout {
        val root = Ui.root(this, title)
        (root.getChildAt(0) as MaterialToolbar).apply {
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationContentDescription(R.string.back)
            setNavigationOnClickListener { finish() }
        }
        return root
    }

    private fun catalog() {
        val root = page(getString(R.string.legal_title))
        val field = TextInputLayout(this).apply { setHint(R.string.legal_search) }
        val search = TextInputEditText(field.context).apply { isSingleLine = true }
        field.addView(search)
        root.addView(field)
        val body =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 16, 24, 16)
            }
        root.addView(
            NestedScrollView(this).apply { addView(body) },
            LinearLayout.LayoutParams(-1, 0, 1f),
        )
        fun filter(query: String) {
            body.removeAllViews()
            for (library in libraries) if (library.toString().contains(query, ignoreCase = true)) {
                val card = Ui.card(body)
                card.addView(Ui.text(this, library.getString("title"), true))
                card.addView(
                    Ui.text(
                        this,
                        library.optString("version") + " · " + library.getString("license"),
                    ),
                )
                card.addView(
                    MaterialButton(this).apply {
                        setText(R.string.legal_open)
                        setOnClickListener { detail(library) }
                    },
                )
            }
        }
        search.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    filter(s?.toString().orEmpty())
                }

                override fun afterTextChanged(s: Editable?) {}
            },
        )
        filter("")
    }

    private fun detail(library: JSONObject) {
        val root = page(library.getString("title"))
        (root.getChildAt(0) as MaterialToolbar).setNavigationOnClickListener { catalog() }
        val body =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 16, 24, 16)
            }
        val license = assets.open(library.getString("asset")).bufferedReader().use { it.readText() }
        body.addView(
            Ui.text(this, library.optString("notice") + "\n\n" + license).apply {
                setTextIsSelectable(true)
            },
        )
        root.addView(NestedScrollView(this).apply { addView(body) })
    }
}
