package com.aiide

import android.content.Context
import android.app.Application

class AIIDEApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: AIIDEApp
            private set

        fun getAppContext(): Context = instance.applicationContext
    }
}
