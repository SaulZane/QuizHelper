package com.quizhelper.app

import android.content.Intent

object MediaProjectionHolder {
    var resultCode: Int = 0
    var data: Intent? = null
    var hasPermission = false

    fun save(code: Int, intent: Intent?) {
        resultCode = code
        data = intent
        hasPermission = true
        Logger.i("MediaProjectionHolder", "Saved permission: resultCode=$code, hasData=${intent != null}")
    }

    fun clear() {
        resultCode = 0
        data = null
        hasPermission = false
        Logger.i("MediaProjectionHolder", "Cleared permission")
    }
}
