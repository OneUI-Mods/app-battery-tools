package dev.ryuya.appbatterytools

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData

/** Lifecycle and ownership proofs from the installed APK, including obfuscated Flutter code. */
internal object LifecycleDiscovery {
    private fun MethodData.calls(type: String, name: String) = invokes.any {
        it.declaredClassName == type && it.name == name
    }

    fun explore(packageName: String, bridge: DexKitBridge): Map<String, String?> =
        when (packageName) {
            Targets.LANDROP -> landrop(bridge)
            Targets.CONTINUITY -> continuity(bridge)
            else -> error("Unknown lifecycle target")
        }

    private fun landrop(bridge: DexKitBridge): Map<String, String?> {
        val acquire =
            bridge
                .findMethod {
                    matcher { usingStrings(listOf("app.landrop.landrop_flutter.discovery")) }
                }
                .single { it.paramTypeNames.isEmpty() && it.returnTypeName == "void" }
        check(acquire.calls("android.net.wifi.WifiManager\$MulticastLock", "acquire"))
        val activity = checkNotNull(acquire.declaredClass)
        val lock =
            activity.fields.single {
                it.typeName == "android.net.wifi.WifiManager\$MulticastLock" &&
                    !Modifier.isStatic(it.modifiers)
            }
        check(activity.methods.none { it.calls(lock.typeName, "release") })
        val configure =
            acquire.callers.single {
                it.declaredClassName == activity.name &&
                    it.paramTypeNames.size == 1 &&
                    it.returnTypeName == "void"
            }
        val engine = checkNotNull(bridge.getClassData(configure.paramTypeNames.single()))
        val jni = engine.fields.single { it.typeName == "io.flutter.embedding.engine.FlutterJNI" }
        val engineDestroy =
            engine.methods.single {
                it.paramTypeNames.isEmpty() &&
                    it.returnTypeName == "void" &&
                    it.calls(jni.typeName, "detachFromNativeAndReleaseResources")
            }
        val host = checkNotNull(activity.superClass)
        val destroy =
            host.methods.single {
                it.name == "onDestroy" && it.paramTypeNames.isEmpty() && it.returnTypeName == "void"
            }
        val ownerPolicy =
            host.methods.single {
                it.usingStrings.contains("destroy_engine_with_activity") &&
                    it.paramTypeNames.isEmpty() &&
                    it.returnTypeName == "boolean"
            }
        val detach =
            destroy.invokes.single { call ->
                call.invokes.any { it.descriptor == engineDestroy.descriptor }
            }
        val delegate = host.fields.single { it.typeName == detach.declaredClassName }
        val engineField =
            checkNotNull(detach.declaredClass).fields.single { it.typeName == engine.name }
        check(
            detach.invokes.any {
                it.descriptor == ownerPolicy.descriptor ||
                    (it.name == ownerPolicy.name &&
                        it.paramTypeNames == ownerPolicy.paramTypeNames &&
                        it.returnTypeName == "boolean")
            },
        )
        val attached =
            checkNotNull(bridge.getClassData(jni.typeName)).methods.single {
                it.name == "isAttached" &&
                    it.paramTypeNames.isEmpty() &&
                    it.returnTypeName == "boolean" &&
                    !Modifier.isNative(it.modifiers)
            }
        return mapOf(
            "contract" to "landrop-finished-engine-lock",
            "landrop_lock_field" to lock.descriptor,
            "landrop_destroy" to destroy.descriptor,
            "landrop_owner_policy" to ownerPolicy.descriptor,
            "landrop_delegate_field" to delegate.descriptor,
            "landrop_engine_field" to engineField.descriptor,
            "landrop_jni_field" to jni.descriptor,
            "landrop_attached" to attached.descriptor,
        )
    }

    private fun continuity(bridge: DexKitBridge): Map<String, String?> {
        val stop =
            bridge
                .findMethod { matcher { usingStrings(listOf("BleScanner", "stopScan")) } }
                .single {
                    it.paramTypeNames.isEmpty() &&
                        it.returnTypeName == "void" &&
                        it.calls("android.bluetooth.le.BluetoothLeScanner", "stopScan") &&
                        it.invokes.any { call ->
                            call.name == "shutdownNow" &&
                                call.paramTypeNames.isEmpty() &&
                                call.returnTypeName == "java.util.List" &&
                                call.declaredClassName in
                                    setOf(
                                        "java.util.concurrent.ExecutorService",
                                        "java.util.concurrent.ScheduledExecutorService",
                                    )
                        }
                }
        val type = checkNotNull(stop.declaredClass)
        val scanner =
            type.fields.single { it.typeName == "android.bluetooth.le.BluetoothLeScanner" }
        val callbacks =
            stop.usingFields
                .map { it.field }
                .distinctBy { it.descriptor }
                .single {
                    it.declaredClassName == type.name && it.typeName == "java.util.ArrayList"
                }
        check(type.methods.any { it.calls(scanner.typeName, "startScan") })
        return mapOf(
            "contract" to "continuity-complete-stop-batch",
            "continuity_stop" to stop.descriptor,
            "continuity_scanner_field" to scanner.descriptor,
            "continuity_callbacks_field" to callbacks.descriptor,
        )
    }
}
