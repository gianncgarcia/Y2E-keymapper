package com.codex.k2er.v2

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.indidevs.android.adbeesh.config.AdbSessionConfig
import com.indidevs.android.adbeesh.notifications.NotificationCallback
import com.indidevs.android.adbeesh.notifications.NotificationCodeProvider
import com.indidevs.android.adbeesh.pairing.AdbPairing
import com.indidevs.android.adbeesh.pairing.AdbPairingCallback
import com.indidevs.android.adbeesh.pairing.AdbPairingStatus
import com.indidevs.android.adbeesh.pairing.AdbPairingWorkflow
import com.indidevs.android.adbeesh.pairing.PairingStage
import com.indidevs.android.adbeesh.notifications.NotificationFlowConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object WirelessActivation {
    interface Listener {
        fun onStatus(message: String)
        fun onFinished(message: String)
        fun onError(message: String)
    }

    private const val PAIRING_ID = "k2er_v2_wireless"
    private const val PREFS = "k2er_v2_wireless_activation"
    private const val STATUS = "status"
    private const val CONNECT_SERVICE_TYPE = "_adb-tls-connect._tcp."
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pairing: AdbPairing? = null
    private var activationJob: Job? = null

    @Synchronized
    private fun pairing(context: Context): AdbPairing {
        if (pairing == null) {
            pairing = AdbPairing.get(
                context.applicationContext,
                PAIRING_ID,
                AdbSessionConfig.Builder()
                    .identityName("K2er v2")
                    .deviceName("K2er v2")
                    .build()
            )
        }
        return pairing!!
    }

    @JvmStatic
    fun isPaired(context: Context): Boolean {
        return pairing(context).status.value == AdbPairingStatus.PAIRED
    }

    @JvmStatic
    fun savedStatus(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(STATUS, "") ?: ""
    }

    @JvmStatic
    fun activate(context: Context, listener: Listener) {
        if (activationJob?.isActive == true) {
            listener.onStatus("Wireless activation is already running.")
            return
        }

        val appContext = context.applicationContext
        val adbPairing = pairing(appContext)
        val flowConfig = NotificationFlowConfig()
        val workflow = AdbPairingWorkflow.Builder()
            .withCodeProvider(NotificationCodeProvider(appContext, flowConfig))
            .addCallback(NotificationCallback(appContext, flowConfig))
            .addCallback(object : AdbPairingCallback {
                override fun onPairingStarted(sessionId: String) {
                    updateStatus(appContext, listener, "Searching for the wireless pairing port…")
                }

                override fun onPortFound(sessionId: String, port: Int) {
                    updateStatus(appContext, listener,
                        "Pairing port $port found. Enter the 6-digit code in the K2er notification.")
                }

                override fun onPairingHandshakeStarted(sessionId: String) {
                    updateStatus(appContext, listener, "Pairing through the discovered wireless debugging port…")
                }

                override fun onPairingSuccess(sessionId: String) {
                    updateStatus(appContext, listener, "Paired. Scanning for the Wireless Debugging connect port…")
                }

                override fun onPairingError(sessionId: String, error: String) {
                    updateStatus(appContext, listener, "Pairing failed: $error")
                }
            })
            .build()

        activationJob = scope.launch {
            try {
                updateStatus(appContext, listener, "Searching for Wireless Debugging…")
                withContext(Dispatchers.IO) {
                    adbPairing.ensurePaired(workflow)
                    updateStatus(appContext, listener, "Scanning for the Wireless Debugging connect port…")
                    val connectService = findConnectService(appContext)
                    updateStatus(appContext, listener,
                        "Connect port ${connectService.port} found. Connecting securely…")
                    val dataDirectory = appContext.getExternalFilesDir(null)
                        ?: throw IllegalStateException("K2er needs Android app storage to start its input daemon.")
                    if (!dataDirectory.exists() && !dataDirectory.mkdirs()) {
                        throw IllegalStateException("Could not prepare K2er's input daemon folder.")
                    }
                    val controller = prepareDaemonScripts(appContext, dataDirectory)
                    adbPairing.openSession(timeoutMs = 15000).use { session ->
                        updateStatus(appContext, listener, "Starting the K2er input daemon through ADB shell…")
                        val dataPath = shellQuote(dataDirectory.absolutePath)
                        val command = "sh ${shellQuote(controller.absolutePath)} $dataPath start"
                        val started = session.shell(command)
                        if (!started.isSuccess || !started.output.contains("K2ER_DAEMON_STARTED")) {
                            throw IllegalStateException(started.error ?: started.output.ifBlank {
                                "Wireless Debugging connected, but K2er could not start its input daemon."
                            })
                        }
                    }
                }

                val message = "Connected over Wireless Debugging. K2er's input daemon has started."
                saveStatus(appContext, message)
                listener.onFinished(message)
            } catch (exception: Exception) {
                val message = when {
                    exception is TimeoutCancellationException ->
                        "Wireless Debugging port scan timed out. Turn Wi-Fi and Wireless Debugging on, then try Connect again."
                    exception.message?.contains("connect port scan", ignoreCase = true) == true ->
                        exception.message!!
                    else -> exception.message?.takeIf { it.isNotBlank() }
                        ?: "Wireless activation failed. Check that Wireless Debugging is on and try again."
                }
                saveStatus(appContext, message)
                listener.onError(message)
            }
        }
    }

    @JvmStatic
    fun forgetPairing(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { stopRemoteDaemon(appContext) }
                pairing(appContext).unpair()
                KeyMappingService.stop(appContext)
            }
            saveStatus(appContext, "Wireless pairing removed.")
        }
    }

    @JvmStatic
    fun deactivate(context: Context, listener: Listener) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                withContext(Dispatchers.IO) { stopRemoteDaemon(appContext) }
                val message = "K2er input daemon stopped. Wireless pairing is still saved."
                saveStatus(appContext, message)
                listener.onFinished(message)
            } catch (exception: Exception) {
                val message = exception.message?.takeIf { it.isNotBlank() }
                    ?: "Could not stop the K2er input daemon. Reconnect through Wireless Debugging and try again."
                saveStatus(appContext, message)
                listener.onError(message)
            }
        }
    }

    private suspend fun stopRemoteDaemon(context: Context) {
        if (!isPaired(context)) return
        val dataDirectory = context.getExternalFilesDir(null) ?: return
        val dataPath = shellQuote(dataDirectory.absolutePath)
        val controller = prepareDaemonScripts(context, dataDirectory)
        val command = "sh ${shellQuote(controller.absolutePath)} $dataPath stop"
        pairing(context).openSession(timeoutMs = 10000).use { session ->
            val result = session.shell(command)
            if (!result.isSuccess || !result.output.contains("K2ER_DAEMON_STOPPED")) {
                throw IllegalStateException(result.error ?: result.output.ifBlank {
                    "Wireless Debugging could not stop K2er's input daemon."
                })
            }
        }
    }

    private fun prepareDaemonScripts(context: Context, directory: File): File {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Could not prepare K2er's input service folder.")
        }
        for (name in listOf("input_daemon.sh", "daemon_control.sh")) {
            val target = File(directory, name)
            context.assets.open(name).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.setReadable(true, false)
        }
        // The shell runtime loads only EngineMain and its small input classes from this APK.
        File(context.applicationInfo.sourceDir).inputStream().use { input ->
            File(directory, "k2er-engine.apk").outputStream().use { output -> input.copyTo(output) }
        }
        return File(directory, "daemon_control.sh")
    }

    private fun updateStatus(context: Context, listener: Listener, message: String) {
        saveStatus(context, message)
        listener.onStatus(message)
    }

    private fun saveStatus(context: Context, message: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(STATUS, message).apply()
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    @Suppress("DEPRECATION")
    private suspend fun findConnectService(context: Context): NsdServiceInfo = withTimeout(25000L) {
        suspendCancellableCoroutine { continuation ->
            val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            if (nsd == null) {
                continuation.resumeWithException(
                    IllegalStateException("Wireless connect port scan is unavailable on this device.")
                )
                return@suspendCancellableCoroutine
            }

            val started = AtomicBoolean(false)
            val resolving = AtomicBoolean(false)
            lateinit var discoveryListener: NsdManager.DiscoveryListener
            fun stopDiscovery() {
                if (started.get()) runCatching { nsd.stopServiceDiscovery(discoveryListener) }
            }

            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    started.set(true)
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (!continuation.isActive || !resolving.compareAndSet(false, true)) return
                    try {
                        nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                                resolving.set(false)
                            }

                            override fun onServiceResolved(info: NsdServiceInfo) {
                                if (info.port > 0 && continuation.isActive) {
                                    stopDiscovery()
                                    continuation.resume(info)
                                } else {
                                    resolving.set(false)
                                }
                            }
                        })
                    } catch (exception: Exception) {
                        resolving.set(false)
                        if (continuation.isActive) continuation.resumeWithException(exception)
                        }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

                override fun onDiscoveryStopped(serviceType: String) {
                    started.set(false)
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    started.set(false)
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IllegalStateException("Wireless connect port scan failed (code $errorCode).")
                        )
                    }
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    started.set(false)
                }
            }

            continuation.invokeOnCancellation { stopDiscovery() }
            try {
                started.set(true)
                nsd.discoverServices(CONNECT_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            } catch (exception: Exception) {
                started.set(false)
                if (continuation.isActive) continuation.resumeWithException(exception)
            }
        }
    }
}
