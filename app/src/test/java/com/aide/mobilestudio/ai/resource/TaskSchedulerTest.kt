package com.aide.mobilestudio.ai.resource

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TaskSchedulerTest {

    private fun mem(availMb: Long, low: Boolean = false): MemorySnapshot {
        val heapMax = 512L * 1024 * 1024
        return MemorySnapshot(
            availableBytes = availMb * 1024 * 1024,
            totalBytes = 4096L * 1024 * 1024,
            lowMemory = low,
            heapUsedBytes = heapMax / 4,
            heapMaxBytes = heapMax,
        )
    }

    @Test
    fun `empty task list returns immediately`() = runBlocking(Dispatchers.Default) {
        val outcome = TaskScheduler().runAll<Int>(emptyList(), mem(3000))
        assertTrue(outcome.results.isEmpty())
        assertEquals(0, outcome.parallelism)
    }

    @Test
    fun `results keep input order and parallelism is bounded`() = runBlocking(Dispatchers.Default) {
        val scheduler = TaskScheduler()
        val current = AtomicInteger(0)
        val maxSeen = AtomicInteger(0)
        val tasks = (0 until 8).map { i ->
            suspend {
                val c = current.incrementAndGet()
                maxSeen.updateAndGet { m -> maxOf(m, c) }
                delay(40)
                current.decrementAndGet()
                i
            }
        }
        val outcome = scheduler.runAll(tasks, mem(3000), userMax = 2)
        assertEquals((0 until 8).toList(), outcome.results)
        assertTrue("parallelism must be ≤ 2 but was ${maxSeen.get()}", maxSeen.get() <= 2)
        assertTrue(outcome.parallelism in 1..2)
    }

    @Test
    fun `low memory runs sequentially`() = runBlocking(Dispatchers.Default) {
        val current = AtomicInteger(0)
        val maxSeen = AtomicInteger(0)
        val tasks = (0 until 5).map { i ->
            suspend {
                val c = current.incrementAndGet()
                maxSeen.updateAndGet { m -> maxOf(m, c) }
                delay(20)
                current.decrementAndGet()
                i
            }
        }
        val outcome = TaskScheduler().runAll(tasks, mem(3000, low = true), userMax = 4)
        assertEquals(1, outcome.parallelism)
        assertEquals((0 until 5).toList(), outcome.results)
        assertEquals(1, maxSeen.get())
    }
}
