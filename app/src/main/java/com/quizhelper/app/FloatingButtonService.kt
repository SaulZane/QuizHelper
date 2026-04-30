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
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        QuestionBank.load(this)
        createNotificationChannel()
        startForeground(1, NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Quiz Helper")
            .setContentText("Floating button active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            ))
            .build())

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createFloatingButton()
    }

    private fun createFloatingButton() {
        floatingView = FrameLayout(this).apply {
            val btnView = ImageView(context).apply {
                setImageResource(android.R.drawable.ic_menu_camera)
                setColorFilter(android.graphics.Color.WHITE)
                setPadding(20, 20, 20, 20)
            }
            addView(btnView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            setBackgroundColor(android.graphics.Color.argb(200, 76, 175, 80))
            val radius = 60f
            val shape = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.argb(200, 76, 175, 80))
            }
            background = shape
        }

        overlayParams = WindowManager.LayoutParams(
            120, 120,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
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
        if (isCapturing) return
        if (mediaProjection != null) {
            captureScreen()
        } else {
            val intent = Intent(this, CaptureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
    }

    fun handleMediaProjectionResult(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, data)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    mediaProjection = null
                    super.onStop()
                }
            }, handler)
            handler.postDelayed({ captureScreen() }, 300)
        } else {
            showAnswerOverlay("Permission denied", "Screen capture permission required")
        }
    }

    private fun captureScreen() {
        if (isCapturing) return
        isCapturing = true

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
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * w

                        val bitmap = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                        bitmap.copyPixelsFromBuffer(buffer)
                        val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, w, h)
                        bitmap.recycle()
                        image.close()
                        runOCR(croppedBitmap)
                    } else {
                        showAnswerOverlay("Capture failed", "No image acquired. Try again.")
                        isCapturing = false
                    }
                } catch (e: Exception) {
                    showAnswerOverlay("Capture error", e.message ?: "Unknown error")
                    isCapturing = false
                }
                cleanupDisplay()
            }, 500)
        } catch (e: Exception) {
            showAnswerOverlay("Capture error", e.message ?: "Unknown error")
            isCapturing = false
        }
    }

    private fun runOCR(bitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val result = QuestionBank.findBestMatch(visionText.text)
                showAnswerOverlay(result.first, result.second)
                isCapturing = false
            }
            .addOnFailureListener { e ->
                showAnswerOverlay("OCR Failed", e.message ?: "Unknown error")
                isCapturing = false
            }
    }

    private fun cleanupDisplay() {
        virtualDisplay?.release()
        virtualDisplay = null
    }

    private fun showAnswerOverlay(title: String, answer: String) {
        handler.post {
            dismissAnswerOverlay()

            answerOverlay = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(android.graphics.Color.argb(220, 30, 30, 30))
                setPadding(32, 24, 32, 24)
                setOnClickListener { dismissAnswerOverlay() }

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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = 80
            }

            windowManager?.addView(answerOverlay, answerParams)

            var alpha = 1f
            val animator = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 3000
                startDelay = 6000
                addUpdateListener {
                    alpha = it.animatedValue as Float
                    answerOverlay?.alpha = alpha
                }
            }
            animator.start()
            handler.postDelayed({ dismissAnswerOverlay() }, 10000)
        }
    }

    private fun dismissAnswerOverlay() {
        answerOverlay?.let { v ->
            try { windowManager?.removeView(v) } catch (_: Exception) {}
        }
        answerOverlay = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        cleanupDisplay()
        mediaProjection?.stop()
        mediaProjection = null
        floatingView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        dismissAnswerOverlay()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Quiz Helper", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "quiz_helper_fg"

        fun setMediaProjection(context: Context, resultCode: Int, data: Intent?) {
            val intent = Intent(context, FloatingButtonService::class.java).apply {
                action = "SET_MEDIA_PROJECTION"
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "SET_MEDIA_PROJECTION") {
            val resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            val data = intent.getParcelableExtra<Intent>("data")
            handleMediaProjectionResult(resultCode, data)
        }
        return START_STICKY
    }
}