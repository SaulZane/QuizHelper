package com.quizhelper.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var tvAnswer: TextView
    private lateinit var tvStatus: TextView

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var lastCaptureTime = 0L
    private val intervalMs = 500L

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            Logger.i("MainActivity", "onCreate START")
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_main)

            previewView = findViewById(R.id.previewView)
            tvAnswer = findViewById(R.id.tvAnswer)
            tvStatus = findViewById(R.id.tvStatus)
            Logger.i("MainActivity", "findViewById done")

            if (allPermissionsGranted()) {
                startCamera()
            } else {
                ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
            }
        } catch (e: Throwable) {
            Logger.e("MainActivity", "FATAL in onCreate", e)
        }
    }

    private fun startCamera() {
        try {
            Logger.i("MainActivity", "startCamera BEGIN")
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({
                try {
                    cameraProvider = future.get()
                    bindCameraUseCases()
                } catch (e: Throwable) {
                    Logger.e("MainActivity", "FATAL in ProcessCameraProvider callback", e)
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Throwable) {
            Logger.e("MainActivity", "FATAL in startCamera", e)
        }
    }

    private fun bindCameraUseCases() {
        try {
            Logger.i("MainActivity", "bindCameraUseCases BEGIN")
            val provider = cameraProvider ?: return
            provider.unbindAll()

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.getSurfaceProvider())

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastCaptureTime < intervalMs) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    lastCaptureTime = now
                    Logger.i("MainActivity", "auto capture: ${imageProxy.width}x${imageProxy.height}")

                    runOCR(imageProxy)
                } catch (e: Throwable) {
                    Logger.e("MainActivity", "FATAL in analysis callback", e)
                    imageProxy.close()
                }
            }

            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            Logger.i("MainActivity", "bindToLifecycle SUCCESS")
        } catch (e: Throwable) {
            Logger.e("MainActivity", "FATAL in bindCameraUseCases", e)
            Toast.makeText(this, "Camera init failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun runOCR(imageProxy: ImageProxy) {
        try {
            val mediaImage = imageProxy.image ?: run {
                imageProxy.close()
                return
            }
            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    try {
                        Logger.i("MainActivity", "OCR success, text len=${visionText.text.length}")
                        val result = QuestionBank.findBestMatch(visionText.text)
                        Logger.i("MainActivity", "Match: q=${result.question?.number} score=${"%.0f".format(result.score * 100)}% typeHint=${result.typeHint} matchedType=${result.matchedType}")

                        runOnUiThread {
                            val matched = result.question
                            if (matched != null) {
                                tvAnswer.text = matched.answer
                                tvAnswer.setTextColor(0xFF4CAF50.toInt())
                            } else {
                                tvAnswer.text = "-"
                                tvAnswer.setTextColor(0xFF888888.toInt())
                            }
                        }
                        imageProxy.close()
                    } catch (e: Throwable) {
                        Logger.e("MainActivity", "FATAL in OCR success handler", e)
                        imageProxy.close()
                    }
                }
                .addOnFailureListener { e ->
                    Logger.e("MainActivity", "OCR failed", e)
                    imageProxy.close()
                }
        } catch (e: Throwable) {
            Logger.e("MainActivity", "FATAL in runOCR", e)
            imageProxy.close()
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Logger.i("MainActivity", "onRequestPermissionsResult: requestCode=$requestCode, grants=${grantResults.toList()}")
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        Logger.i("MainActivity", "onDestroy")
        super.onDestroy()
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
    }

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val REQUEST_CODE_PERMISSIONS = 10
    }
}
