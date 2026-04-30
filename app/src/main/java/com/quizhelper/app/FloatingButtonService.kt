package com.quizhelper.app

import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

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
    private val answerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val title = intent?.getStringExtra("title") ?: return
            val answer = intent.getStringExtra("answer") ?: ""
            showAnswerOverlay(title, answer)
        }
    }

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

        registerReceiver(answerReceiver, IntentFilter(ACTION_SHOW_ANSWER), RECEIVER_NOT_EXPORTED)

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
            setBackgroundResource(android.R.drawable.btn_default)
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
        val intent = Intent(this, CaptureActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun showAnswerOverlay(title: String, answer: String) {
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
            duration = 5000
            startDelay = 5000
            addUpdateListener {
                alpha = it.animatedValue as Float
                answerOverlay?.alpha = alpha
            }
        }
        animator.start()
        handler.postDelayed({ if (alpha > 0.05f) dismissAnswerOverlay() }, 11000)
    }

    private fun dismissAnswerOverlay() {
        answerOverlay?.let { v ->
            try { windowManager?.removeView(v) } catch (_: Exception) {}
        }
        answerOverlay = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(answerReceiver)
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
        const val ACTION_SHOW_ANSWER = "com.quizhelper.app.SHOW_ANSWER"

        fun broadcastAnswer(context: Context, title: String, answer: String) {
            val intent = Intent(ACTION_SHOW_ANSWER).apply {
                setPackage(context.packageName)
                putExtra("title", title)
                putExtra("answer", answer)
            }
            context.sendBroadcast(intent)
        }
    }
}
