package com.iamahsana.ossstudio.scene.persistence

import android.content.Context

object SceneProjectStoreProvider {
    @Volatile private var instance:SceneProjectDatabase?=null
    fun database(context:Context):SceneProjectDatabase=instance?: synchronized(this) {
        instance?:SceneProjectDatabase(context.applicationContext).also { instance=it }
    }
}
