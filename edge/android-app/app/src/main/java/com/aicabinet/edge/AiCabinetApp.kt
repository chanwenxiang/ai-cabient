package com.aicabinet.edge

import android.app.Application
import com.aicabinet.edge.service.CabinetForegroundService

class AiCabinetApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        CabinetForegroundService.start(this)
    }

    companion object {
        lateinit var instance: AiCabinetApp
            private set
    }
}
