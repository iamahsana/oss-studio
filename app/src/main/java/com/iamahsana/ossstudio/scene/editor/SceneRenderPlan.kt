package com.iamahsana.ossstudio.scene.editor

import com.iamahsana.ossstudio.scene.model.Scene
import com.iamahsana.ossstudio.scene.model.SceneAsset
import com.iamahsana.ossstudio.scene.model.SceneElement
import com.iamahsana.ossstudio.scene.model.SceneProject
import com.iamahsana.ossstudio.scene.model.SceneSource
import com.iamahsana.ossstudio.scene.model.SceneSourceType

/**
 * Ordered compositor plan. Static objects are grouped into transparent canvas
 * segments; live-decoded sources occupy their exact z-order between segments.
 */
internal sealed interface SceneRenderNode {
    data class CanvasSegment(
        val index: Int,
        val elements: List<SceneElement>,
        val drawsBackground: Boolean
    ) : SceneRenderNode

    data class LiveSource(
        val element: SceneElement,
        val source: SceneSource
    ) : SceneRenderNode
}

internal object SceneRenderPlan {
    private val liveSourceTypes = setOf(
        SceneSourceType.VIDEO,
        SceneSourceType.CAMERA,
        SceneSourceType.USB_VIDEO
    )

    fun build(
        project: SceneProject,
        scene: Scene,
        layerPosition: SceneCanvasLayerController.LayerPosition,
        shareScreenZIndex: Int?
    ): List<SceneRenderNode> {
        val sources = project.sources.associateBy { it.id }
        val visible = scene.elements.asSequence()
            .filter { it.visible && isSceneElementInLayer(it.zIndex, layerPosition, shareScreenZIndex) }
            .mapNotNull { element ->
                val source = sources[element.sourceId] ?: return@mapNotNull null
                if (!source.enabled || source.type == SceneSourceType.SHARE_SCREEN) return@mapNotNull null
                element to source
            }
            .sortedBy { it.first.zIndex }
            .toList()

        val dynamicSources = visible.filter { (_, source) -> source.type in liveSourceTypes }
        val dynamicElementIds = dynamicSources.mapTo(mutableSetOf()) { it.first.id }
        val segments = MutableList(dynamicSources.size + 1) { mutableListOf<SceneElement>() }

        visible.asSequence()
            .filterNot { (element, _) -> element.id in dynamicElementIds }
            .forEach { (element, _) ->
                val segmentIndex = dynamicSources.count { (dynamicElement, _) ->
                    dynamicElement.zIndex < element.zIndex
                }
                segments[segmentIndex].add(element)
            }

        return buildList {
            segments.forEachIndexed { index, elements ->
                add(
                    SceneRenderNode.CanvasSegment(
                        index = index,
                        elements = elements.toList(),
                        drawsBackground = index == 0
                    )
                )
                if (index < dynamicSources.size) {
                    val (element, source) = dynamicSources[index]
                    add(SceneRenderNode.LiveSource(element, source))
                }
            }
        }
    }

    /**
     * Structural identity for sources that own decoder/capture lifecycles.
     * Geometry and opacity are deliberately excluded: they can be updated
     * without recreating the decoder or camera session.
     */
    fun liveSourceSignature(
        project: SceneProject,
        plan: List<SceneRenderNode>
    ): List<String> {
        val assets = project.assets.associateBy { it.id }
        return plan.filterIsInstance<SceneRenderNode.LiveSource>().map { node ->
            val asset: SceneAsset? = node.source.assetId?.let(assets::get)
            listOf(
                node.element.id,
                node.element.zIndex.toString(),
                node.source.id,
                node.source.type.name,
                node.source.assetId.orEmpty(),
                asset?.relativePath.orEmpty(),
                asset?.sha256.orEmpty(),
                node.source.settings.toSortedMap().entries.joinToString(";") { (key, value) -> "$key=$value" }
            ).joinToString("|")
        }
    }
}
