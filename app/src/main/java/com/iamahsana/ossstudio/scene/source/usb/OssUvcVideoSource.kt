package com.iamahsana.ossstudio.scene.source.usb

import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.view.Surface
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.pedro.encoder.input.sources.OrientationConfig
import com.pedro.encoder.input.sources.OrientationForced
import com.pedro.encoder.input.sources.video.VideoSource

/**
 * UVC adapter with explicit camera-open, permission-denied, and detach callbacks.
 *
 * Its lifecycle follows the RootEncoder 2.8.1 CameraUvcSource contract, while
 * exposing the UVCAndroid state events needed to gate LIVE on a real camera.
 * UVCAndroid is Apache-2.0; see third_party/UVCAndroid/.
 */
internal class OssUvcVideoSource(
    private val onCameraReady: () -> Unit,
    private val onCameraFailure: (String) -> Unit
) : VideoSource() {
    private var cameraHelper: ICameraHelper? = null
    private var surface: Surface? = null
    private var running = false
    private var stopping = false
    private var selectedDeviceName: String? = null

    override fun create(width: Int, height: Int, fps: Int, rotation: Int): Boolean = true

    override fun start(surfaceTexture: SurfaceTexture) {
        this.surfaceTexture = surfaceTexture
        if (isRunning()) return

        stopping = false
        surface = Surface(surfaceTexture)
        val helper = CameraHelper()
        cameraHelper = helper
        helper.setStateCallback(object : ICameraHelper.StateCallback {
            override fun onAttach(device: UsbDevice) {
                selectDevice(device)
            }

            override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
                if (stopping) return
                runCatching { helper.openCamera() }
                    .onFailure { reportFailure(it.message ?: "USB camera could not be opened") }
            }

            override fun onCameraOpen(device: UsbDevice) {
                if (stopping) return
                runCatching {
                    helper.startPreview()
                    val output = checkNotNull(surface) { "USB camera output surface is unavailable" }
                    helper.addSurface(output, false)
                    if (!stopping) onCameraReady()
                }.onFailure {
                    reportFailure(it.message ?: "USB camera preview could not be started")
                }
            }

            override fun onCameraClose(device: UsbDevice) {
                reportFailure("USB camera closed unexpectedly")
            }

            override fun onDeviceClose(device: UsbDevice) {
                reportFailure("USB camera device closed unexpectedly")
            }

            override fun onDetach(device: UsbDevice) {
                reportFailure("USB camera was disconnected")
            }

            override fun onCancel(device: UsbDevice) {
                reportFailure("USB camera permission was denied")
            }

            override fun onError(device: UsbDevice, error: CameraException) {
                reportFailure(error.message ?: "USB camera reported an error")
            }
        })
        helper.getDeviceList().orEmpty().firstOrNull()?.let(::selectDevice)
        running = true
    }

    private fun selectDevice(device: UsbDevice) {
        if (stopping || selectedDeviceName == device.deviceName) return
        selectedDeviceName = device.deviceName
        runCatching { cameraHelper?.selectDevice(device) }
            .onFailure { reportFailure(it.message ?: "USB camera selection failed") }
    }

    private fun reportFailure(message: String) {
        if (!stopping) onCameraFailure(message)
    }

    override fun stop() {
        if (!running && cameraHelper == null && surface == null) return
        stopping = true
        val helper = cameraHelper
        val output = surface
        runCatching { helper?.setStateCallback(null) }
        if (output != null) runCatching { helper?.removeSurface(output) }
        runCatching { helper?.release() }
        output?.release()
        surface = null
        cameraHelper = null
        selectedDeviceName = null
        running = false
    }

    override fun release() {
        stop()
    }

    override fun isRunning(): Boolean = running

    override fun getOrientationConfig() =
        OrientationConfig(forced = OrientationForced.LANDSCAPE)
}
