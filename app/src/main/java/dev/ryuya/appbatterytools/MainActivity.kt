package dev.ryuya.appbatterytools

import android.content.Intent
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textview.MaterialTextView

/** No polling service: diagnostics refresh only while opening this screen or on demand. */
class MainActivity : AppCompatActivity() {
    private lateinit var tabs: SettingsTabs
    private lateinit var status: MaterialTextView
    private val settings by lazy { getSharedPreferences("settings", 0) }
    private val statusEvents =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                refresh()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = Ui.root(this, getString(R.string.app_name))
        tabs = SettingsTabs(this, root, savedInstanceState)
        val apps = tabs.page(R.string.tab_apps)
        val master = Ui.card(apps)
        switch(master, "enabled", R.string.enable_module)
        master.addView(Ui.text(this, getString(R.string.setup)))
        val digi = Ui.card(apps)
        digi.addView(Ui.text(this, "Digi Police", true))
        switch(digi, "digipolice", R.string.digi_fix)
        digi.addView(Ui.text(this, getString(R.string.digi_detail)))
        digi.addView(Ui.text(this, getString(R.string.restart_hint)))

        val diagnostics = tabs.page(R.string.tab_status)
        val panel = Ui.card(diagnostics)
        panel.addView(Ui.text(this, getString(R.string.discovery_title), true))
        status = Ui.text(this, "")
        status.setTextIsSelectable(true)
        panel.addView(status)
        panel.addView(
            MaterialButton(this).apply {
                setText(R.string.refresh)
                setOnClickListener { refresh() }
            },
        )
        diagnostics.addView(Ui.text(this, getString(R.string.discovery_detail)))

        val about = tabs.page(R.string.tab_about)
        val info = Ui.card(about)
        info.addView(Ui.text(this, getString(R.string.app_name), true))
        info.addView(Ui.text(this, getString(R.string.version, BuildConfig.VERSION_NAME)))
        info.addView(Ui.text(this, getString(R.string.support)))
        info.addView(
            MaterialButton(this).apply {
                setText(R.string.legal_title)
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, LicensesActivity::class.java))
                }
            },
        )
        tabs.restore()
        refresh()
    }

    private fun switch(parent: LinearLayout, key: String, label: Int) {
        parent.addView(
            MaterialSwitch(this).apply {
                setText(label)
                isChecked = settings.getBoolean(key, true)
                setOnCheckedChangeListener { _, checked ->
                    settings.edit().putBoolean(key, checked).apply()
                }
            },
            LinearLayout.LayoutParams(-1, -2),
        )
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refresh()
    }

    override fun onStart() {
        super.onStart()
        contentResolver.registerContentObserver(ModuleProvider.URI, true, statusEvents)
    }

    override fun onStop() {
        contentResolver.unregisterContentObserver(statusEvents)
        super.onStop()
    }

    private fun refresh() {
        val result = contentResolver.call(ModuleProvider.URI, "status", null, null)
        fun state(value: String?) =
            getString(
                when (value) {
                    "ready" -> R.string.state_ready
                    "preparing",
                    "waiting",
                    "interrupted" -> R.string.state_preparing
                    "active" -> R.string.state_active
                    "removal_requested" -> R.string.state_removed
                    "disabled" -> R.string.state_disabled
                    "not_ready" -> R.string.state_restart
                    "not_installed_or_untrusted" -> R.string.state_absent
                    "error",
                    "failed",
                    "native-unavailable",
                    "corrupt",
                    "unsupported" -> R.string.state_unsupported
                    else -> R.string.state_waiting
                },
            )
        status.text =
            getString(
                R.string.status_format,
                state(result?.getString("discovery")),
                state(result?.getString("hostState")),
                result?.getInt("released") ?: 0,
                result?.getString("error").orEmpty(),
            )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        tabs.save(outState)
        super.onSaveInstanceState(outState)
    }
}
