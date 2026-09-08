package com.arcoregeo.campoar

import android.app.Application
import org.maplibre.android.MapLibre

class CampoArApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
    }
}
