package com.aide.mobilestudio

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildCancellationTest {

    @After
    fun tearDown() {
        // The token is process-wide; leave it clear for the other tests.
        BuildCancellation.begin()
    }

    @Test
    fun `a fresh run is not cancelled`() {
        BuildCancellation.begin()
        assertFalse(BuildCancellation.isCancelled)
    }

    @Test
    fun `cancel takes effect until the next run`() {
        BuildCancellation.begin()
        BuildCancellation.cancel()
        assertTrue(BuildCancellation.isCancelled)
    }

    @Test
    fun `begin clears a previous cancel`() {
        BuildCancellation.cancel()
        BuildCancellation.begin()
        assertFalse(BuildCancellation.isCancelled)
    }

    @Test
    fun `throwIfCancelled only throws after a cancel`() {
        BuildCancellation.begin()
        BuildCancellation.throwIfCancelled() // must not throw

        BuildCancellation.cancel()
        var threw = false
        try {
            BuildCancellation.throwIfCancelled()
        } catch (e: ProvisioningCancelled) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `a wrapped cancellation is still recognised as one`() {
        BuildCancellation.cancel()
        val wrapped = RuntimeException("install failed", ProvisioningCancelled())
        assertTrue(BuildCancellation.isCancellation(wrapped))
    }

    @Test
    fun `an unrelated failure is not a cancellation`() {
        BuildCancellation.begin()
        assertFalse(BuildCancellation.isCancellation(RuntimeException("disk full")))
        assertFalse(BuildCancellation.isCancellation(null))
        assertFalse(BuildCancellation.isCancellation(IllegalStateException()))
    }

    @Test
    fun `a deeply nested cancellation is still found`() {
        BuildCancellation.cancel()
        val deep = RuntimeException(
            RuntimeException(
                RuntimeException(ProvisioningCancelled()),
            ),
        )
        assertTrue(BuildCancellation.isCancellation(deep))
    }
}
