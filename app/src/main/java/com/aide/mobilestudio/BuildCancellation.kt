package com.aide.mobilestudio

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cancellation token for build-time tool provisioning.
 *
 * Provisioning runs before Gradle and downloads hundreds of megabytes; the
 * stop button must be able to abort that too, not just the Gradle run that
 * has not started yet. Downloads and extractions poll [isCancelled] between
 * chunks and bail out, so a cancel takes effect within one buffer rather
 * than after the whole archive.
 *
 * A cancelled run clears its partial file, so the next build starts the
 * download over instead of resuming an archive with a missing prefix.
 *
 * Not an [java.util.concurrent.CancellationToken] because provisioning is
 * plain blocking code on a worker thread; a flag it can poll is both enough
 * and the least invasive thing to thread through the existing installers.
 */
object BuildCancellation {

    private val cancelled = AtomicBoolean(false)

    /** Starts a new provisioning run. Called before the first download. */
    fun begin() {
        cancelled.set(false)
    }

    /** Requests that every running provisioning step stop as soon as it can. */
    fun cancel() {
        cancelled.set(true)
    }

    val isCancelled: Boolean
        get() = cancelled.get()

    /**
     * Throws when [cancel] has been called since [begin]. Provisioning steps
     * wrap their work in runCatching and treat this as an abort, not a
     * failure, so the user does not see a red error for their own cancel.
     */
    fun throwIfCancelled() {
        if (cancelled.get()) {
            throw ProvisioningCancelled()
        }
    }

    /** True when [t] (or its causes) is the user pressing stop. */
    fun isCancellation(t: Throwable?): Boolean {
        var current = t
        var depth = 0
        while (current != null && depth < 8) {
            if (current is ProvisioningCancelled) return true
            current = current.cause
            depth++
        }
        return false
    }
}

/** Thrown inside provisioning when the user pressed stop. */
class ProvisioningCancelled : InterruptedException("provisioning cancelled by user")
