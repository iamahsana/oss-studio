package com.iamahsana.ossstudio.stream.android

import com.iamahsana.ossstudio.scene.model.SceneProjectFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveSceneProjectRegistryTest {
    @Test fun publishesProjectUpdatesAndStopsAfterSubscriptionClose() {
        val original = ActiveSceneProjectRegistry.project
        val received = mutableListOf<String?>()
        val subscription = ActiveSceneProjectRegistry.subscribe { received.add(it?.name) }
        try {
            val project = SceneProjectFactory.create("Live scene", 1L)
            ActiveSceneProjectRegistry.publish(project)
            assertEquals("Live scene", received.last())
            subscription.close()
            ActiveSceneProjectRegistry.publish(project.copy(name = "Updated scene"))
            assertEquals("Live scene", received.last())
            assertEquals("Updated scene", ActiveSceneProjectRegistry.project?.name)
        } finally {
            subscription.close()
            ActiveSceneProjectRegistry.publish(original)
        }
    }
}
