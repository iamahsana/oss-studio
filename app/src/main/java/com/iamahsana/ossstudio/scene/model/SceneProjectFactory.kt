package com.iamahsana.ossstudio.scene.model

import java.util.UUID

/** Creates a valid, empty starting project with one active scene. */
object SceneProjectFactory {
    fun create(
        name: String,
        nowEpochMillis: Long,
        canvas: CanvasSize = CanvasSize.DEFAULT_PORTRAIT,
        projectId: String = UUID.randomUUID().toString(),
        sceneId: String = UUID.randomUUID().toString()
    ): SceneProject {
        require(name.isNotBlank()) { "Project name must not be blank" }
        require(nowEpochMillis >= 0L) { "Creation timestamp must not be negative" }
        val scene = Scene(
            id = sceneId,
            projectId = projectId,
            name = "Main",
            canvas = canvas
        )
        return SceneProject(
            id = projectId,
            name = name.trim(),
            createdAtEpochMillis = nowEpochMillis,
            updatedAtEpochMillis = nowEpochMillis,
            activeSceneId = scene.id,
            scenes = listOf(scene)
        )
    }
}
