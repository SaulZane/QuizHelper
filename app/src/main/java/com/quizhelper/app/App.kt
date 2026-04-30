package com.quizhelper.app

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Logger.init(this)
        Logger.i("App", "Application started")
        QuestionBank.load(this)
        Logger.i("App", "QuestionBank loaded: ${QuestionBank.size()} questions")
        
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Logger.e("App", "Uncaught exception", throwable)
        }
    }
}
