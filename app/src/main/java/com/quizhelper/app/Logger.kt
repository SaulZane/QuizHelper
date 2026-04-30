package com.quizhelper.app

import android.os.Environment
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

object Logger {
    private const val TAG = "QuizHelper"
    private var logFile: File? = null
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun init() {
        try {
            val dir = File(Environment.getExternalStorageDirectory(), "QuizHelper/logs")
            if (!dir.exists()) dir.mkdirs()
            logFile = File(dir, "quiz_helper_${System.currentTimeMillis()}.log")
            logFile?.createNewFile()
            i("Logger", "Log file: ${logFile?.absolutePath}")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to create log file", e)
        }
    }

    private fun log(tag: String, msg: String) {
        val line = "${sdf.format(Date())} [$tag] $msg"
        android.util.Log.d(TAG, line)
        try {
            logFile?.appendText("${line}\n")
        } catch (_: Exception) {}
    }

    fun i(tag: String, msg: String) = log(tag, msg)

    fun e(tag: String, msg: String, t: Throwable? = null) {
        val line = "${sdf.format(Date())} [$tag] ERROR: $msg${if (t != null) " | ${t.message}" else ""}"
        android.util.Log.e(TAG, line, t)
        try {
            logFile?.appendText("${line}\n")
            t?.let { logFile?.appendText("${android.util.Log.getStackTraceString(it)}\n") }
        } catch (_: Exception) {}
    }

    fun getLogPath(): String = logFile?.absolutePath ?: "N/A"
}
