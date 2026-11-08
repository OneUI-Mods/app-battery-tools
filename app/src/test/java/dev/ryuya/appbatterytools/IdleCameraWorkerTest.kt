package dev.ryuya.appbatterytools

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class IdleCameraWorkerTest {
    private val factory = ThreadFactory { Thread(it, "CameraWorkerTest").apply { isDaemon = true } }

    private fun pool() =
        ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, LinkedBlockingQueue(), factory)

    private class Delegate(private val executor: Executor) : Executor {
        override fun execute(command: Runnable) = executor.execute(command)
    }

    @Test
    fun queuedWorkFinishesAndNewWorkCanRestartAfterIdleRetirement() {
        val pool = pool()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        try {
            val first =
                pool.submit<Int> {
                    entered.countDown()
                    check(proceed.await(3, TimeUnit.SECONDS))
                    check(!Thread.currentThread().isInterrupted)
                    1
                }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val next = pool.submit<Int> { 2 }
            assertTrue(IdleCameraWorker.retire(Delegate(Delegate(pool)), factory))
            assertFalse(pool.isShutdown)
            assertEquals(1, pool.corePoolSize)
            assertEquals(1, pool.maximumPoolSize)
            proceed.countDown()
            assertEquals(1, first.get(3, TimeUnit.SECONDS))
            assertEquals(2, next.get(3, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (pool.poolSize != 0 && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(0, pool.poolSize)
            assertEquals(3, pool.submit<Int> { 3 }.get(3, TimeUnit.SECONDS))
        } finally {
            proceed.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun unrelatedFactoryIsPreserved() {
        val pool = pool()
        try {
            assertFalse(IdleCameraWorker.retire(pool, Executors.defaultThreadFactory()))
            assertFalse(pool.allowsCoreThreadTimeOut())
            assertEquals(0, pool.getKeepAliveTime(TimeUnit.NANOSECONDS))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun existingIdlePolicyIsPreserved() {
        val pool = pool()
        try {
            pool.setKeepAliveTime(7, TimeUnit.SECONDS)
            pool.allowCoreThreadTimeOut(true)
            assertFalse(IdleCameraWorker.retire(pool, factory))
            assertEquals(7, pool.getKeepAliveTime(TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun customKeepAliveWithoutTimeoutIsPreserved() {
        val pool = pool()
        try {
            pool.setKeepAliveTime(8, TimeUnit.SECONDS)
            assertFalse(IdleCameraWorker.retire(pool, factory))
            assertFalse(pool.allowsCoreThreadTimeOut())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun parallelPoolIsPreserved() {
        val pool = pool()
        try {
            pool.maximumPoolSize = 2
            assertFalse(IdleCameraWorker.retire(pool, factory))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun shutdownExecutorIsNotReactivated() {
        val pool = pool()
        pool.shutdown()
        assertFalse(IdleCameraWorker.retire(pool, factory))
        assertTrue(pool.isShutdown)
    }

    @Test
    fun missingOwnershipIsPreserved() {
        val pool = pool()
        try {
            assertFalse(IdleCameraWorker.retire(pool, null))
            assertFalse(IdleCameraWorker.retire(null, factory))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun unknownAndCyclicDelegatesAreRejected() {
        class Cycle : Executor {
            val delegate: Executor = this

            override fun execute(command: Runnable) = Unit
        }
        assertFalse(IdleCameraWorker.retire(Cycle(), factory))
        assertFalse(IdleCameraWorker.retire(Executor { it.run() }, factory))
    }

    @Test
    fun ambiguousAndDeepDelegatesAreRejected() {
        val pool = pool()
        val other = pool()
        class Ambiguous(val first: Executor, val second: Executor) : Executor {
            override fun execute(command: Runnable) = first.execute(command)
        }
        try {
            assertFalse(IdleCameraWorker.retire(Ambiguous(pool, other), factory))
            assertFalse(
                IdleCameraWorker.retire(Delegate(Delegate(Delegate(Delegate(pool)))), factory),
            )
        } finally {
            pool.shutdownNow()
            other.shutdownNow()
        }
    }

    @Test
    fun duplicateDelegateFieldsMayAliasTheSameOwnedPool() {
        open class Parent(val delegate: Executor) : Executor {
            override fun execute(command: Runnable) = delegate.execute(command)
        }
        class Child(pool: Executor) : Parent(pool) {
            val sameDelegate: Executor = pool
        }
        val pool = pool()
        try {
            assertTrue(IdleCameraWorker.retire(Child(pool), factory))
        } finally {
            pool.shutdownNow()
        }
    }
}
