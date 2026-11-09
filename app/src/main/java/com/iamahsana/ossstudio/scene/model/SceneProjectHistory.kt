package com.iamahsana.ossstudio.scene.model

import java.util.ArrayDeque

/**
 * Bounded in-memory undo/redo history for immutable scene-project snapshots.
 * A non-null group key coalesces a continuous gesture into one undo step.
 */
class SceneProjectHistory(
    initialProject: SceneProject,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) {
    private val undoEntries = ArrayDeque<SceneProject>()
    private val redoEntries = ArrayDeque<SceneProject>()
    private var currentProject = initialProject
    private var activeGroup: String? = null

    init {
        require(maxEntries > 0) { "History capacity must be positive" }
    }

    val canUndo: Boolean
        get() = undoEntries.isNotEmpty()

    val canRedo: Boolean
        get() = redoEntries.isNotEmpty()

    fun record(updatedProject: SceneProject, groupKey: String? = null) {
        if (updatedProject == currentProject) return
        if (groupKey == null || groupKey != activeGroup) {
            undoEntries.addLast(currentProject)
            while (undoEntries.size > maxEntries) undoEntries.removeFirst()
        }
        redoEntries.clear()
        currentProject = updatedProject
        activeGroup = groupKey
    }

    fun endGroup() {
        activeGroup = null
    }

    fun undo(): SceneProject? {
        if (undoEntries.isEmpty()) return null
        redoEntries.addLast(currentProject)
        currentProject = undoEntries.removeLast()
        activeGroup = null
        return currentProject
    }

    fun redo(): SceneProject? {
        if (redoEntries.isEmpty()) return null
        undoEntries.addLast(currentProject)
        while (undoEntries.size > maxEntries) undoEntries.removeFirst()
        currentProject = redoEntries.removeLast()
        activeGroup = null
        return currentProject
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 50
    }
}
