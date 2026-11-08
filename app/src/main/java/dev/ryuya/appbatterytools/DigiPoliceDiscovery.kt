package dev.ryuya.appbatterytools

import android.os.Build
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.luckypray.dexkit.DexKitBridge

/** Resolve both sides of the same location registration; ambiguity disables this fix. */
internal object DigiPoliceDiscovery {
    fun explore(apk: String, files: List<String>): Map<String, String?> {
        System.loadLibrary("dexkit")
        return DexKitBridge.create(apk).use { bridge ->
            bridge.setThreadNum(2)
            val service =
                checkNotNull(
                    bridge
                        .findClass {
                            matcher {
                                superClass("android.app.Service")
                                usingStrings(listOf("エリア通知を送信するために位置情報を取得します。", "位置情報受信"))
                            }
                        }
                        .singleOrNull(),
                ) {
                    "Area service contract is not unique"
                }
            val start =
                service.methods.single {
                    it.name == "onStartCommand" &&
                        it.returnTypeName == "int" &&
                        it.paramTypeNames == listOf("android.content.Intent", "int", "int") &&
                        !Modifier.isStatic(it.modifiers)
                }
            val register =
                start.invokes
                    .filter {
                        it.paramTypeNames.size == 3 &&
                            it.paramTypeNames.first() ==
                                "com.google.android.gms.location.LocationRequest" &&
                            it.paramTypeNames.last() == "android.os.Looper" &&
                            !Modifier.isStatic(it.modifiers)
                    }
                    .distinctBy { it.descriptor }
                    .single()
            val callback = register.paramTypeNames[1]
            val callbackType = bridge.getClassData(callback) ?: error("Missing callback")
            check(
                callbackType.methods.any {
                    it.paramTypeNames == listOf("com.google.android.gms.location.LocationResult") &&
                        it.returnTypeName == "void"
                },
            ) {
                "Unexpected callback contract"
            }
            val remove =
                checkNotNull(register.declaredClass).methods.single {
                    it.paramTypeNames == listOf(callback) &&
                        it.returnTypeName == register.returnTypeName &&
                        !Modifier.isStatic(it.modifiers) &&
                        it.usingStrings.containsAll(
                            listOf("Listener must not be null", "Listener type must not be empty"),
                        )
                }
            // A direct upstream cleanup no longer needs our patch. Keep the terminal result cached.
            check(
                service.methods.none { method ->
                    method.name == "onDestroy" &&
                        method.paramTypeNames.isEmpty() &&
                        method.invokes.any { it.descriptor == remove.descriptor }
                },
            ) {
                "Upstream service already removes its location callback"
            }
            mapOf(
                "area_guard" to if (reviewedSettings(files)) "reviewed" else "unreviewed",
                "service" to service.name,
                "client" to register.declaredClassName,
                "register" to register.name,
                "remove" to remove.name,
                "callback" to callback,
                "task" to register.returnTypeName,
            )
        }
    }

    private fun reviewedSettings(apks: List<String>): Boolean {
        // DexKit proves Java contracts, not Dart AOT semantics. Do not let an obsolete setting
        // disable notifications after the target changes its settings model.
        val entry = "lib/${Build.SUPPORTED_ABIS.first()}/libapp.so"
        for (apk in apks) ZipFile(apk).use { zip ->
            val library = zip.getEntry(entry) ?: return@use
            val digest = MessageDigest.getInstance("SHA-256")
            zip.getInputStream(library).use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            return hash == "ba39095c548613bbfa03a418e5b3a06d874585936c007ee18402cf01e2af85a5"
        }
        return false
    }
}
