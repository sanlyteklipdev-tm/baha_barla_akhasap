package com.sanly.bahabarla

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.sanly.bahabarla.databinding.ActivityScannerBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScannerBinding
    private lateinit var analysisExecutor: ExecutorService

    private val scanner = BarcodeScanning.getClient()
    private val delivered = AtomicBoolean(false)
    private val hintHandler = Handler(Looper.getMainLooper())
    private val showHint = Runnable { binding.tvHint.visibility = View.VISIBLE }

    private var camera: Camera? = null
    private var torchOn = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, R.string.err_camera_permission, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        analysisExecutor = Executors.newSingleThreadExecutor()

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnFlash.setOnClickListener { toggleTorch() }

        // The prompt appears only once scanning has been running for a while.
        hintHandler.postDelayed(showHint, HINT_DELAY_MS)

        if (hasCameraPermission()) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                bindUseCases(future.get())
            } catch (e: Exception) {
                Log.e(TAG, "Camera start failed", e)
                Toast.makeText(this, R.string.err_camera, Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases(provider: ProcessCameraProvider) {
        val preview = Preview.Builder().build().apply {
            surfaceProvider = binding.previewView.surfaceProvider
        }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply { setAnalyzer(analysisExecutor, ::analyze) }

        provider.unbindAll()
        camera = provider.bindToLifecycle(
            this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
        )
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val mediaImage = proxy.image
        if (mediaImage == null || delivered.get()) {
            proxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes -> barcodes.firstUsableValue()?.let(::deliver) }
            .addOnFailureListener { e -> Log.w(TAG, "Barcode scan failed", e) }
            .addOnCompleteListener { proxy.close() }
    }

    private fun List<Barcode>.firstUsableValue(): String? =
        firstNotNullOfOrNull { it.rawValue?.takeIf(String::isNotBlank) }

    private fun deliver(barcode: String) {
        // ML Kit can report the same frame twice before the activity finishes.
        if (!delivered.compareAndSet(false, true)) return
        setResult(RESULT_OK, Intent().putExtra(EXTRA_BARCODE, barcode))
        finish()
    }

    private fun toggleTorch() {
        val control = camera ?: return
        if (camera?.cameraInfo?.hasFlashUnit() != true) {
            Toast.makeText(this, R.string.err_no_flash, Toast.LENGTH_SHORT).show()
            return
        }
        torchOn = !torchOn
        control.cameraControl.enableTorch(torchOn)
    }

    override fun onDestroy() {
        super.onDestroy()
        hintHandler.removeCallbacks(showHint)
        analysisExecutor.shutdown()
        scanner.close()
    }

    companion object {
        const val EXTRA_BARCODE = "com.sanly.bahabarla.EXTRA_BARCODE"
        private const val TAG = "ScannerActivity"
        private const val HINT_DELAY_MS = 3_000L
    }
}
