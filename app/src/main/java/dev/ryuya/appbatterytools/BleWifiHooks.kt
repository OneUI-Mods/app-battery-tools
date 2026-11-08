package dev.ryuya.appbatterytools

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/** Host-process events only. No service, polling, global BLE switch, or connection termination. */
internal class BleWifiHooks(
    private val module: XposedModule,
    private val context: Context,
    private val report: (String, Throwable?, Int?) -> Unit,
) {
    private class Key(val token: Any) {
        override fun hashCode() =
            if (token is PendingIntent) token.hashCode() else System.identityHashCode(token)

        override fun equals(other: Any?) =
            other is Key &&
                if (token is PendingIntent) token == other.token else token === other.token
    }

    private data class Request(
        val scanner: BluetoothLeScanner,
        val method: Method,
        val arguments: Array<Any?>,
        val stop: Method,
    )

    private val lock = Any()
    private val registry = ScanRegistry<Key, Request>()
    private val handles = mutableListOf<HookHandle>()
    private val callbacks = mutableSetOf<Method>()
    private val internal = ThreadLocal<Boolean>()
    private val main = Handler(Looper.getMainLooper())
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val bluetooth = context.getSystemService(BluetoothManager::class.java)
    private var connected = true // An unavailable network API must preserve original behavior.
    @Volatile private var disposed = false
    @Volatile private var operational = false
    private val wifiNetworks = mutableSetOf<Network>()
    private val transitions = BackgroundExecutors.serial("BatteryBleTransition", idleMillis = 1_000)
    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = networkChanged(network, true)

            override fun onLost(network: Network) = networkChanged(network, false)
        }

    private fun networkChanged(network: Network, available: Boolean) {
        if (disposed) return
        val changed =
            synchronized(lock) {
                if (available) wifiNetworks.add(network) else wifiNetworks.remove(network)
            }
        if (changed)
            try {
                transitions.execute { if (!disposed) refresh() }
            } catch (failure: java.util.concurrent.RejectedExecutionException) {
                if (!disposed) report("error", failure, null)
            }
    }

    private val bluetoothReset =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                if (
                    state == BluetoothAdapter.STATE_OFF ||
                        state == BluetoothAdapter.STATE_TURNING_OFF
                )
                    synchronized(lock) { registry.clear() }
            }
        }

    @SuppressLint("MissingPermission") // One initial snapshot, after the host permission check.
    private fun initialWifiNetworks() =
        connectivity.allNetworks.filterTo(mutableSetOf()) {
            val caps = connectivity.getNetworkCapabilities(it)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }

    private fun allowed() =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED &&
            bluetooth.adapter?.isEnabled == true

    private inline fun <T> platform(block: () -> T): T {
        internal.set(true)
        try {
            return block()
        } finally {
            internal.remove()
        }
    }

    private fun refresh() {
        try {
            val wifi: Boolean
            val changes: List<Pair<Key, Request>>
            synchronized(lock) {
                wifi = wifiNetworks.isNotEmpty()
                if (wifi == connected) return
                connected = wifi
                if (!allowed()) {
                    registry.clear()
                    changes = emptyList()
                } else changes = registry.transition(wifi)
            }
            // Replay can wait for the system scanner. Use an expiring host worker, and release
            // the registry lock between requests rather than blocking the main thread for a batch.
            for ((key, request) in changes) synchronized(lock) {
                if (connected != wifi || !registry.isCurrent(key, request, wifi))
                    return@synchronized
                try {
                    if (wifi) {
                        val result = platform {
                            request.method.invoke(request.scanner, *request.arguments)
                        }
                        if (result is Int && result != 0) registry.remove(key)
                    } else platform { request.stop.invoke(request.scanner, key.token) }
                } catch (failure: Exception) {
                    registry.remove(key)
                    report("error", failure, null)
                }
            }
            report(if (wifi) "wifi_active" else "wifi_paused", null, null)
        } catch (failure: Exception) {
            report("error", failure, null)
        }
    }

    @SuppressLint("MissingPermission") // The module does not request this permission for itself.
    fun install() {
        var registered = false
        var receiverRegistered = false
        try {
            check(
                context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) ==
                    PackageManager.PERMISSION_GRANTED,
            ) {
                "Host lacks network-state permission"
            }
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder()
                    .clearCapabilities()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(),
                networkCallback,
                main,
            )
            registered = true
            synchronized(lock) {
                wifiNetworks.addAll(initialWifiNetworks())
                connected = wifiNetworks.isNotEmpty()
            }
            // A protected system action, also sent by the Bluetooth UID. Reset registrations
            // on adapter shutdown instead of resurrecting them after a subsequent Wi-Fi change.
            context.registerReceiver(
                bluetoothReset,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                Context.RECEIVER_EXPORTED,
            )
            receiverRegistered = true
            val scanner = BluetoothLeScanner::class.java
            for (tokenType in listOf(ScanCallback::class.java, PendingIntent::class.java)) {
                val start =
                    scanner.getDeclaredMethod(
                        "startScan",
                        List::class.java,
                        ScanSettings::class.java,
                        tokenType,
                    )
                val stop = scanner.getDeclaredMethod("stopScan", tokenType)
                handles +=
                    module.hook(start).intercept { chain ->
                        if (
                            !operational ||
                                internal.get() == true ||
                                (chain.args[1] !is ScanSettings &&
                                    !(tokenType == PendingIntent::class.java &&
                                        chain.args[1] == null)) ||
                                chain.args[2] == null ||
                                !allowed()
                        )
                            return@intercept chain.proceed()
                        synchronized(lock) {
                            val token = checkNotNull(chain.args[2])
                            val key = Key(token)
                            if (registry.contains(key)) {
                                if (token is ScanCallback)
                                    main.post {
                                        token.onScanFailed(ScanCallback.SCAN_FAILED_ALREADY_STARTED)
                                    }
                                // PendingIntent registration is accepted asynchronously by Android.
                                // Keep the first desired request; duplicate callback scans still
                                // fail.
                                return@intercept if (token is PendingIntent) 0 else null
                            }
                            if (
                                !registry.canAdd() ||
                                    (token is ScanCallback && !watchFailures(token))
                            ) {
                                if (token is ScanCallback)
                                    main.post {
                                        token.onScanFailed(
                                            ScanCallback
                                                .SCAN_FAILED_APPLICATION_REGISTRATION_FAILED,
                                        )
                                    }
                                return@intercept if (token is PendingIntent)
                                    ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED
                                else null
                            }
                            val args: Array<Any?> = Array(chain.args.size) { chain.args[it] }
                            @Suppress("UNCHECKED_CAST")
                            (args[0] as? List<Any?>)?.let { args[0] = ArrayList(it) }
                            val request =
                                Request(chain.thisObject as BluetoothLeScanner, start, args, stop)
                            registry.add(key, request, connected)
                            if (!connected) return@intercept if (token is PendingIntent) 0 else null
                            try {
                                val result = chain.proceed()
                                if (result is Int && result != 0) registry.remove(key)
                                result
                            } catch (failure: Throwable) {
                                registry.remove(key)
                                throw failure
                            }
                        }
                    }
                handles +=
                    module.hook(stop).intercept { chain ->
                        if (!operational || internal.get() == true || chain.args[0] == null)
                            return@intercept chain.proceed()
                        synchronized(lock) {
                            registry.remove(Key(checkNotNull(chain.args[0])))
                            // A pause may still be queued. Always honor the platform stop too.
                            chain.proceed()
                        }
                    }
            }
            operational = true
            report(if (connected) "wifi_active" else "wifi_paused", null, null)
        } catch (failure: Throwable) {
            disposed = true
            operational = false
            handles.asReversed().forEach { runCatching { it.unhook() } }
            if (registered) runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
            if (receiverRegistered) runCatching { context.unregisterReceiver(bluetoothReset) }
            transitions.shutdownNow()
            throw failure
        }
    }

    private fun watchFailures(token: ScanCallback): Boolean {
        val method =
            try {
                token.javaClass.getMethod("onScanFailed", Int::class.javaPrimitiveType)
            } catch (failure: Exception) {
                report("error", failure, null)
                return false
            }
        if (callbacks.contains(method)) return true
        if (callbacks.size >= 32) return false
        callbacks.add(method)
        try {
            handles +=
                module.hook(method).intercept { chain ->
                    synchronized(lock) {
                        if (
                            chain.args[0] != ScanCallback.SCAN_FAILED_ALREADY_STARTED &&
                                chain.thisObject != null
                        )
                            registry.remove(Key(checkNotNull(chain.thisObject)))
                    }
                    chain.proceed()
                }
        } catch (failure: Exception) {
            callbacks.remove(method)
            report("error", failure, null)
            return false
        } catch (failure: LinkageError) {
            callbacks.remove(method)
            report("error", failure, null)
            return false
        }
        return true
    }
}
