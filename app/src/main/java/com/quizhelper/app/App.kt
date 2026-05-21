package com.quizhelper.app

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            Logger.init(this)
            Logger.i("App", "Application onCreate started")
            QuestionBank.load(this)
            Logger.i("App", "QuestionBank loaded: ${QuestionBank.questions.size} questions")
        } catch (e: Throwable) {
            Logger.e("App", "FATAL in Application.onCreate", e)
        }
    }
}
