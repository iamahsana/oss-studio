package com.iamahsana.ossstudio.audio

import android.os.SystemClock

/** RootEncoder 2.8.1 timestamps use elapsedRealtimeNanos()/1000. */
object AndroidPcmMixerClock : PcmMixerClock {
    override fun nowMicros(): Long = SystemClock.elapsedRealtimeNanos() / 1_000L
}
