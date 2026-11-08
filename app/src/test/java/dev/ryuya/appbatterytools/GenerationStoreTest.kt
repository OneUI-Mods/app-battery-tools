package dev.ryuya.appbatterytools

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class GenerationStoreTest {
    private fun <T> directory(run: (File) -> T): T {
        val file = Files.createTempDirectory("battery-cache-").toFile()
        try {
            return run(file)
        } finally {
            file.deleteRecursively()
        }
    }

    @Test
    fun normalProcessRestartsDoNotRepeatExploration() = directory { dir ->
        var runs = 0
        repeat(4) {
            assertEquals(
                "ready",
                GenerationStore(dir)
                    .readOrExplore("apk-a") {
                        runs++
                        mapOf("remove" to "d")
                    }
                    .state,
            )
        }
        assertEquals(1, runs)
    }

    @Test
    fun targetUpdateOwnsOneNewDiscovery() = directory { dir ->
        var runs = 0
        val store = GenerationStore(dir)
        for (apk in listOf("apk-a", "apk-a", "apk-b", "apk-b")) store.readOrExplore(apk) {
            runs++
            emptyMap()
        }
        assertEquals(2, runs)
    }

    @Test
    fun failedSearchIsTerminalForThatGeneration() = directory { dir ->
        var runs = 0
        val store = GenerationStore(dir)
        repeat(3) {
            assertEquals(
                "failed",
                store
                    .readOrExplore("apk-a") {
                        runs++
                        error("Ambiguous methods")
                    }
                    .state,
            )
        }
        assertEquals(1, runs)
    }

    @Test
    fun concurrentConsumersStillRunOneExploration() = directory { dir ->
        val count = AtomicInteger()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs =
                (1..12).map {
                    pool.submit<String> {
                        GenerationStore(dir)
                            .readOrExplore("apk-a") {
                                count.incrementAndGet()
                                mapOf("service" to "AreaService")
                            }
                            .state
                    }
                }
            assertTrue(jobs.all { it.get() == "ready" })
            assertEquals(1, count.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun interruptedNativeSearchIsNotRetriedOnEveryStart() = directory { dir ->
        val store = GenerationStore(dir)
        try {
            store.readOrExplore("apk-a") { throw AssertionError("Process aborted") }
        } catch (_: AssertionError) {}
        assertEquals(
            "interrupted",
            GenerationStore(dir).readOrExplore("apk-a") { error("Must not retry") }.state,
        )
    }

    @Test
    fun corruptResultsDoNotCauseAnEndlessSearchLoop() = directory { dir ->
        File(dir, GenerationStore.digest("apk-a") + ".bin").writeText("broken")
        assertEquals(
            "corrupt",
            GenerationStore(dir).readOrExplore("apk-a") { error("Must not retry") }.state,
        )
    }
}
