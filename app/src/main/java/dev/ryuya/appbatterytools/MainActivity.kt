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
    private val statuses = linkedMapOf<String, MaterialTextView>()
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
        for (target in Targets.all) {
            val card = Ui.card(apps)
            card.addView(Ui.text(this, targetTitle(target), true))
            target.options.forEach { switch(card, it.key, it.label) }
            card.addView(Ui.text(this, getString(target.detail)))
        }
        apps.addView(Ui.text(this, getString(R.string.restart_hint)))

        val diagnostics = tabs.page(R.string.tab_status)
        for (target in Targets.all) {
            val panel = Ui.card(diagnostics)
            panel.addView(Ui.text(this, targetTitle(target), true))
            val view = Ui.text(this, "").apply { setTextIsSelectable(true) }
            statuses[target.packageName] = view
            panel.addView(view)
        }
        diagnostics.addView(
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

    private fun targetTitle(target: Targets.Target): String =
        when (target.packageName) {
            Targets.WINDOWS -> getString(R.string.windows_name)
            Targets.SESAME -> getString(R.string.sesame_name)
            Targets.WEATHER -> getString(R.string.weather_name)
            else -> target.name
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
        if (statuses.isNotEmpty()) refresh()
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
        for (target in Targets.all) {
            val result =
                contentResolver.call(ModuleProvider.URI, "status", target.packageName, null)
            val status = statuses.getValue(target.packageName)
            fun state(value: String?) =
                getString(
                    when (value) {
                        "ready" -> R.string.state_ready
                        "preparing",
                        "waiting",
                        "interrupted" -> R.string.state_preparing
                        "active" -> R.string.state_active
                        "cleanup_partial" -> R.string.state_cleanup_partial
                        "wifi_paused" -> R.string.state_wifi_paused
                        "wifi_active" -> R.string.state_wifi_active
                        "removal_requested" -> R.string.state_removed
                        "suppressed" -> R.string.state_suppressed
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
            if (target.packageName == Targets.DIGIPOLICE)
                status.text =
                    getString(
                        R.string.status_format,
                        state(result?.getString("discovery")),
                        state(result?.getString("hostState")),
                        result?.getInt("released") ?: 0,
                        result?.getString("error").orEmpty(),
                    )
            else
                status.text =
                    getString(
                        R.string.extra_status_format,
                        state(result?.getString("discovery")),
                        state(result?.getString("hostState")),
                        result?.getString("error").orEmpty(),
                    )
            if (target.packageName == Targets.DIGIPOLICE && result?.getBoolean("ready") == true) {
                status.append(
                    "\n" +
                        getString(
                            if (result.getString("area_guard") == "reviewed") R.string.flag_ready
                            else R.string.flag_unreviewed,
                        ),
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        tabs.save(outState)
        super.onSaveInstanceState(outState)
    }
}
