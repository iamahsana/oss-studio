package com.iamahsana.ossstudio.audio

/**
 * Retryable stop/release gate for one captured audio source.
 *
 * A failed stop retains the source and prevents release/overlapping sessions.
 * A failed release retries only release, never repeats a successful stop.
 */
internal class CaptureSourceCleanup(
    private val stopSource: () -> Unit,
    private val releaseSource: () -> Unit
) {
    private var startAttempted = false
    private var stopCompleted = false
    private var releaseCompleted = false

    fun markStartAttempted() {
        startAttempted = true
    }

    @Synchronized
    fun cleanup(): Boolean {
        if (startAttempted && !stopCompleted) {
            try {
                stopSource()
                stopCompleted = true
            } catch (_: Exception) {
                return false
            }
        } else if (!startAttempted) {
            stopCompleted = true
        }

        if (!releaseCompleted) {
            try {
                releaseSource()
                releaseCompleted = true
            } catch (_: Exception) {
                return false
            }
        }
        return stopCompleted && releaseCompleted
    }
}
