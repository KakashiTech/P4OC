package dev.blazelight.p4oc.core.performance

import android.os.Build
import android.os.StrictMode
import android.util.Log
import dev.blazelight.p4oc.BuildConfig

/**
 * Initializes performance monitoring and strict mode for debugging.
 * Should be called in Application.onCreate()
 */
object PerformanceInitializer {
    
    private const val TAG = "PerformanceInit"
    
    fun initialize() {
        if (BuildConfig.DEBUG) {
            enableStrictMode()
        }
        
        // Log performance configuration
        Log.i(TAG, "Performance config: DEBUG=${BuildConfig.DEBUG}, SDK=${Build.VERSION.SDK_INT}")
    }
    
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .detectResourceMismatches()
                .penaltyLog()
                // REMOVED: .penaltyFlashScreen() - causes red flash border
                .build()
        )
        
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build()
        )
        
        Log.d(TAG, "StrictMode enabled for debugging")
    }
}
