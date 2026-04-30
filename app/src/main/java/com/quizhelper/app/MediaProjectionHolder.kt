package com.quizhelper.app

import android.content.Intent

object MediaProjectionHolder {
    var resultCode: Int = 0
    var data: Intent? = null
    var initialized = false

    fun save(code: Int, intent: Intent?) {
        resultCode = code
        data = intent
        initialized = true
        Logger.i("MediaProjectionHolder", "Saved resultCode=$code data=${intent != null}")
    }

    fun clear() {
        resultCode = 0
        data = null
        initialized = false
        Logger.i("MediaProjectionHolder", "Cleared projection data")
    }
}
