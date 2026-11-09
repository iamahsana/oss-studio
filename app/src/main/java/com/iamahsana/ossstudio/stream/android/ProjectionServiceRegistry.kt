package com.iamahsana.ossstudio.stream.android

/** Exposes the currently bound projection service to the app-scoped LIVE runtime factory. */
object ProjectionServiceRegistry {
    @Volatile
    var service: ShareScreenSessionService? = null
        private set

    fun attach(value: ShareScreenSessionService) {
        service = value
    }

    fun detach(value: ShareScreenSessionService) {
        if (service === value) service = null
    }
}
