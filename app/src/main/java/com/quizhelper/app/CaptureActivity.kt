package com.quizhelper.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

class CaptureActivity : AppCompatActivity() {

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
            // 将 MediaProjection 实例传递给 Service，由 Service 保存和复用
            FloatingButtonService.setMediaProjection(this, resultCode, data)
        }
        finish()
    }

    companion object {
        const val REQUEST_CAPTURE = 2002
    }
}
