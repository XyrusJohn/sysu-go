package com.sysu.go.activities

import android.content.Intent
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.sysu.go.BuildConfig
import com.sysu.go.R
import com.sysu.go.databinding.ActivityQrScanBinding
import com.sysu.go.network.QRApiService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class QRScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrScanBinding
    private lateinit var cameraExecutor: ExecutorService
    private var hasScanned = false
    private var currentToken: String? = null
    private var camera: Camera? = null
    private var torchEnabled = false
    private var isZoomingFromSlider = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, R.string.camera_required, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityQrScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.btnCancel.setOnClickListener { finish() }

        // Zoom controls
        setupZoomControls()

        // Torch toggle
        binding.btnTorch.setOnClickListener { toggleTorch() }

        cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
    }

    private fun setupZoomControls() {
        // Step zoom buttons
        binding.btnZoomIn.setOnClickListener {
            camera?.let { cam ->
                val current = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                val maxZoom = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 4f
                val step = 0.3f
                val newZoom = (current + step).coerceAtMost(maxZoom)
                cam.cameraControl.setZoomRatio(newZoom)
                syncSliderToZoom(newZoom, maxZoom)
            }
        }

        binding.btnZoomOut.setOnClickListener {
            camera?.let { cam ->
                val current = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                val minZoom = cam.cameraInfo.zoomState.value?.minZoomRatio ?: 1f
                val step = 0.3f
                val newZoom = (current - step).coerceAtLeast(minZoom)
                cam.cameraControl.setZoomRatio(newZoom)
                syncSliderToZoom(newZoom, cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 4f)
            }
        }

        // Slider
        binding.zoomSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || isZoomingFromSlider) return
                isZoomingFromSlider = true
                camera?.let { cam ->
                    val minZoom = cam.cameraInfo.zoomState.value?.minZoomRatio ?: 1f
                    val maxZoom = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 4f
                    val ratio = minZoom + (maxZoom - minZoom) * (progress / 100f)
                    cam.cameraControl.setZoomRatio(ratio)
                }
                isZoomingFromSlider = false
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun syncSliderToZoom(zoomRatio: Float, maxZoom: Float) {
        val minZoom = camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f
        val percent = ((zoomRatio - minZoom) / (maxZoom - minZoom) * 100f).toInt().coerceIn(0, 100)
        binding.zoomSlider.progress = percent
    }

    private fun toggleTorch() {
        camera?.let { cam ->
            torchEnabled = !torchEnabled
            cam.cameraControl.enableTorch(torchEnabled)
            binding.btnTorch.setImageResource(
                if (torchEnabled) R.drawable.ic_torch_on else R.drawable.ic_torch_off
            )
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (!hasScanned) {
                            processImage(imageProxy)
                        } else {
                            imageProxy.close()
                        }
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis
                )

                // Initialize slider to current zoom range
                camera?.cameraInfo?.zoomState?.let { zoomState ->
                    val state = zoomState.value
                    if (state != null) {
                        syncSliderToZoom(state.zoomRatio, state.maxZoomRatio)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun processImage(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.imageInfo.rotationDegrees
            )

            val scanner = BarcodeScanning.getClient()
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        val rawValue = barcode.rawValue ?: continue
                        // Case 1: URL format https://...?token=SYS-XXXXXXXXXXXX
                        if (rawValue.startsWith(BuildConfig.FRONTEND_URL) && rawValue.contains("token=")) {
                            val uri = android.net.Uri.parse(rawValue)
                            val token = uri.getQueryParameter("token")
                            if (!token.isNullOrEmpty() && token.startsWith("SYS-")) {
                                onTokenDetected(token)
                                return@addOnSuccessListener
                            }
                        }
                        // Case 2: Raw token SYS-XXXXXXXXXXXX
                        if (rawValue.startsWith("SYS-")) {
                            onTokenDetected(rawValue)
                            return@addOnSuccessListener
                        }
                    }
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun onTokenDetected(token: String) {
        if (hasScanned) return
        hasScanned = true

        runOnUiThread {
            binding.tvStatus.text = "Token found! Verifying…"
            binding.progressBar.visibility = android.widget.ProgressBar.VISIBLE
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val result = QRApiService.verifyToken(token)
            when (result) {
                is QRApiService.Result.Success -> {
                    currentToken = token
                    withContext(Dispatchers.Main) {
                        binding.tvStatus.text = "Token verified! Loading form…"
                        // Navigate directly to TripRegistrationActivity with the token
                        // Skip UpdateInfoActivity to make it a one-step process
                        val intent = Intent(this@QRScanActivity, TripRegistrationActivity::class.java)
                        intent.putExtra("token", token)
                        startActivity(intent)
                        finish()
                    }
                }
                is QRApiService.Result.Error -> {
                    withContext(Dispatchers.Main) {
                        binding.progressBar.visibility = android.widget.ProgressBar.GONE
                        val msg = when {
                            result.code == 410 -> getString(R.string.token_expired)
                            result.code == 404 -> getString(R.string.token_invalid)
                            else -> getString(R.string.scan_failed)
                        }
                        showErrorAndReset(msg)
                    }
                }
            }
        }
    }

    private fun showErrorAndReset(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Scan Error")
            .setMessage(message)
            .setPositiveButton("Try Again") { _, _ ->
                hasScanned = false
                binding.tvStatus.text = getString(R.string.scan_instruction)
                binding.progressBar.visibility = android.widget.ProgressBar.GONE
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}