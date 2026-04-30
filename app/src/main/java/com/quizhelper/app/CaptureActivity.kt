package com.quizhelper.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

class CaptureActivity : AppCompatActivity() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        window.setDimAmount(0f)
        requestCapture()
    }

    private fun requestCapture() {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CAPTURE && resultCode == Activity.RESULT_OK && data != null) {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, data)
            captureScreen()
        } else {
            finish()
        }
    }

    private fun captureScreen() {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val dpi = metrics.densityDpi

        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "quiz_capture", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )

        handler.postDelayed({
            val image = reader.acquireLatestImage()
            if (image != null) {
                val buffer = image.planes[0].buffer
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()
                runOCR(bitmap)
            } else {
                notifyResult("Capture failed", "No image acquired")
                finish()
            }
            cleanup()
        }, 300)
    }

    private fun runOCR(bitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val result = QuestionBank.findBestMatch(visionText.text)
                notifyResult(result.first, result.second)
                finish()
            }
            .addOnFailureListener { e ->
                notifyResult("OCR Failed", e.message ?: "Unknown error")
                finish()
            }
    }

    private fun notifyResult(title: String, answer: String) {
        FloatingButtonService.broadcastAnswer(this, title, answer)
    }

    private fun cleanup() {
        virtualDisplay?.release()
        mediaProjection?.stop()
    }

    companion object {
        const val REQUEST_CAPTURE = 2002
    }
}
