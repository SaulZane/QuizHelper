package com.quizhelper.app

import android.animation.ValueAnimator
import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

class FloatingButtonService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: FrameLayout? = null
    private var answerOverlay: LinearLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var answerParams: WindowManager.LayoutParams? = null
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private val handler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var isCapturing = false

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Logger.i("Service", "Received permission granted broadcast")
            if (MediaProjectionHolder.hasPermission) {
                createMediaProjection()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Logger.i("Service", "onCreate")
        
        createNotificationChannel()
        startForeground(1, NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Quiz Helper")
            .setContentText("悬浮按钮已启动")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build())

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createFloatingButton()
        
        registerReceiver(permissionReceiver, IntentFilter(ACTION_PERMISSION_GRANTED), RECEIVER_NOT_EXPORTED)
        
        if (MediaProjectionHolder.hasPermission) {
            Logger.i("Service", "Permission already exists, creating projection")
            createMediaProjection()
        }
    }

    private fun createMediaProjection() {
        if (mediaProjection != null) {
            Logger.i("Service", "MediaProjection already exists")
            return
        }
        
        try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(MediaProjectionHolder.resultCode, MediaProjectionHolder.data)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Logger.i("Service", "MediaProjection stopped")
                    mediaProjection = null
                    MediaProjectionHolder.clear()
                }
            }, handler)
            Logger.i("Service", "MediaProjection created successfully")
        } catch (e: Exception) {
            Logger.e("Service", "Failed to create MediaProjection", e)
            MediaProjectionHolder.clear()
        }
    }

    private fun createFloatingButton() {
        floatingView = FrameLayout(this).apply {
            val btnView = ImageView(context).apply {
                setImageResource(android.R.drawable.ic_menu_camera)
                setColorFilter(android.graphics.Color.WHITE)
                setPadding(20, 20, 20, 20)
            }
            addView(btnView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            setBackgroundColor(android.graphics.Color.argb(200, 76, 175, 80))
        }

        overlayParams = WindowManager.LayoutParams(
            120, 120,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 300
        }

        floatingView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = overlayParams!!.x
                    initialY = overlayParams!!.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) isDragging = true
                    overlayParams!!.x = initialX + dx
                    overlayParams!!.y = initialY + dy
                    windowManager?.updateViewLayout(floatingView, overlayParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) onFloatingButtonClick()
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(floatingView, overlayParams)
    }

    private fun onFloatingButtonClick() {
        Logger.i("Service", "Button clicked, isCapturing=$isCapturing, hasProjection=${mediaProjection != null}")
        
        if (isCapturing) {
            Logger.i("Service", "Already capturing, ignoring")
            return
        }
        
        if (mediaProjection != null) {
            captureScreen()
        } else if (MediaProjectionHolder.hasPermission) {
            Logger.i("Service", "Creating projection from holder")
            createMediaProjection()
            if (mediaProjection != null) {
                captureScreen()
            } else {
                Logger.e("Service", "Failed to create projection from holder, requesting permission")
                requestPermission()
            }
        } else {
            Logger.i("Service", "No permission, requesting")
            requestPermission()
        }
    }

    private fun requestPermission() {
        val intent = Intent(this, CaptureActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun captureScreen() {
        if (isCapturing || mediaProjection == null) {
            Logger.e("Service", "Cannot capture: isCapturing=$isCapturing, hasProjection=${mediaProjection != null}")
            return
        }
        
        isCapturing = true
        Logger.i("Service", "Starting capture")

        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val dpi = metrics.densityDpi

        try {
            val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "quiz_capture", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, handler
            )

            handler.postDelayed({
                try {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        val buffer = image.planes[0].buffer
                        val pixelStride = image.planes[0].pixelStride
                        val rowStride = image.planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * w

                        val bitmap = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                        bitmap.copyPixelsFromBuffer(buffer)
                        val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, w, h)
                        bitmap.recycle()
                        image.close()
                        Logger.i("Service", "Image captured: ${croppedBitmap.width}x${croppedBitmap.height}")
                        runOCR(croppedBitmap)
                    } else {
                        Logger.e("Service", "No image from reader")
                        showAnswer("截图失败", "未获取到图像，请重试")
                        isCapturing = false
                    }
                } catch (e: Exception) {
                    Logger.e("Service", "Capture error", e)
                    showAnswer("截图错误", e.message ?: "未知错误")
                    isCapturing = false
                }
                cleanupDisplay()
            }, 500)
        } catch (e: Exception) {
            Logger.e("Service", "Setup error", e)
            showAnswer("设置错误", e.message ?: "未知错误")
            isCapturing = false
        }
    }

    private fun runOCR(bitmap: Bitmap) {
        Logger.i("Service", "Running OCR")
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                Logger.i("Service", "OCR success, text length: ${visionText.text.length}")
                val result = QuestionBank.findBestMatch(visionText.text)
                Logger.i("Service", "Match result: ${result.first} | ${result.second}")
                showAnswer(result.first, result.second)
                isCapturing = false
            }
            .addOnFailureListener { e ->
                Logger.e("Service", "OCR failed", e)
                showAnswer("OCR失败", e.message ?: "未知错误")
                isCapturing = false
            }
    }

    private fun cleanupDisplay() {
        virtualDisplay?.release()
        virtualDisplay = null
    }

    private fun showAnswer(title: String, answer: String) {
        handler.post {
            dismissAnswer()
            answerOverlay = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(android.graphics.Color.argb(230, 20, 20, 20))
                setPadding(32, 24, 32, 24)
                addView(TextView(context).apply {
                    text = title
                    textSize = 13f
                    setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
                })
                addView(TextView(context).apply {
                    text = answer
                    textSize = 20f
                    setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                    setTypeface(null, android.graphics.Typeface.BOLD)
                })
            }
            answerParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = 100
            }
            windowManager?.addView(answerOverlay, answerParams)
            handler.postDelayed({ dismissAnswer() }, 8000)
        }
    }

    private fun dismissAnswer() {
        answerOverlay?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        answerOverlay = null
    }

    override fun onDestroy() {
        Logger.i("Service", "onDestroy")
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(permissionReceiver)
        cleanupDisplay()
        mediaProjection?.stop()
        mediaProjection = null
        floatingView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        dismissAnswer()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Quiz Helper", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "quiz_helper_fg"
        const val ACTION_PERMISSION_GRANTED = "com.quizhelper.app.PERMISSION_GRANTED"
    }
}
