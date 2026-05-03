package com.quizhelper.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private var permissionRequested = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.i("MainActivity", "onCreate")
        
        if (!Settings.canDrawOverlays(this)) {
            Logger.i("MainActivity", "Requesting overlay permission")
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQUEST_OVERLAY
            )
        } else {
            checkStoragePermission()
        }
    }

    private fun checkStoragePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) 
            != PackageManager.PERMISSION_GRANTED) {
            Logger.i("MainActivity", "Requesting storage permission")
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_STORAGE)
        } else {
            Logger.i("MainActivity", "Storage permission granted, starting service")
            startService()
            finish()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_STORAGE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Logger.i("MainActivity", "Storage permission granted")
            } else {
                Logger.e("MainActivity", "Storage permission denied")
            }
            startService()
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY) {
            if (Settings.canDrawOverlays(this)) {
                Logger.i("MainActivity", "Overlay permission granted")
                checkStoragePermission()
            } else {
                Logger.e("MainActivity", "Overlay permission denied")
                finish()
            }
        }
    }

    private fun startService() {
        startForegroundService(Intent(this, FloatingButtonService::class.java))
    }

    companion object {
        const val REQUEST_OVERLAY = 1001
        const val REQUEST_STORAGE = 1002
    }
}
