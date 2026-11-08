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
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** The module owns discovery, cache files and diagnostics. Hosts never supply paths or queries. */
class ModuleProvider : ContentProvider() {
    private data class Identity(val generation: String, val apk: String, val files: List<String>)

    private val worker = BackgroundExecutors.serial("BatteryDiscovery", idleMillis = 1_000)
    private val pending = AtomicBoolean()
    private val searches = AtomicInteger()
    @Volatile private var result: Pair<String, GenerationStore.Result>? = null
    private val store by lazy {
        GenerationStore(File(appContext().filesDir, "dexkit/${Targets.DIGIPOLICE}"))
    }

    private fun appContext() = checkNotNull(context)

    override fun onCreate(): Boolean {
        prepare()
        return true
    }

    @Suppress("DEPRECATION")
    private fun identity(): Identity? =
        try {
            val pm = appContext().packageManager
            val info =
                pm.getPackageInfo(Targets.DIGIPOLICE, PackageManager.GET_SIGNING_CERTIFICATES)
            val signers =
                info.signingInfo
                    ?.let {
                        if (it.hasMultipleSigners()) it.apkContentsSigners
                        else it.signingCertificateHistory
                    }
                    .orEmpty()
            check(
                signers.any { signature ->
                    val bytes = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                    bytes.joinToString("") { "%02x".format(it) } == Targets.CERTIFICATE
                },
            ) {
                "Unreviewed target signer"
            }
            val app = checkNotNull(info.applicationInfo)
            val files = listOf(app.sourceDir) + app.splitSourceDirs.orEmpty()
            Identity(
                listOf(
                        Targets.RULE,
                        info.longVersionCode,
                        info.lastUpdateTime,
                        Targets.CERTIFICATE,
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

    @Synchronized
    private fun prepare() {
        val target = identity() ?: return
        if (result?.first == target.generation || pending.get()) return
        val cached = store.readExisting(target.generation)
        if (cached != null) {
            result = target.generation to cached
            return
        }
        if (!pending.compareAndSet(false, true)) return
        worker.execute {
            try {
                val resolved =
                    store.readOrExplore(target.generation) {
                        searches.incrementAndGet()
                        DigiPoliceDiscovery.explore(target.apk, target.files) +
                            ("apk" to target.apk)
                    }
                result = target.generation to resolved
            } finally {
                pending.set(false)
                appContext()
                    .contentResolver
                    .notifyChange(URI.buildUpon().appendPath("ready").build(), null)
            }
        }
    }

    private fun checkCaller(write: Boolean = false) {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return
        if (!write && (uid == 0 || uid == 2000)) return
        check(
            appContext()
                .packageManager
                .getPackagesForUid(uid)
                .orEmpty()
                .contains(Targets.DIGIPOLICE),
        ) {
            "Installed host UID required"
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (method == "configure") {
            // The app UI uses private preferences; this bounded root-only path supports CLI QA.
            require(Binder.getCallingUid() == 0 || Binder.getCallingUid() == Process.myUid())
            val input = checkNotNull(extras)
            require(input.keySet().all { it in setOf("enabled", "digipolice") })
            val edit = appContext().getSharedPreferences("settings", 0).edit()
            for (key in input.keySet()) edit.putBoolean(key, input.getBoolean(key))
            check(edit.commit())
            return Bundle.EMPTY
        }
        checkCaller(method == "report")
        val c = appContext()
        val prefs = c.getSharedPreferences("settings", 0)
        if (method == "report") {
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
                    ),
            )
            val edit =
                c.getSharedPreferences("status", 0)
                    .edit()
                    .putString("state", state)
                    .putLong("updated", System.currentTimeMillis())
            if (input.containsKey("released")) edit.putInt("released", input.getInt("released"))
            edit.putString("error", input.getString("error", "").orEmpty().take(200)).apply()
            c.contentResolver.notifyChange(URI.buildUpon().appendPath("status").build(), null)
            return Bundle.EMPTY
        }
        require(method == "settings" || method == "status")
        prepare()
        val target = identity()
        val data = result?.takeIf { target?.generation == it.first }?.second
        return Bundle().apply {
            putBoolean("enabled", prefs.getBoolean("enabled", true))
            putBoolean("digipolice", prefs.getBoolean("digipolice", true))
            putString(
                "discovery",
                if (target == null) "not_installed_or_untrusted"
                else if (pending.get()) "preparing" else data?.state ?: "waiting",
            )
            putInt("searches", searches.get())
            putBoolean("ready", data?.state == "ready")
            if (data?.state == "ready") for ((key, value) in data.bindings) putString(key, value)
            if (method == "status") {
                val status = c.getSharedPreferences("status", 0)
                putString("hostState", status.getString("state", "waiting"))
                putString("error", status.getString("error", ""))
                putInt("released", status.getInt("released", 0))
                putLong("updated", status.getLong("updated", 0))
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
