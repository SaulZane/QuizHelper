package com.quizhelper.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.WindowManager

class CaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.i("CaptureActivity", "onCreate")
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        requestCapture()
    }

    private fun requestCapture() {
        Logger.i("CaptureActivity", "Requesting MediaProjection permission")
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Logger.i("CaptureActivity", "onActivityResult: requestCode=$requestCode, resultCode=$resultCode, hasData=${data != null}")
        
        if (requestCode == REQUEST_CAPTURE && resultCode == RESULT_OK && data != null) {
            MediaProjectionHolder.save(resultCode, data)
            Logger.i("CaptureActivity", "Permission saved, notifying service")
            val intent = Intent(FloatingButtonService.ACTION_PERMISSION_GRANTED).apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
        } else {
            Logger.e("CaptureActivity", "Permission denied or data null")
        }
        finish()
    }

    companion object {
        const val REQUEST_CAPTURE = 2002
    }
}