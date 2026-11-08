package dev.ryuya.appbatterytools

import android.annotation.SuppressLint
import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge

/** Proves the host's terminal callback and the framework's privately created executor. */
internal object CameraWorkerDiscovery {
    const val FRAMEWORK = "/system/framework/framework.jar"

    @SuppressLint("DuplicateCreateDexKit") // A second APK in the same durable, OS-aware generation.
    fun explore(host: DexKitBridge, framework: String): Map<String, String?> {
        val closed =
            host
                .findMethod {
                    matcher { usingStrings(listOf("Camera Device onClosed")) }
                }
                .single {
                    it.name == "onClosed" &&
                        it.paramTypeNames == listOf("android.hardware.camera2.CameraDevice") &&
                        it.returnTypeName == "void" &&
                        it.declaredClass?.superClass?.name ==
                            "android.hardware.camera2.CameraDevice\$StateCallback"
                }
        return DexKitBridge.create(framework).use { platform ->
            platform.setThreadNum(2)
            val factories =
                platform
                    .findMethod {
                        matcher { usingStrings(listOf("CameraDeviceExecutor")) }
                    }
                    .filter {
                        it.name == "newThread" &&
                            it.paramTypeNames == listOf("java.lang.Runnable") &&
                            it.returnTypeName == "java.lang.Thread" &&
                            it.invokes.any { call ->
                                call.declaredClassName == "java.lang.Thread" &&
                                    call.name == "setName"
                            }
                    }
            if (factories.isEmpty())
                return@use mapOf("contract" to "sead-private-worker-unavailable")
            val factoryMethod = factories.single()
            val device =
                checkNotNull(
                    platform.getClassData("android.hardware.camera2.impl.CameraDeviceImpl"),
                )
            val factory =
                device.fields.single {
                    it.typeName == "java.util.concurrent.ThreadFactory" &&
                        Modifier.isStatic(it.modifiers)
                }
            check(
                device.methods.any { init ->
                    init.name == "<clinit>" &&
                        init.usingFields.any { it.field.descriptor == factory.descriptor } &&
                        init.invokes.any {
                            it.name == "<init>" &&
                                it.declaredClassName == factoryMethod.declaredClassName
                        }
                },
            )
            val constructor =
                device.methods.single { method ->
                    method.name == "<init>" &&
                        method.usingFields.any { it.field.descriptor == factory.descriptor } &&
                        method.invokes.any {
                            it.declaredClassName == "java.util.concurrent.Executors" &&
                                it.name == "newSingleThreadExecutor" &&
                                it.paramTypeNames == listOf("java.util.concurrent.ThreadFactory")
                        }
                }
            val executor =
                constructor.usingFields
                    .map { it.field }
                    .distinctBy { it.descriptor }
                    .single {
                        it.declaredClassName == device.name &&
                            it.typeName == "java.util.concurrent.Executor" &&
                            Modifier.isFinal(it.modifiers) &&
                            !Modifier.isStatic(it.modifiers)
                    }
            check(
                device.methods.any { method ->
                    method.name == "close" &&
                        method.paramTypeNames.isEmpty() &&
                        method.usingFields.any { it.field.descriptor == executor.descriptor } &&
                        method.invokes.any {
                            it.declaredClassName == "java.util.concurrent.Executor" &&
                                it.name == "execute"
                        }
                },
            )
            mapOf(
                "contract" to "sead-camera-idle-worker",
                "sead_closed" to closed.descriptor,
                "sead_executor_field" to executor.descriptor,
                "sead_factory_field" to factory.descriptor,
            )
        }
    }
}
