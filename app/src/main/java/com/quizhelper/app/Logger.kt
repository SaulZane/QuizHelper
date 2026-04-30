package com.quizhelper.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object Logger {
    private var logFile: File? = null
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun init(context: Context) {
        try {
            val dir = File(context.filesDir, "logs")
            if (!dir.exists()) dir.mkdirs()
            logFile = File(dir, "quiz_helper.log")
            if (!logFile!!.exists()) logFile!!.createNewFile()
            android.util.Log.i("QuizHelper", "Log file: ${logFile?.absolutePath}")
        } catch (e: Exception) {
            android.util.Log.e("QuizHelper", "Failed to create log file", e)
        }
    }

    private fun write(tag: String, msg: String) {
        val line = "${sdf.format(Date())} [$tag] $msg"
        android.util.Log.d("QuizHelper", line)
        try {
            logFile?.appendText("$line\n")
        } catch (_: Exception) {}
    }

    fun i(tag: String, msg: String) = write(tag, msg)

    fun e(tag: String, msg: String, t: Throwable? = null) {
        write(tag, "ERROR: $msg${if (t != null) " | ${t.message}" else ""}")
        t?.let {
            try {
                logFile?.appendText("${android.util.Log.getStackTraceString(it)}\n")
            } catch (_: Exception) {}
        }
    }
}
