package com.iamahsana.ossstudio.audio

/**
 * Reusable block processor joining source queues, timestamp alignment and the
 * independent LIVE/MONITOR mixer buses.
 *
 * One dedicated mixer worker owns [processBlock]. Capture callbacks only call
 * their assigned SPSC queue's offer(). The returned output arrays are owned by
 * this engine and remain valid only until the next processBlock call.
 */
class PcmMixerEngine(
    sourceQueues: Array<SpscPcmFrameQueue>,
    initialConfigs: Array<ChannelMixConfig>,
    val framesPerBlock: Int = 480,
    sampleRateHz: Int = 48_000,
    maxFrameBytes: Int = 8192
) {
    init {
        require(sourceQueues.isNotEmpty()) { "At least one source queue is required" }
        require(sourceQueues.size == initialConfigs.size) {
            "Each source queue must have one initial channel configuration"
        }
        require(framesPerBlock > 0) { "Block size must be positive" }
        require(framesPerBlock <= Int.MAX_VALUE / BYTES_PER_FRAME) {
            "Block size is too large"
        }
    }

    private val aligner = PcmTimestampAligner(sourceQueues, sampleRateHz, maxFrameBytes)
    private val mixer = PcmMixerCore()
    private val channelInputs: Array<ByteArray?> =
        Array(sourceQueues.size) { ByteArray(framesPerBlock * BYTES_PER_FRAME) }
    private val liveBuffer = ByteArray(framesPerBlock * BYTES_PER_FRAME)
    private val monitorBuffer = ByteArray(framesPerBlock * BYTES_PER_FRAME)
    private val channelPeaks = FloatArray(sourceQueues.size)
    private val channelRmsValues = FloatArray(sourceQueues.size)
    private val livePeakValue = FloatArray(1)
    private val liveRmsValue = FloatArray(1)
    private val monitorPeakValue = FloatArray(1)
    private val monitorRmsValue = FloatArray(1)

    @Volatile
    private var configs = initialConfigs.copyOf()

    @Volatile
    var liveMasterGain: Float = 1f
        set(value) {
            require(value in 0f..2f) { "LIVE master gain must be between 0% and 200%" }
            field = value
        }

    @Volatile
    var monitorMasterGain: Float = 1f
        set(value) {
            require(value in 0f..2f) { "MONITOR master gain must be between 0% and 200%" }
            field = value
        }

    val liveOutput: ByteArray
        get() = liveBuffer

    val monitorOutput: ByteArray
        get() = monitorBuffer

    val channelCount: Int
        get() = channelInputs.size

    val livePeak: Float
        get() = livePeakValue[0]

    val liveRms: Float
        get() = liveRmsValue[0]

    val monitorPeak: Float
        get() = monitorPeakValue[0]

    val monitorRms: Float
        get() = monitorRmsValue[0]

    fun channelPeak(index: Int): Float = channelPeaks[index]
    fun channelRms(index: Int): Float = channelRmsValues[index]
    fun queuedFrames(index: Int): Int = alignerQueueDepth(index)
    fun droppedFrames(index: Int): Long = alignerQueueDropped(index)
    fun lateFrames(index: Int): Long = aligner.lateFrames(index)
    fun missingBlocks(index: Int): Long = aligner.missingBlocks(index)

    /**
     * Control changes are copied as one immutable configuration snapshot.
     * Call from UI/control code; never from an audio capture callback.
     */
    @Synchronized
    fun updateChannelConfig(index: Int, config: ChannelMixConfig) {
        val next = configs.copyOf()
        next[index] = config
        configs = next
    }

    /**
     * Process exactly one fixed-size output block. This method performs no
     * per-block allocations. It must be called serially by one mixer worker.
     */
    fun processBlock(blockStartMicros: Long) {
        aligner.fillBlock(blockStartMicros, framesPerBlock, channelInputs)
        mixer.mixInto(
            inputs = channelInputs,
            configs = configs,
            frameCount = framesPerBlock,
            liveOutput = liveBuffer,
            monitorOutput = monitorBuffer,
            liveMasterGain = liveMasterGain,
            monitorMasterGain = monitorMasterGain,
            channelPeak = channelPeaks,
            channelRms = channelRmsValues,
            livePeak = livePeakValue,
            liveRms = liveRmsValue,
            monitorPeak = monitorPeakValue,
            monitorRms = monitorRmsValue
        )
    }

    private fun alignerQueueDepth(index: Int): Int = sourceQueue(index).queuedFrames
    private fun alignerQueueDropped(index: Int): Long = sourceQueue(index).droppedFrames
    private fun sourceQueue(index: Int): SpscPcmFrameQueue = queues[index]

    private val queues = sourceQueues

    private companion object {
        const val BYTES_PER_FRAME = 4
    }
}
