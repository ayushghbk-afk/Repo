package com.lenix

import android.app.Application
import com.lenix.nativebridge.NativeBridge

class LenixApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NativeBridge.tryLoad()
        // The PRoot engine itself is validated + resolved at START time by
        // EngineInstaller.ensureEngine() (see docs/DECISIONS.md ADR-021): on Android 10+
        // it must be exec'd from the APK's native payload dir, not copied to filesDir.
    }
}
