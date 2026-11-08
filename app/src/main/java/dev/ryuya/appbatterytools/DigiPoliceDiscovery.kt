package dev.ryuya.appbatterytools

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge

/** Resolve both sides of the same location registration; ambiguity disables this fix. */
internal object DigiPoliceDiscovery {
    fun explore(apk: String): Map<String, String?> {
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
                "service" to service.name,
                "client" to register.declaredClassName,
                "register" to register.name,
                "remove" to remove.name,
                "callback" to callback,
                "task" to register.returnTypeName,
            )
        }
    }
}
