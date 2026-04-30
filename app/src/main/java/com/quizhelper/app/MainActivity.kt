package com.quizhelper.app

import android.app.Activity
import android.content.*
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

class MainActivity : AppCompatActivity() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val handler = Handler(Looper.getMainLooper())
    private var hasPermission = false
    private var isCapturing = false
    private var permissionRequested = false
    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Logger.i("MainActivity", "Received capture request broadcast")
            if (!isCapturing) {
                performCapture()
            } else {
                Logger.i("MainActivity", "Already capturing, ignoring request")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.init()
        Logger.i("MainActivity", "onCreate called, savedInstanceState=${savedInstanceState != null}")
        QuestionBank.load(this)

        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        Logger.i("MainActivity", "MediaProjectionManager obtained")

        // Register capture request receiver
        registerReceiver(captureReceiver,
            IntentFilter(ACTION_REQUEST_CAPTURE),
            Context.RECEIVER_NOT_EXPORTED)
        Logger.i("MainActivity", "Capture receiver registered")

        // Step 1: Request overlay permission
        if (!Settings.canDrawOverlays(this)) {
            Logger.i("MainActivity", "Overlay permission not granted, requesting...")
            setContentView(R.layout.activity_main)
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQUEST_OVERLAY)
        } else {
            Logger.i("MainActivity", "Overlay permission already granted")
            startServiceAndGoBack()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Logger.i("MainActivity", "onNewIntent called")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Logger.i("MainActivity", "onActivityResult requestCode=$requestCode resultCode=$resultCode")

        when (requestCode) {
            REQUEST_OVERLAY -> {
                permissionRequested = false
                if (Settings.canDrawOverlays(this)) {
                    Logger.i("MainActivity", "Overlay permission granted")
                    startServiceAndGoBack()
                } else {
                    Logger.e("MainActivity", "Overlay permission denied")
                    finish()
                }
            }
            REQUEST_MEDIA_PROJECTION -> {
                permissionRequested = false
                if (resultCode == Activity.RESULT_OK && data != null) {
                    Logger.i("MainActivity", "MediaProjection permission granted, creating projection")
                    val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    try {
                        mediaProjection = mgr.getMediaProjection(resultCode, data)
                        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                            override fun onStop() {
                                Logger.i("MainActivity", "MediaProjection.onStop called - token invalidated")
                                mediaProjection = null
                            }
                        }, handler)
                        hasPermission = true
                        Logger.i("MainActivity", "MediaProjection created and stored, hasPermission=true")
                        // Now perform the capture that was requested
                        performCapture()
                    } catch (e: Exception) {
                        Logger.e("MainActivity", "Failed to create MediaProjection", e)
                        sendResultToService("Error", "Failed to create projection: ${e.message}")
                    }
                } else {
                    Logger.e("MainActivity", "MediaProjection permission denied or data is null (resultCode=$resultCode)")
                    sendResultToService("Permission denied", "Screen capture permission is required")
                }
            }
        }
    }

    private fun startServiceAndGoBack() {
        Logger.i("MainActivity", "Starting FloatingButtonService and moving to background")
        val intent = Intent(this, FloatingButtonService::class.java)
        ContextCompat.startForegroundService(this, intent)
        // Move to background instead of finishing - keeps MediaProjection alive
        setContentView(R.layout.activity_main)
        Logger.i("MainActivity", "Service started, moving MainActivity to background")
        moveTaskToBack(true)
    }

    private fun performCapture() {
        Logger.i("MainActivity", "performCapture called, hasPermission=$hasPermission, isCapturing=$isCapturing")
        if (isCapturing) {
            Logger.i("MainActivity", "Already capturing, skipping")
            return
        }

        if (!hasPermission || mediaProjection == null) {
            Logger.i("MainActivity", "No MediaProjection permission yet, requesting...")
            if (!permissionRequested) {
                permissionRequested = true
                try {
                    val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
                    Logger.i("MainActivity", "MediaProjection intent started")
                } catch (e: Exception) {
                    Logger.e("MainActivity", "Failed to start MediaProjection intent", e)
                    permissionRequested = false
                    sendResultToService("Error", "Failed to request projection: ${e.message}")
                }
            } else {
                Logger.i("MainActivity", "Permission already being requested, waiting...")
            }
            return
        }

        isCapturing = true
        Logger.i("MainActivity", "Starting screen capture...")

        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val dpi = metrics.densityDpi
        Logger.i("MainActivity", "Screen dimensions: ${w}x${h}, dpi=$dpi")

        try {
            val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            Logger.i("MainActivity", "ImageReader created")

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "quiz_capture",
                w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, handler
            )
            Logger.i("MainActivity", "VirtualDisplay created: ${virtualDisplay != null}")

            handler.postDelayed({
                try {
                    Logger.i("MainActivity", "Acquiring image from ImageReader...")
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        Logger.i("MainActivity", "Image acquired, size=${image.width}x${image.height}")
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * w
                        
                        val bitmap = Bitmap.createBitmap(
                            w + rowPadding / pixelStride, h,
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap.copyPixelsFromBuffer(buffer)
                        val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, w, h)
                        bitmap.recycle()
                        image.close()
                        Logger.i("MainActivity", "Bitmap created: ${croppedBitmap.width}x${croppedBitmap.height}")
                        runOCR(croppedBitmap)
                    } else {
                        Logger.e("MainActivity", "No image acquired from ImageReader")
                        sendResultToService("Capture failed", "No image acquired. Try again.")
                        isCapturing = false
                    }
                } catch (e: Exception) {
                    Logger.e("MainActivity", "Error processing captured image", e)
                    sendResultToService("Capture error", e.message ?: "Unknown error")
                    isCapturing = false
                }
                cleanupDisplay()
            }, 500)
        } catch (e: Exception) {
            Logger.e("MainActivity", "Error setting up capture", e)
            sendResultToService("Capture error", e.message ?: "Unknown error")
            isCapturing = false
        }
    }

    private fun runOCR(bitmap: Bitmap) {
        Logger.i("MainActivity", "Starting OCR...")
        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(
                ChineseTextRecognizerOptions.Builder().build()
            )
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    Logger.i("MainActivity", "OCR succeeded, text length: ${visionText.text.length}")
                    Logger.i("MainActivity", "OCR sample: ${visionText.text.take(200)}")
                    try {
                        val result = QuestionBank.findBestMatch(visionText.text)
                        Logger.i("MainActivity", "Match result: title=${result.first}, answer=${result.second}")
                        sendResultToService(result.first, result.second)
                    } catch (e: Exception) {
                        Logger.e("MainActivity", "Error matching question", e)
                        sendResultToService("Match error", e.message ?: "Unknown error")
                    }
                    isCapturing = false
                }
                .addOnFailureListener { e ->
                    Logger.e("MainActivity", "OCR failed", e)
                    sendResultToService("OCR Failed", e.message ?: "Unknown error")
                    isCapturing = false
                }
        } catch (e: Exception) {
            Logger.e("MainActivity", "OCR setup failed", e)
            sendResultToService("OCR Error", e.message ?: "Unknown error")
            isCapturing = false
        }
    }

    private fun sendResultToService(title: String, answer: String) {
        Logger.i("MainActivity", "Sending result to service: $title | $answer")
        val intent = Intent(FloatingButtonService.ACTION_SHOW_ANSWER).apply {
            setPackage(packageName)
            putExtra("title", title)
            putExtra("answer", answer)
        }
        sendBroadcast(intent)
    }

    private fun cleanupDisplay() {
        Logger.i("MainActivity", "Cleaning up VirtualDisplay")
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Logger.e("MainActivity", "Error releasing VirtualDisplay", e)
        }
        virtualDisplay = null
    }

    override fun onDestroy() {
        Logger.i("MainActivity", "onDestroy called")
        unregisterReceiver(captureReceiver)
        handler.removeCallbacksAndMessages(null)
        cleanupDisplay()
        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Logger.e("MainActivity", "Error stopping MediaProjection", e)
        }
        mediaProjection = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        Logger.i("MainActivity", "onResume")
        if (hasPermission && isCapturing) {
            // Re-acquire capture after returning from permission dialog
            Logger.i("MainActivity", "Resuming after permission, re-triggering capture")
            handler.postDelayed({ performCapture() }, 500)
        }
    }

    companion object {
        const val REQUEST_OVERLAY = 1001
        const val REQUEST_MEDIA_PROJECTION = 1002
        const val ACTION_REQUEST_CAPTURE = "com.quizhelper.app.REQUEST_CAPTURE"
    }
}
