package dev.ryuya.appbatterytools

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData

/** Recognize the slot-page banner player, independently of its obfuscated method names. */
internal object GalaxyStoreDiscovery {
    const val MAIN = "com.sec.android.app.samsungapps.slotpage.GalaxyAppsMainActivity"
    private const val WRAPPER = "com.sec.android.app.samsungapps.slotpage.ExoPlayer"
    private const val VIEW = "com.google.android.exoplayer2.ui.PlayerView"

    fun explore(bridge: DexKitBridge): Map<String, String?> {
        val wrapper = checkNotNull(bridge.getClassData(WRAPPER))
        val getPlayer =
            wrapper.methods.single { it.name == "getPlayer" && it.paramTypeNames.isEmpty() }
        check(
            wrapper.methods.count {
                it.name == "getPlayerView" &&
                    it.paramTypeNames.isEmpty() &&
                    it.returnTypeName == VIEW
            } == 1,
        )
        val player = checkNotNull(bridge.getClassData(getPlayer.returnTypeName))
        val create =
            bridge
                .findMethod {
                    matcher {
                        usingStrings(listOf("null cannot be cast to non-null type $VIEW"))
                    }
                }
                .single { method ->
                    method.paramTypeNames == listOf("int") &&
                        method.returnTypeName == "void" &&
                        method.invokes.any {
                            it.declaredClassName == WRAPPER &&
                                it.name == "<init>" &&
                                it.paramTypeNames == listOf(VIEW, player.name)
                        }
                }
        val controller = checkNotNull(create.declaredClass)
        val pause =
            controller.methods.single { method ->
                method.paramTypeNames.isEmpty() &&
                    method.returnTypeName == "void" &&
                    method.invokes.any { it.descriptor == getPlayer.descriptor } &&
                    method.invokes.any {
                        it.declaredClassName == "android.media.AudioManager" &&
                            it.name == "abandonAudioFocus"
                    }
            }
        val registry =
            create.usingFields
                .map { it.field }
                .distinctBy { it.descriptor }
                .single { field ->
                    field.declaredClassName == controller.name &&
                        field.typeName == "java.util.HashMap" &&
                        Modifier.isStatic(field.modifiers) &&
                        Modifier.isFinal(field.modifiers) &&
                        pause.usingFields.any { it.field.descriptor == field.descriptor }
                }
        val play =
            pause.invokes
                .distinctBy { it.descriptor }
                .single {
                    it.declaredClassName == player.name &&
                        it.paramTypeNames == listOf("boolean") &&
                        it.returnTypeName == "void"
                }
        val state =
            play.invokes
                .distinctBy { it.descriptor }
                .single {
                    it.declaredClassName == player.name &&
                        it.paramTypeNames.isEmpty() &&
                        it.returnTypeName == "int"
                }
        val focus =
            play.invokes
                .distinctBy { it.descriptor }
                .single {
                    it.declaredClassName != player.name &&
                        it.paramTypeNames == listOf("int", "boolean") &&
                        it.returnTypeName == "int"
                }
        val focusOperations =
            player.methods.filter { method ->
                val stopWithReset =
                    method.name == "stop" && method.paramTypeNames == listOf("boolean")
                (method.paramTypeNames.isEmpty() || stopWithReset) &&
                    method.returnTypeName == "void" &&
                    method.invokes.any { it.descriptor == focus.descriptor }
            }
        val stop = focusOperations.single { method ->
            method.usingFields.any {
                it.field.declaredClassName == "java.util.Collections" &&
                    it.field.name == "EMPTY_LIST"
            }
        }
        val prepare = focusOperations.single { method ->
            method.descriptor != stop.descriptor &&
                method.invokes.any {
                    it.declaredClassName == player.name &&
                        it.paramTypeNames == listOf("int", "int", "boolean")
                }
        }
        // The real resume path must be able to issue a new play request after a stop.
        val start =
            controller.methods.single { method ->
                method.paramTypeNames == listOf("java.lang.String") &&
                    method.returnTypeName == "void" &&
                    method.invokes.any { it.descriptor == play.descriptor } &&
                    method.usingFields.any { it.field.descriptor == registry.descriptor }
            }
        val resume =
            checkNotNull(bridge.getClassData(MAIN)).methods.single {
                it.name == "onResume" && it.paramTypeNames.isEmpty()
            }
        check(reachesPlayback(resume, start, delegates = 2)) {
            "Banner not reachable from store resume"
        }
        return mapOf(
            "contract" to "galaxy-store-slot-playback",
            "store_registry" to registry.descriptor,
            "store_player" to getPlayer.descriptor,
            "store_play" to play.descriptor,
            "store_state" to state.descriptor,
            "store_stop" to stop.descriptor,
            "store_prepare" to prepare.descriptor,
        )
    }

    private fun reachesPlayback(
        resume: MethodData,
        play: MethodData,
        delegates: Int,
    ): Boolean =
        resume.invokes.any { call ->
            call.descriptor == play.descriptor ||
                (delegates > 0 &&
                    call.name == "onResume" &&
                    (call.declaredClassName.startsWith("com.sec.android.app.samsungapps.") ||
                        call.declaredClassName.startsWith(
                            "com.samsung.android.game.cloudgame.sdk.",
                        )) &&
                    reachesPlayback(call, play, delegates - 1))
        }
}
