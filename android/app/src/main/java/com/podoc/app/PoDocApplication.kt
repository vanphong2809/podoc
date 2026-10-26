package com.podoc.app

import android.app.Application
import com.podoc.app.di.AppContainer

class PoDocApplication : Application() {

    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
