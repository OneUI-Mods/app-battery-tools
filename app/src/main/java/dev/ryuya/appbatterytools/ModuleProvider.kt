package dev.ryuya.appbatterytools

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Each target has its own generation cache and diagnostics, in this module's private data. */
class ModuleProvider : ContentProvider() {
    private data class Identity(val generation: String, val apk: String, val files: List<String>)

    private class Slot {
        val pending = AtomicBoolean()
        val searches = AtomicInteger()
        @Volatile var result: Pair<String, GenerationStore.Result>? = null
    }

    private val slots = Targets.all.associate { it.packageName to Slot() }
    private val worker = BackgroundExecutors.serial("BatteryDiscovery", idleMillis = 1_000)

    private fun appContext() = checkNotNull(context)

    override fun onCreate(): Boolean {
        Targets.all.forEach(::prepare)
        return true
    }

    @Suppress("DEPRECATION")
    private fun identity(target: Targets.Target): Identity? =
        try {
            val info =
                appContext()
                    .packageManager
                    .getPackageInfo(
                        target.packageName,
                        PackageManager.GET_SIGNING_CERTIFICATES,
                    )
            val signers =
                info.signingInfo
                    ?.let {
                        if (it.hasMultipleSigners()) it.apkContentsSigners
                        else it.signingCertificateHistory
                    }
                    .orEmpty()
            check(
                signers.any { GenerationStore.digestBytes(it.toByteArray()) == target.certificate },
            )
            val app = checkNotNull(info.applicationInfo)
            val files = listOf(app.sourceDir) + app.splitSourceDirs.orEmpty()
            Identity(
                listOf(
                        target.rule,
                        info.longVersionCode,
                        info.lastUpdateTime,
                        target.certificate,
                        files.joinToString("|") { path ->
                            val file = File(path)
                            "$path:${file.length()}:${file.lastModified()}"
                        },
                    )
                    .joinToString("|"),
                app.sourceDir,
                files,
            )
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: IllegalStateException) {
            null
        }

    private fun store(target: Targets.Target) =
        GenerationStore(File(appContext().filesDir, "dexkit/${target.packageName}"))

    @Synchronized
    private fun prepare(target: Targets.Target) {
        val identity = identity(target) ?: return
        val slot = slots.getValue(target.packageName)
        if (slot.result?.first == identity.generation || slot.pending.get()) return
        val cached = store(target).readExisting(identity.generation)
        if (cached != null) {
            slot.result = identity.generation to cached
            return
        }
        if (!slot.pending.compareAndSet(false, true)) return
        worker.execute {
            try {
                val resolved =
                    store(target).readOrExplore(identity.generation) {
                        slot.searches.incrementAndGet()
                        (if (target.packageName == Targets.DIGIPOLICE)
                            DigiPoliceDiscovery.explore(identity.apk, identity.files)
                        else ExtraDiscovery.explore(target.packageName, identity.apk)) +
                            ("apk" to identity.apk)
                    }
                slot.result = identity.generation to resolved
            } finally {
                slot.pending.set(false)
                appContext().contentResolver.notifyChange(URI, null)
            }
        }
    }

    private fun targetFor(arg: String?): Targets.Target {
        val uid = Binder.getCallingUid()
        val packageName = arg ?: Targets.DIGIPOLICE
        val target = checkNotNull(Targets.find(packageName))
        if (uid != Process.myUid() && uid != 0 && uid != 2000) {
            check(
                appContext().packageManager.getPackagesForUid(uid).orEmpty().contains(packageName),
            )
            check(identity(target) != null) { "Reviewed installed host required" }
        }
        return target
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val uid = Binder.getCallingUid()
        if (method == "configure") {
            require(uid == 0 || uid == Process.myUid())
            val input = checkNotNull(extras)
            require(input.keySet().all { it in Targets.optionKeys && input.get(it) is Boolean })
            val edit = appContext().getSharedPreferences("settings", 0).edit()
            input.keySet().forEach { edit.putBoolean(it, input.getBoolean(it)) }
            check(edit.commit())
            return Bundle.EMPTY
        }
        val target = targetFor(arg)
        val c = appContext()
        val statePrefs = c.getSharedPreferences("status_${target.packageName}", 0)
        if (method == "report") {
            check(uid == Process.myUid() || (uid != 0 && uid != 2000))
            val input = checkNotNull(extras)
            val state = input.getString("state")
            require(
                state in
                    setOf(
                        "active",
                        "suppressed",
                        "disabled",
                        "not_ready",
                        "unsupported",
                        "removal_requested",
                        "error",
                        "wifi_paused",
                        "wifi_active",
                    ),
            )
            val error = input.getString("error", "").orEmpty().take(200)
            val count = input.getInt("released", statePrefs.getInt("released", 0))
            if (
                statePrefs.getString("state", "") != state ||
                    statePrefs.getString("error", "") != error ||
                    statePrefs.getInt("released", 0) != count
            ) {
                statePrefs
                    .edit()
                    .putString("state", state)
                    .putString("error", error)
                    .putInt("released", count)
                    .putLong("updated", System.currentTimeMillis())
                    .apply()
                c.contentResolver.notifyChange(URI, null)
            }
            return Bundle.EMPTY
        }
        require(method == "settings" || method == "status")
        prepare(target)
        val identity = identity(target)
        val slot = slots.getValue(target.packageName)
        val data = slot.result?.takeIf { identity?.generation == it.first }?.second
        val prefs = c.getSharedPreferences("settings", 0)
        return Bundle().apply {
            putBoolean("enabled", prefs.getBoolean("enabled", true))
            target.options.forEach { putBoolean(it.key, prefs.getBoolean(it.key, true)) }
            putString(
                "discovery",
                if (identity == null) "not_installed_or_untrusted"
                else if (slot.pending.get()) "preparing" else data?.state ?: "waiting",
            )
            putInt("searches", slot.searches.get())
            putBoolean("ready", data?.state == "ready")
            if (data?.state == "ready")
                data.bindings.forEach { (key, value) -> putString(key, value) }
            if (method == "status") {
                putString("hostState", statePrefs.getString("state", "waiting"))
                putString("error", statePrefs.getString("error", ""))
                putInt("released", statePrefs.getInt("released", 0))
                putLong("updated", statePrefs.getLong("updated", 0))
            }
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int =
        throw UnsupportedOperationException()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = throw UnsupportedOperationException()

    companion object {
        val URI: Uri = Uri.parse("content://${Targets.AUTHORITY}")
    }
}
