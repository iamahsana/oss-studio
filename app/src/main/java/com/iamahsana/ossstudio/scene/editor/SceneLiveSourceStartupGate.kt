package com.iamahsana.ossstudio.scene.editor

/**
 * Prevents LIVE readiness from being reported until every GL surface and every
 * asynchronous camera/video source has reached its first-frame/open milestone.
 */
internal class SceneLiveSourceStartupGate(
    private val expectedSurfaceCount: Int
) {
    private var readySurfaceCount = 0
    private val pendingSourceIds = mutableSetOf<String>()

    init {
        require(expectedSurfaceCount > 0) { "At least one compositor surface is required" }
    }

    fun awaitSource(sourceId: String) {
        require(sourceId.isNotBlank())
        check(pendingSourceIds.add(sourceId)) { "Source is already awaiting readiness: $sourceId" }
    }

    fun surfaceReady() {
        check(readySurfaceCount < expectedSurfaceCount) { "More surfaces became ready than the render plan contains" }
        readySurfaceCount++
    }

    fun sourceReady(sourceId: String): Boolean = pendingSourceIds.remove(sourceId)

    fun isPending(sourceId: String): Boolean = sourceId in pendingSourceIds

    val isReady: Boolean
        get() = readySurfaceCount == expectedSurfaceCount && pendingSourceIds.isEmpty()
}
