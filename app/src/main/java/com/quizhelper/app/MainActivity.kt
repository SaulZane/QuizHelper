package com.quizhelper.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.init(this)
        Logger.i("MainActivity", "App started")
        QuestionBank.load(this)

        if (!Settings.canDrawOverlays(this)) {
            Logger.i("MainActivity", "Requesting overlay permission")
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQUEST_OVERLAY
            )
        } else {
            Logger.i("MainActivity", "Overlay permission already granted, starting service")
            startService()
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY) {
            if (Settings.canDrawOverlays(this)) {
                Logger.i("MainActivity", "Overlay permission granted")
                startService()
            } else {
                Logger.e("MainActivity", "Overlay permission denied")
            }
            finish()
        }
    }

    private fun startService() {
        startForegroundService(Intent(this, FloatingButtonService::class.java))
    }

    companion object {
        const val REQUEST_OVERLAY = 1001
    }
}
