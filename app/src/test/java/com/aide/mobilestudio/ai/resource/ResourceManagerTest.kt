package com.aide.mobilestudio.ai.resource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceManagerTest {

    private val manager = ResourceManager()

    private fun mem(availMb: Long, low: Boolean = false, heapFraction: Double = 0.2): MemorySnapshot {
        val heapMax = 512L * 1024 * 1024
        return MemorySnapshot(
            availableBytes = availMb * 1024 * 1024,
            totalBytes = 4096L * 1024 * 1024,
            lowMemory = low,
            heapUsedBytes = (heapFraction * heapMax).toLong(),
            heapMaxBytes = heapMax,
        )
    }

    @Test
    fun `no pending tasks gives zero`() {
        val decision = manager.decide(mem(3000), 0, 0, false, 5)
        assertEquals(0, decision.count)
    }

    @Test
    fun `healthy memory allows the configured maximum`() {
        val decision = manager.decide(mem(3000), 10, 0, false, 5)
        assertEquals(5, decision.count)
        assertEquals("resources sufficient", decision.reason)
    }

    @Test
    fun `low memory drops to one task`() {
        val decision = manager.decide(mem(3000, low = true), 10, 0, false, 5)
        assertEquals(1, decision.count)
    }

    @Test
    fun `critical free memory drops to one task`() {
        val decision = manager.decide(mem(200), 10, 0, false, 5)
        assertEquals(1, decision.count)
        assertTrue(decision.reason.contains("critical"))
    }

    @Test
    fun `moderate free memory caps at two`() {
        val decision = manager.decide(mem(700), 10, 0, false, 5)
        assertEquals(2, decision.count)
        assertTrue(decision.reason.contains("low free memory"))
    }

    @Test
    fun `resident local model reduces parallelism`() {
        val decision = manager.decide(mem(3000), 10, 1, false, 5)
        assertEquals(2, decision.count)
        assertTrue(decision.reason.contains("local model"))
    }

    @Test
    fun `running build reduces parallelism`() {
        val decision = manager.decide(mem(3000), 10, 0, buildRunning = true, userMax = 5)
        assertEquals(2, decision.count)
    }

    @Test
    fun `heap pressure reduces parallelism`() {
        val decision = manager.decide(mem(3000, heapFraction = 0.95), 10, 0, false, 5)
        assertEquals(2, decision.count)
        assertTrue(decision.reason.contains("heap"))
    }

    @Test
    fun `user limit is respected`() {
        val decision = manager.decide(mem(3000), 10, 0, false, 1)
        assertEquals(1, decision.count)
    }

    @Test
    fun `count never exceeds pending tasks`() {
        val decision = manager.decide(mem(3000), 1, 0, false, 5)
        assertEquals(1, decision.count)
    }

    @Test
    fun `model load blocked when memory estimate exceeds available`() {
        val (allowed, reason) = manager.canLoadModel(mem(1000), alreadyLoaded = 0)
        assertFalse(allowed)
        assertTrue(reason.contains("estimated"))
    }

    @Test
    fun `model load allowed with headroom`() {
        val (allowed, _) = manager.canLoadModel(mem(3000), alreadyLoaded = 0)
        assertTrue(allowed)
    }

    @Test
    fun `build and inference coexist only with headroom`() {
        assertTrue(manager.canRunBuildWithInference(mem(3000), inferenceActive = false))
        assertTrue(manager.canRunBuildWithInference(mem(3000), inferenceActive = true))
        assertFalse(manager.canRunBuildWithInference(mem(1000), inferenceActive = true))
        assertFalse(manager.canRunBuildWithInference(mem(3000, low = true), inferenceActive = true))
    }
}
