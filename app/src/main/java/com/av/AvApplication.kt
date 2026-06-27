package com.av

import android.app.Application

class AvApplication : Application() {

    lateinit var sensorCollector: com.av.sensor.SensorCollector
        private set

    override fun onCreate() {
        super.onCreate()
        sensorCollector = com.av.sensor.SensorCollector(this)
    }
}
