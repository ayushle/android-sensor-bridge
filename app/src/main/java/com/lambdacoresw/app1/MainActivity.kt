package com.lambdacoresw.app1

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SensorBridge"
    }

    private lateinit var sensorServer: SensorServer
    private lateinit var cameraExecutor: ExecutorService

    // ---------------------------------------------------------------------------
    // Activity lifecycle
    // ---------------------------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Start the WebSocket / IMU server immediately (no camera permission needed)
        sensorServer = SensorServer(this)
        sensorServer.start()
        Log.i(TAG, "SensorServer started on port 8080")

        // Request camera permission using the modern ActivityResult API
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorServer.stop()
        cameraExecutor.shutdown()
        Log.i(TAG, "SensorServer stopped")
    }

    // ---------------------------------------------------------------------------
    // Permission request
    // ---------------------------------------------------------------------------
    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Camera permission denied — camera stream unavailable",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    // ---------------------------------------------------------------------------
    // CameraX setup
    // ---------------------------------------------------------------------------
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor, FrameAnalyzer { jpegBytes ->
                sensorServer.pushCameraFrame(jpegBytes)
            })

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    imageAnalysis
                )
                Log.i(TAG, "CameraX bound — streaming 640×480 JPEG frames")
            } catch (e: Exception) {
                Log.e(TAG, "CameraX bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------------------------------------------------------------------------
    // Image analyser — converts YUV_420_888 → JPEG at 50% quality
    // ---------------------------------------------------------------------------
    private inner class FrameAnalyzer(
        private val onFrame: (ByteArray) -> Unit
    ) : ImageAnalysis.Analyzer {

        @ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            try {
                val jpegBytes = imageProxy.toJpeg(quality = 50)
                onFrame(jpegBytes)
            } catch (e: Exception) {
                Log.e(TAG, "Frame conversion failed", e)
            } finally {
                imageProxy.close()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Extension: YUV_420_888 ImageProxy → JPEG ByteArray
// ---------------------------------------------------------------------------
@ExperimentalGetImage
private fun ImageProxy.toJpeg(quality: Int): ByteArray {
    val image = this.image
        ?: return ByteArray(0)

    // NV21 byte buffer layout required by YuvImage
    val yBuffer  = image.planes[0].buffer
    val uBuffer  = image.planes[1].buffer
    val vBuffer  = image.planes[2].buffer

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    // Build NV21: Y plane followed by interleaved V/U
    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, out)
    return out.toByteArray()
}
