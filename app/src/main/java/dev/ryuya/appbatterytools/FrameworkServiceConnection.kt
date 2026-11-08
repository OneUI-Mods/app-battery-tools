package dev.ryuya.appbatterytools

import android.content.Context
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.atomic.AtomicBoolean

/** App-side API 102 handshake. Hook configuration and scope stay on their existing paths. */
object FrameworkServiceConnection {
    private const val TAG = "OneUIModsBinder"
    private val registered = AtomicBoolean()
    private val worker by lazy {
        BackgroundExecutors.serial("XposedServiceMetadata", daemon = true, idleMillis = 1_000)
    }

    fun register(context: Context) {
        if (!registered.compareAndSet(false, true)) return
        val applicationId = context.packageName
        XposedServiceHelper.registerListener(
            object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    // The listener can run inside Vector's provider.call. Return immediately;
                    // remote metadata reads must not delay the daemon's delivery reply.
                    worker.execute {
                        try {
                            val api = service.apiVersion
                            val framework = service.frameworkName
                            val version = service.frameworkVersionCode
                            Log.i(
                                TAG,
                                "CONNECTED $applicationId api=$api framework=$framework version=$version",
                            )
                        } catch (error: RuntimeException) {
                            Log.w(TAG, "METADATA_UNAVAILABLE $applicationId", error)
                        }
                    }
                }

                override fun onServiceDied(service: XposedService) {
                    Log.w(TAG, "DISCONNECTED $applicationId")
                }
            },
        )
    }
}
