package com.conectabot.app

import android.app.Application

class AppContext : Application() {
    companion object {
        lateinit var app: Application
    }

    override fun onCreate() {
        super.onCreate()
        app = this
    }
}