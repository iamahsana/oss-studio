package com.iamahsana.ossstudio.scene.model

/**
 * Immutable project mutations used by the editor and persistence layer.
 * Every operation returns a new validated project and advances its update timestamp.
 */
object SceneProjectEditor {
    fun addScene(project: SceneProject, scene: Scene, nowEpochMillis: Long): SceneProject {
        require(scene.projectId == project.id) { "Scene belongs to another project" }
        require(project.scenes.none { it.id == scene.id }) { "Scene ID already exists" }
        return project.copy(
            scenes = project.scenes + scene,
            activeSceneId = project.activeSceneId ?: scene.id,
            updatedAtEpochMillis = nextTimestamp(project, nowEpochMillis)
        )
    }

    fun removeScene(project: SceneProject, sceneId: String, nowEpochMillis: Long): SceneProject {
        require(project.scenes.any { it.id == sceneId }) { "Scene does not exist" }
        val remaining = project.scenes.filterNot { it.id == sceneId }
        val nextActive = if (project.activeSceneId == sceneId) remaining.firstOrNull()?.id else project.activeSceneId
        return project.copy(
            scenes = remaining,
            activeSceneId = nextActive,
            updatedAtEpochMillis = nextTimestamp(project, nowEpochMillis)
        )
    }

    fun addSource(project: SceneProject, source: SceneSource, nowEpochMillis: Long): SceneProject {
        require(project.sources.none { it.id == source.id }) { "Source ID already exists" }
        return project.copy(
            sources = project.sources + source,
            updatedAtEpochMillis = nextTimestamp(project, nowEpochMillis)
        )
    }

    fun addAsset(project: SceneProject, asset: SceneAsset, nowEpochMillis: Long): SceneProject {
        require(project.assets.none { it.id == asset.id }) { "Asset ID already exists" }
        require(project.assets.none { it.relativePath == asset.relativePath }) { "Asset path already exists" }
        return project.copy(
            assets = project.assets + asset,
            updatedAtEpochMillis = nextTimestamp(project, nowEpochMillis)
        )
    }

    fun updateSceneCanvas(
        project: SceneProject,
        sceneId: String,
        canvas: CanvasSize,
        nowEpochMillis: Long
    ): SceneProject {
        val sceneIndex = project.scenes.indexOfFirst { it.id == sceneId }
        require(sceneIndex >= 0) { "Scene does not exist" }
        return project.copy(
            scenes = project.scenes.toMutableList().also {
                it[sceneIndex] = it[sceneIndex].copy(canvas = canvas)
            },
            updatedAtEpochMillis = nextTimestamp(project, nowEpochMillis)
        )
    }

    fun updateSource(project: SceneProject, source: SceneSource, nowEpochMillis: Long): SceneProject {
        val sourceIndex = project.sources.indexOfFirst { it.id == source.id }
        require(sourceIndex >= 0) { "Source does not exist" }
        return project.copy(
            sources = project.sources.toMutableList().also { it[sourceIndex] = source },
            updatedAtEpochMillis = nextTimestamp(project, nowEpochMillis)
        )
    }

    fun removeElement(
        project: SceneProject,
        sceneId: String,
        elementId: String,
        nowEpochMillis: Long
    ): SceneProject {
        val sceneIndex = project.scenes.indexOfFirst { it.id == sceneId }
        require(sceneIndex >= 0) { "Scene does not exist" }
        val scene = project.scenes[sceneIndex]
        require(scene.elements.any { it.id == elementId }) { "Element does not exist" }
        val remaining = scene.elements.filterNot { it.id == elementId }
            .sortedBy { it.zIndex }
            .mapIndexed { index, element -> element.copy(zIndex = index) }
        return project.copy(
            scenes = project.scenes.toMutableList().also {
                it[sceneIndex] = scene.copy(elements = remaining)
            },
            updatedAtEpochMillis = nextTimestamp(project, nowEpochMillis)
        )
    }

    fun setElementLocked(
        project: SceneProject,
        sceneId: String,
        elementId: String,
        locked: Boolean,
        nowEpochMillis: Long
    ): SceneProject {
        val sceneIndex = project.scenes.indexOfFirst { it.id == sceneId }
        require(sceneIndex >= 0) { "Scene does not exist" }
        val scene = project.scenes[sceneIndex]
        require(scene.elements.any { it.id == elementId }) { "Element does not exist" }
        return project.copy(
            scenes = project.scenes.toMutableList().also { list ->
                list[sceneIndex] = scene.copy(
                    elements = scene.elements.map { if (it.id == elementId) it.copy(locked = locked) else it }
                )
            },
            updatedAtEpochMillis = nextTimestamp(project, nowEpochMillis)
        )
    }

