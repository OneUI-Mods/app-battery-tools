package dev.ryuya.appbatterytools

import android.annotation.SuppressLint
import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge

/** Semantic proofs are serialized once per installed generation, never scanned in a hook. */
internal object ExtraDiscovery {
    @SuppressLint("DuplicateCreateDexKit") // Provider's durable generation cache calls this once.
    fun explore(packageName: String, apk: String): Map<String, String?> {
        // These two policies own Android BLE registrations, not protected/obfuscated app methods.
        if (packageName == Targets.GOVEE || packageName == Targets.SESAME)
            return mapOf("contract" to "android-ble-wifi")
        System.loadLibrary("dexkit")
        return DexKitBridge.create(apk).use { bridge ->
            bridge.setThreadNum(2)
            when (packageName) {
                Targets.WINDOWS -> windows(bridge)
                Targets.EQ -> equalizer(bridge)
                Targets.ECOVACS -> ecovacs(bridge)
                Targets.WEATHER,
                Targets.SMARTTHINGS,
                Targets.TAPO,
                Targets.SIMEJI -> CleanupDiscovery.explore(packageName, bridge)
                else -> error("Unknown target")
            }
        }
    }

    private fun windows(bridge: DexKitBridge): Map<String, String?> {
        val database =
            bridge
                .findMethod { matcher { usingStrings(listOf("beacon_database")) } }
                .single {
                    it.paramTypeNames == listOf("android.content.Context") &&
                        it.returnTypeName != "void" &&
                        !Modifier.isStatic(it.modifiers)
                }
        val adapter =
            bridge
                .findClass {
                    matcher {
                        usingStrings(listOf("keepalive.mtutimeoutmultiplier", "open.timeout"))
                    }
                }
                .single { type ->
                    type.methods.any {
                        it.name == "OnOpened" &&
                            it.paramTypeNames.size == 1 &&
                            it.returnTypeName == "void"
                    }
                }
        val opened =
            adapter.methods.single {
                it.name == "OnOpened" &&
                    it.paramTypeNames.size == 1 &&
                    it.returnTypeName == "void" &&
                    it.invokes.any { call ->
                        call.declaredClassName == "java.util.concurrent.CompletableFuture" &&
                            call.name == "complete"
                    }
            }
        val getter =
            opened.invokes
                .distinctBy { it.descriptor }
                .single {
                    it.declaredClassName == adapter.name &&
                        it.paramTypeNames.isEmpty() &&
                        it.returnTypeName == "java.util.concurrent.CompletableFuture"
                }
        val client = checkNotNull(bridge.getClassData(opened.paramTypeNames.single()))
        check(Modifier.isInterface(client.modifiers))
        val close =
            client.methods.single {
                it.name == "Close" && it.paramTypeNames.isEmpty() && it.returnTypeName == "void"
            }
        val resultConstructor =
            opened.invokes.single {
                it.name == "<init>" &&
                    it.paramTypeNames.size == 2 &&
                    it.paramTypeNames.last() == client.name
            }
        val result = checkNotNull(bridge.getClassData(resultConstructor.declaredClassName))
        val data =
            checkNotNull(result.superClass).methods.single {
                it.paramTypeNames.isEmpty() &&
                    it.returnTypeName == "java.lang.Object" &&
                    !Modifier.isStatic(it.modifiers)
            }
        return mapOf(
            "contract" to "windows-resources",
            "database" to database.descriptor,
            "opened" to opened.descriptor,
            "opened_future" to getter.descriptor,
            "result_data" to data.descriptor,
            "client_close" to close.descriptor,
        )
    }

    private fun equalizer(bridge: DexKitBridge): Map<String, String?> {
        val identity =
            bridge
                .findMethod { matcher { usingStrings(listOf("SHA-256", "name:")) } }
                .single {
                    it.paramTypeNames == List(3) { "java.lang.String" } &&
                        it.returnTypeName == "java.lang.String"
                }
        check(
            identity.invokes.any {
                it.declaredClassName == "java.security.MessageDigest" && it.name == "digest"
            },
        )
        val select =
            checkNotNull(identity.declaredClass).methods.single {
                it.returnTypeName == "boolean" &&
                    it.paramTypeNames.firstOrNull() == "android.content.Context" &&
                    it.usingStrings.containsAll(
                        listOf("[0-9a-f]{64}", "route_active", "profile_initialized"),
                    )
            }
        return mapOf(
            "contract" to "eq-route-computation",
            "eq_identity" to identity.descriptor,
            "eq_select" to select.descriptor,
        )
    }

    private fun ecovacs(bridge: DexKitBridge): Map<String, String?> {
        val type =
            bridge
                .findClass {
                    matcher {
                        usingStrings(
                            listOf("ecoiot_matter_mdns", "ChipDeviceController already null"),
                        )
                    }
                }
                .single()
        val lock =
            type.fields.single {
                it.typeName == "android.net.wifi.WifiManager\$MulticastLock" &&
                    Modifier.isStatic(it.modifiers)
            }
        val controller =
            type.fields.single {
                it.typeName == "chip.devicecontroller.ChipDeviceController" &&
                    Modifier.isStatic(it.modifiers)
            }
        val initialize =
            type.methods.single {
                it.paramTypeNames == listOf("android.content.Context") &&
                    it.returnTypeName == "void" &&
                    it.usingStrings.contains("ecoiot_matter_mdns")
            }
        val create =
            type.methods.single {
                it.paramTypeNames == listOf("android.content.Context") &&
                    it.returnTypeName == controller.typeName &&
                    it.usingStrings.contains(
                        "Matter controller not configured. Call MatterCloudControllerBootstrap.ensureReady() first (LAN/Matter mode).",
                    )
            }
        val destroy =
            type.methods.single {
                it.paramTypeNames.isEmpty() &&
                    it.returnTypeName == "void" &&
                    it.usingStrings.contains("ChipDeviceController already null")
            }
        check(destroy.invokes.any { it.name == "stopDnssd" })
        // An upstream fix supersedes ours; do not install a second lifecycle owner.
        check(
            destroy.invokes.none { it.name == "release" && it.declaredClassName == lock.typeName },
        )
        return mapOf(
            "contract" to "ecovacs-matter",
            "matter_lock" to lock.descriptor,
            "matter_controller" to controller.descriptor,
            "matter_initialize" to initialize.descriptor,
            "matter_create" to create.descriptor,
            "matter_destroy" to destroy.descriptor,
        )
    }
}
