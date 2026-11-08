package dev.ryuya.appbatterytools

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData

/** Every binding is proved from the installed APK. Obfuscated class names are not rules. */
internal object CleanupDiscovery {
    private const val MULTICAST = "android.net.wifi.WifiManager\$MulticastLock"

    fun explore(packageName: String, bridge: DexKitBridge): Map<String, String?> =
        when (packageName) {
            Targets.WEATHER -> weather(bridge)
            Targets.SMARTTHINGS -> smartThings(bridge)
            Targets.TAPO -> tapo(bridge)
            Targets.SIMEJI -> simeji(bridge)
            else -> error("Unknown cleanup target")
        }

    private fun MethodData.calls(owner: String, name: String): Boolean = invokes.any {
        it.declaredClassName == owner && it.name == name
    }

    private fun weather(bridge: DexKitBridge): Map<String, String?> {
        val start =
            bridge
                .findMethod {
                    matcher { usingStrings(listOf("WxBeacon2の定期受信間隔: %d sec")) }
                }
                .single { it.paramTypeNames.isEmpty() && it.returnTypeName == "void" }
        check(start.calls("java.util.Timer", "scheduleAtFixedRate"))
        val field =
            start.usingFields
                .map { it.field }
                .distinctBy { it.descriptor }
                .single {
                    it.typeName == "java.util.Timer" &&
                        it.declaredClassName == start.declaredClassName
                }
        val stop =
            checkNotNull(start.declaredClass).methods.single {
                it.paramTypeNames.isEmpty() &&
                    it.returnTypeName == "void" &&
                    it.calls("java.util.Timer", "purge") &&
                    !it.calls("java.util.Timer", "cancel") &&
                    it.usingFields.any { use -> use.field.descriptor == field.descriptor }
            }
        // Both the enabled-setting change and interval change must route through this stop.
        check(stop.callers.count { it.declaredClassName == start.declaredClassName } >= 2)
        return mapOf(
            "contract" to "weather-owned-timer",
            "weather_stop" to stop.descriptor,
            "weather_timer" to field.descriptor,
        )
    }

    private fun smartThings(bridge: DexKitBridge): Map<String, String?> {
        val subscribe =
            bridge
                .findMethod {
                    matcher {
                        usingStrings(listOf("[Diagnostics] DiscoveryModelCore", "invalid IPv4, "))
                    }
                }
                .single {
                    it.paramTypeNames == listOf("io.reactivex.FlowableEmitter") &&
                        it.returnTypeName == "void"
                }
        check(subscribe.calls("android.net.wifi.WifiManager", "createMulticastLock"))
        check(subscribe.calls(MULTICAST, "acquire"))
        check(subscribe.calls("io.reactivex.FlowableEmitter", "setCancellable"))
        check(!subscribe.calls(MULTICAST, "release"))
        val field =
            subscribe.usingFields
                .map { it.field }
                .distinctBy { it.descriptor }
                .single { it.typeName == MULTICAST }
        // R8 may merge captures into Object fields. The runtime value must still be the
        // verified lock owner; do not assume a particular synthetic field name or position.
        val ownerFields =
            subscribe.usingFields
                .map { it.field }
                .distinctBy { it.descriptor }
                .filter {
                    it.declaredClassName == subscribe.declaredClassName &&
                        !Modifier.isStatic(it.modifiers) &&
                        it.typeName in listOf(field.declaredClassName, "java.lang.Object")
                }
        check(ownerFields.size in 1..4)
        val cancelRegistration =
            bridge
                .findMethod {
                    matcher {
                        paramTypes("io.reactivex.functions.Cancellable")
                        returnType("void")
                    }
                }
                .single {
                    !Modifier.isAbstract(it.modifiers) &&
                        it.declaredClass?.interfaces?.any { type ->
                            type.name == "io.reactivex.FlowableEmitter"
                        } == true
                }
        return mapOf(
            "contract" to "smartthings-mdns-handoff",
            "smart_subscribe" to subscribe.descriptor,
            "smart_owner_fields" to ownerFields.joinToString("\n") { it.descriptor },
            "smart_lock" to field.descriptor,
            "smart_cancellable" to cancelRegistration.descriptor,
        )
    }

    private fun tapo(bridge: DexKitBridge): Map<String, String?> {
        val type =
            bridge
                .findClass { matcher { usingStrings(listOf("chipBrowseMulticastLock")) } }
                .single()
        val lock =
            type.fields.single { it.typeName == MULTICAST && !Modifier.isStatic(it.modifiers) }
        val map =
            type.fields.single {
                it.typeName == "java.util.HashMap" && !Modifier.isStatic(it.modifiers)
            }
        val start =
            type.methods.single {
                it.paramTypeNames.size == 4 &&
                    it.paramTypeNames.take(3) == listOf("java.lang.String", "long", "long") &&
                    it.returnTypeName == "void" &&
                    it.calls(MULTICAST, "acquire") &&
                    it.calls("android.net.nsd.NsdManager", "discoverServices")
            }
        check(!start.calls(MULTICAST, "release"))
        check(start.usingFields.any { it.field.descriptor == lock.descriptor })
        check(start.usingFields.any { it.field.descriptor == map.descriptor })
        check(
            type.methods.any {
                it.calls(MULTICAST, "release") &&
                    it.calls("android.net.nsd.NsdManager", "stopServiceDiscovery")
            },
        )
        return mapOf(
            "contract" to "tapo-failed-mdns-acquisition",
            "tapo_start" to start.descriptor,
            "tapo_lock" to lock.descriptor,
            "tapo_callbacks" to map.descriptor,
        )
    }

    private fun simeji(bridge: DexKitBridge): Map<String, String?> {
        val reporter =
            bridge
                .findMethod { matcher { usingStrings(listOf("anr_reports")) } }
                .single {
                    it.paramTypeNames.isEmpty() &&
                        it.returnTypeName == "void" &&
                        Modifier.isStatic(it.modifiers)
                }
        val initializers =
            reporter.callers.filter { it.declaredClassName == reporter.declaredClassName }
        val scheduler =
            initializers
                .flatMap { it.invokes }
                .distinctBy { it.descriptor }
                .single {
                    it.paramTypeNames.isEmpty() &&
                        it.returnTypeName == "void" &&
                        Modifier.isStatic(it.modifiers) &&
                        it.calls(
                            "java.util.concurrent.ScheduledExecutorService",
                            "scheduleAtFixedRate",
                        ) &&
                        it.usingNumbers.any { number -> number.longValue() == 500L }
                }
        check(
            checkNotNull(scheduler.declaredClass).methods.any {
                it.calls("android.app.ActivityManager", "getProcessesInErrorState")
            },
        )
        return mapOf(
            "contract" to "simeji-sdk-anr-polling",
            "simeji_schedule" to scheduler.descriptor,
        )
    }
}
