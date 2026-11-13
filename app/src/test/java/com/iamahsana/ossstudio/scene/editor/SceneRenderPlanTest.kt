package com.iamahsana.ossstudio.scene.editor

import com.iamahsana.ossstudio.scene.model.Scene
import com.iamahsana.ossstudio.scene.model.SceneAsset
import com.iamahsana.ossstudio.scene.model.SceneElement
import com.iamahsana.ossstudio.scene.model.SceneProject
import com.iamahsana.ossstudio.scene.model.SceneSource
import com.iamahsana.ossstudio.scene.model.SceneSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneRenderPlanTest {
    @Test
    fun interleavesStaticCanvasSegmentsAndLiveSourcesInExactLayerOrder() {
        val fixture = project(
            sources = listOf(
                source("text-before", SceneSourceType.TEXT),
                source("video", SceneSourceType.VIDEO, "video-asset"),
                source("text-middle", SceneSourceType.TEXT),
                source("camera", SceneSourceType.CAMERA),
                source("text-after", SceneSourceType.TEXT)
            ),
            elements = listOf(
                element("before", "text-before", 0),
                element("video-element", "video", 1),
                element("middle", "text-middle", 2),
                element("camera-element", "camera", 3),
                element("after", "text-after", 4)
            )
        )

        val plan = SceneRenderPlan.build(
            fixture.first,
            fixture.second,
            SceneCanvasLayerController.LayerPosition.ALL,
            null
        )

        assertEquals(5, plan.size)
        assertTrue(plan[0] is SceneRenderNode.CanvasSegment)
        assertEquals(listOf("before"), (plan[0] as SceneRenderNode.CanvasSegment).elements.map { it.id })
        assertTrue((plan[0] as SceneRenderNode.CanvasSegment).drawsBackground)
        assertEquals("video-element", (plan[1] as SceneRenderNode.LiveSource).element.id)
        assertEquals(listOf("middle"), (plan[2] as SceneRenderNode.CanvasSegment).elements.map { it.id })
        assertEquals("camera-element", (plan[3] as SceneRenderNode.LiveSource).element.id)
        assertEquals(listOf("after"), (plan[4] as SceneRenderNode.CanvasSegment).elements.map { it.id })
        assertTrue(!(plan[4] as SceneRenderNode.CanvasSegment).drawsBackground)
    }

    @Test
    fun firstDynamicSourceStillReceivesAnOpaqueBackgroundSegmentBeforeIt() {
        val fixture = project(
            sources = listOf(source("video", SceneSourceType.VIDEO, "video-asset")),
            elements = listOf(element("video-element", "video", 0))
        )

        val plan = SceneRenderPlan.build(
            fixture.first,
            fixture.second,
            SceneCanvasLayerController.LayerPosition.ALL,
            null
        )

        assertEquals(3, plan.size)
        val background = plan.first() as SceneRenderNode.CanvasSegment
        assertTrue(background.drawsBackground)
        assertTrue(background.elements.isEmpty())
        assertTrue(plan[1] is SceneRenderNode.LiveSource)
        assertTrue((plan[2] as SceneRenderNode.CanvasSegment).elements.isEmpty())
    }

    @Test
    fun shareScreenPartitionsLiveSourcesWithoutRenderingShareScreenTwice() {
        val fixture = project(
            sources = listOf(
                source("below", SceneSourceType.VIDEO, "video-asset"),
                source("screen", SceneSourceType.SHARE_SCREEN),
                source("above", SceneSourceType.CAMERA)
            ),
            elements = listOf(
                element("below-element", "below", 0),
                element("screen-element", "screen", 1),
                element("above-element", "above", 2)
            )
        )

        val below = SceneRenderPlan.build(
            fixture.first,
            fixture.second,
            SceneCanvasLayerController.LayerPosition.BELOW_SHARE_SCREEN,
            1
        )
        val above = SceneRenderPlan.build(
            fixture.first,
            fixture.second,
            SceneCanvasLayerController.LayerPosition.ABOVE_SHARE_SCREEN,
            1
        )

        assertEquals("below-element", (below[1] as SceneRenderNode.LiveSource).element.id)
        assertEquals("above-element", (above[1] as SceneRenderNode.LiveSource).element.id)
        assertTrue(below.none { it is SceneRenderNode.LiveSource && it.source.type == SceneSourceType.SHARE_SCREEN })
        assertTrue(above.none { it is SceneRenderNode.LiveSource && it.source.type == SceneSourceType.SHARE_SCREEN })
    }

    @Test
    fun hiddenAndDisabledLiveSourcesDoNotCreateDecoderNodes() {
        val disabled = source("disabled", SceneSourceType.CAMERA).copy(enabled = false)
        val fixture = project(
            sources = listOf(disabled, source("text", SceneSourceType.TEXT)),
            elements = listOf(
                element("hidden-camera", "disabled", 0),
                element("visible-text", "text", 1)
            )
        )
        val hiddenScene = fixture.second.copy(
            elements = fixture.second.elements.map { it.copy(visible = it.id != "hidden-camera") }
        )

        val plan = SceneRenderPlan.build(
            fixture.first,
            hiddenScene,
            SceneCanvasLayerController.LayerPosition.ALL,
            null
        )

        assertEquals(1, plan.size)
        assertEquals(listOf("visible-text"), (plan.single() as SceneRenderNode.CanvasSegment).elements.map { it.id })
    }

    @Test
    fun geometryChangesDoNotRecreateLiveSourcesButAssetChangesDo() {
        val fixture = project(
            sources = listOf(source("video", SceneSourceType.VIDEO, "video-asset")),
            elements = listOf(element("video-element", "video", 0))
        )
        val initial = SceneRenderPlan.build(
            fixture.first,
            fixture.second,
            SceneCanvasLayerController.LayerPosition.ALL,
            null
        )
        val movedScene = fixture.second.copy(
            elements = fixture.second.elements.map {
                it.copy(transform = it.transform.copy(x = 40f, width = 500f))
            }
        )
        val moved = SceneRenderPlan.build(
            fixture.first,
            movedScene,
            SceneCanvasLayerController.LayerPosition.ALL,
            null
        )
        assertEquals(
            SceneRenderPlan.liveSourceSignature(fixture.first, initial),
            SceneRenderPlan.liveSourceSignature(fixture.first, moved)
        )

        val changedAsset = fixture.first.copy(
            assets = fixture.first.assets.map { it.copy(sha256 = "b".repeat(64)) }
        )
        val changed = SceneRenderPlan.build(
            changedAsset,
            fixture.second,
            SceneCanvasLayerController.LayerPosition.ALL,
            null
        )
        assertTrue(
            SceneRenderPlan.liveSourceSignature(fixture.first, initial) !=
                SceneRenderPlan.liveSourceSignature(changedAsset, changed)
        )
    }

    private fun project(
        sources: List<SceneSource>,
        elements: List<SceneElement>
    ): Pair<SceneProject, Scene> {
        val asset = SceneAsset(
            id = "video-asset",
            relativePath = "assets/clip.mp4",
            mimeType = "video/mp4",
            byteSize = 1,
            sha256 = "a".repeat(64)
        )
        val scene = Scene(
            id = "main",
            projectId = "project",
            name = "Main",
            elements = elements
        )
        val assets = if (sources.any { it.assetId == asset.id }) listOf(asset) else emptyList()
        val project = SceneProject(
            id = "project",
            name = "Plan test",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            activeSceneId = scene.id,
            scenes = listOf(scene),
            sources = sources,
            assets = assets
        )
        return project to scene
    }

    private fun source(id: String, type: SceneSourceType, assetId: String? = null) =
        SceneSource(id = id, type = type, displayName = id, assetId = assetId)

    private fun element(id: String, sourceId: String, zIndex: Int) =
        SceneElement(id = id, sourceId = sourceId, zIndex = zIndex)
}
