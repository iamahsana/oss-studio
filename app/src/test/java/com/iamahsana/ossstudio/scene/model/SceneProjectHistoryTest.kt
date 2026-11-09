package com.iamahsana.ossstudio.scene.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneProjectHistoryTest {
    private fun project(name: String, timestamp: Long = 10L) = SceneProject(
        id = "project",
        name = name,
        createdAtEpochMillis = 10L,
        updatedAtEpochMillis = timestamp
    )

    @Test
    fun undoAndRedoRestoreImmutableProjectSnapshots() {
        val initial = project("Initial")
        val changed = initial.copy(name = "Changed", updatedAtEpochMillis = 20L)
        val history = SceneProjectHistory(initial)

        history.record(changed)

        assertEquals(initial, history.undo())
        assertEquals(changed, history.redo())
        assertFalse(history.canRedo)
    }

    @Test
    fun consecutiveChangesInOneGestureBecomeOneUndoStep() {
        val initial = project("Initial")
        val first = initial.copy(name = "First", updatedAtEpochMillis = 20L)
        val second = initial.copy(name = "Second", updatedAtEpochMillis = 30L)
        val history = SceneProjectHistory(initial)

        history.record(first, "drag:element")
        history.record(second, "drag:element")
        history.endGroup()

        assertEquals(initial, history.undo())
        assertNull(history.undo())
    }

    @Test
    fun newEditAfterUndoClearsRedoHistory() {
        val initial = project("Initial")
        val first = initial.copy(name = "First", updatedAtEpochMillis = 20L)
        val second = initial.copy(name = "Second", updatedAtEpochMillis = 30L)
        val history = SceneProjectHistory(initial)

        history.record(first)
        history.undo()
        history.record(second)

        assertFalse(history.canRedo)
        assertEquals(initial, history.undo())
    }

    @Test
    fun historyIsBoundedToConfiguredCapacity() {
        val initial = project("0")
        val history = SceneProjectHistory(initial, maxEntries = 2)
        var current = initial

        (1L..3L).forEach { index ->
            current = current.copy(name = index.toString(), updatedAtEpochMillis = 10L + index)
            history.record(current)
        }

        assertTrue(history.canUndo)
        assertEquals("2", history.undo()?.name)
        assertEquals("1", history.undo()?.name)
        assertNull(history.undo())
    }
}
