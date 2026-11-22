package com.bharatai.learning

import android.app.Application
import android.util.Log
import com.bharatai.learning.hw.NativeBridge

class SaarthiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Probe the Arm SoC once at start-up (logged for the technical report).
        Log.i("Saarthi", "CPU: ${NativeBridge.cpu}")
    }
}
