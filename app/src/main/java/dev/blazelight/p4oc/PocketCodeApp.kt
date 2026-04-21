package dev.blazelight.p4oc

import android.app.Application
import android.os.Handler
import android.os.Looper
import dev.blazelight.p4oc.core.notification.NotificationEventObserver
import dev.blazelight.p4oc.di.allModules
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import dev.blazelight.p4oc.core.performance.PerformanceInitializer
import dev.blazelight.p4oc.core.performance.MainThreadWatchdog
import dev.blazelight.p4oc.BuildConfig
import dev.blazelight.p4oc.core.security.CredentialStore
import dev.blazelight.p4oc.core.network.NativeMdnsSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PocketCodeApp : Application() {
    
    private val notificationEventObserver: NotificationEventObserver by inject()
    private val credentialStoreForWarmup: CredentialStore by inject()
    @Volatile
    private var notificationsStarted = false
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidContext(this@PocketCodeApp)
            modules(allModules)
        }

        PerformanceInitializer.initialize()
        if (BuildConfig.DEBUG) {
            // Start watchdog on background thread to avoid blocking app startup
            Handler(Looper.getMainLooper()).post {
                MainThreadWatchdog.start(timeoutMs = 2000L, intervalMs = 1000L)
            }
        }

        appScope.launch(Dispatchers.IO) {
            try {
                credentialStoreForWarmup.warmup()
                NativeMdnsSupport.warmup()
            } catch (_: Throwable) { }
        }

        // Lazy init: start notifications only when app enters foreground the first time
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (!notificationsStarted) {
                    notificationsStarted = true
                    notificationEventObserver.start()
                }
            }
        })
    }
}