    fun setElementVisibility(
        project: SceneProject,
        sceneId: String,
        elementId: String,
        visible: Boolean,
        nowEpochMillis: Long
    ): SceneProject {
        val sceneIndex = project.scenes.indexOfFirst { it.id == sceneId }
        require(sceneIndex >= 0) { "Scene does not exist" }
        val scene = project.scenes[sceneIndex]
        require(scene.elements.any { it.id == elementId }) { "Element does not exist" }
        return project.copy(
            scenes = project.scenes.toMutableList().also { list ->
                list[sceneIndex] = scene.copy(
                    elements = scene.elements.map { if (it.id == elementId) it.copy(visible = visible) else it }
                )
            },
            updatedAtEpochMillis = nextTimestamp(project, nowEpochMillis)
        )
    }

    fun addElement(
        project: SceneProject,
        sceneId: String,
        sourceId: String,
        nowEpochMillis: Long
    ): SceneProject {
        require(project.sources.any { it.id == sourceId }) { "Source does not exist" }
        val sceneIndex = project.scenes.indexOfFirst { it.id == sceneId }
        require(sceneIndex >= 0) { "Scene does not exist" }
        val scene = project.scenes[sceneIndex]
        val nextZ = (scene.elements.maxOfOrNull { it.zIndex } ?: -1) + 1
        val updatedScene = scene.copy(
            elements = scene.elements + SceneElement(sourceId = sourceId, zIndex = nextZ)
        )
        return project.copy(
            scenes = project.scenes.toMutableList().also { it[sceneIndex] = updatedScene },
            updatedAtEpochMillis = nextTimestamp(project, nowEpochMillis)
        )
    }

    fun moveElement(
        project: SceneProject,
        sceneId: String,
        elementId: String,
        targetIndex: Int,
        nowEpochMillis: Long
    ): SceneProject {
        val sceneIndex = project.scenes.indexOfFirst { it.id == sceneId }
        require(sceneIndex >= 0) { "Scene does not exist" }
        val scene = project.scenes[sceneIndex]
        val ordered = scene.elements.sortedBy { it.zIndex }.toMutableList()
        val fromIndex = ordered.indexOfFirst { it.id == elementId }
        require(fromIndex >= 0) { "Element does not exist" }
        require(targetIndex in ordered.indices) { "Target layer index is out of bounds" }
        val moved = ordered.removeAt(fromIndex)
        ordered.add(targetIndex, moved)
        val normalized = ordered.mapIndexed { index, element -> element.copy(zIndex = index) }
        return project.copy(
            scenes = project.scenes.toMutableList().also {
                it[sceneIndex] = scene.copy(elements = normalized)
            },
            updatedAtEpochMillis = nextTimestamp(project, nowEpochMillis)
        )
    }

    fun updateTransform(
        project: SceneProject,
        sceneId: String,
        elementId: String,
        transform: ElementTransform,
        nowEpochMillis: Long
    ): SceneProject {
        val sceneIndex = project.scenes.indexOfFirst { it.id == sceneId }
        require(sceneIndex >= 0) { "Scene does not exist" }
        val scene = project.scenes[sceneIndex]
        require(scene.elements.any { it.id == elementId }) { "Element does not exist" }
        val updatedScene = scene.copy(
            elements = scene.elements.map {
                if (it.id == elementId) it.copy(transform = transform) else it
            }
        )
        return project.copy(
            scenes = project.scenes.toMutableList().also { it[sceneIndex] = updatedScene },
            updatedAtEpochMillis = nextTimestamp(project, nowEpochMillis)
        )
    }

    fun removeSource(project: SceneProject, sourceId: String, nowEpochMillis: Long): SceneProject {
        require(project.sources.any { it.id == sourceId }) { "Source does not exist" }
        val remainingSources = project.sources.filterNot { it.id == sourceId }
        val removedAssetIds = project.sources
            .filter { it.id == sourceId }
            .mapNotNull { it.assetId }
            .toSet()
        val stillUsedAssetIds = remainingSources.mapNotNull { it.assetId }.toSet()
        val remainingAssets = project.assets.filterNot {
            it.id in removedAssetIds && it.id !in stillUsedAssetIds
        }
        val updatedScenes = project.scenes.map { scene ->
            scene.copy(elements = scene.elements.filterNot { it.sourceId == sourceId })
        }
        return project.copy(
            sources = remainingSources,
            assets = remainingAssets,
            scenes = updatedScenes,
            updatedAtEpochMillis = nextTimestamp(project, nowEpochMillis)
        )
    }

    fun setActiveScene(project: SceneProject, sceneId: String, nowEpochMillis: Long): SceneProject {
        require(project.scenes.any { it.id == sceneId }) { "Scene does not exist" }
        return project.copy(
            activeSceneId = sceneId,
            updatedAtEpochMillis = nextTimestamp(project, nowEpochMillis)
        )
    }

    private fun nextTimestamp(project: SceneProject, requested: Long): Long {
        require(requested >= 0L) { "Timestamp must not be negative" }
        return maxOf(requested, project.updatedAtEpochMillis)
    }
}
